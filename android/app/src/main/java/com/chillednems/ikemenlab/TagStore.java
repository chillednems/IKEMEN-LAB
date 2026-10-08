package com.chillednems.ikemenlab;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;

/** Small app-private manual tags; one hashed file per source identity. */
final class TagStore {
    private static final int MAX_BYTES = 128 * 1024;
    private static final int MAX_ITEMS = 1000;
    private static final int MAX_TAGS = 8;
    private static final int MAX_TAG_LENGTH = 24;
    private final File file;
    private final Map<String, List<String>> entries = new LinkedHashMap<>();

    TagStore(File privateDirectory, String sourceIdentity) throws IOException {
        if (sourceIdentity == null || sourceIdentity.isEmpty()) throw new IOException("Tag source is unavailable");
        file = new File(privateDirectory, SelectStorage.hash(sourceIdentity.getBytes(StandardCharsets.UTF_8)) + ".properties");
        if (!file.isFile()) return;
        if (file.length() > MAX_BYTES) throw new IOException("Saved tags exceed size limit");
        Properties loaded = new Properties();
        try (InputStream input = Files.newInputStream(file.toPath())) { loaded.load(input); }
        if (loaded.size() > MAX_ITEMS) throw new IOException("Saved tags exceed item limit");
        for (String key : loaded.stringPropertyNames()) {
            if (key.length() > 500) throw new IOException("Saved tag key is invalid");
            List<String> tags = parse(loaded.getProperty(key));
            if (!tags.isEmpty()) entries.put(key, tags);
        }
    }

    List<String> get(String itemKey) {
        List<String> tags = entries.get(itemKey);
        return tags == null ? Collections.emptyList() : Collections.unmodifiableList(tags);
    }

    List<String> allTags() {
        TreeSet<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (List<String> tags : entries.values()) names.addAll(tags);
        return new ArrayList<>(names);
    }

    void toggle(String itemKey, String tag) throws IOException {
        if (itemKey == null || itemKey.isEmpty() || itemKey.length() > 500) throw new IOException("Invalid item tag key");
        String clean = validTag(tag);
        List<String> next = new ArrayList<>(get(itemKey));
        boolean removed = next.removeIf(existing -> existing.equalsIgnoreCase(clean));
        if (!removed) {
            if (next.size() >= MAX_TAGS) throw new IOException("At most eight manual tags per item");
            next.add(clean);
        }
        List<String> previous = entries.get(itemKey);
        if (next.isEmpty()) entries.remove(itemKey);
        else {
            if (!entries.containsKey(itemKey) && entries.size() >= MAX_ITEMS)
                throw new IOException("At most 1,000 tagged items per source");
            entries.put(itemKey, next);
        }
        try { save(); }
        catch (IOException failed) {
            if (previous == null) entries.remove(itemKey);
            else entries.put(itemKey, previous);
            throw failed;
        }
    }

    private static List<String> parse(String encoded) throws IOException {
        if (encoded == null || encoded.isEmpty()) return new ArrayList<>();
        String[] parts = encoded.split("\u001f", -1);
        if (parts.length > MAX_TAGS) throw new IOException("Saved tags exceed per-item limit");
        List<String> result = new ArrayList<>();
        for (String part : parts) {
            String tag = validTag(part);
            for (String existing : result) if (existing.equalsIgnoreCase(tag)) throw new IOException("Duplicate saved tag");
            result.add(tag);
        }
        return result;
    }

    private static String validTag(String value) throws IOException {
        if (value == null) throw new IOException("Tag is empty");
        String tag = value.trim();
        if (tag.isEmpty() || tag.length() > MAX_TAG_LENGTH || tag.indexOf('\u001f') >= 0
                || tag.indexOf('\n') >= 0 || tag.indexOf('\r') >= 0)
            throw new IOException("Tag must be 1–24 characters on one line");
        return tag;
    }

    private void save() throws IOException {
        File directory = file.getParentFile();
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create private tag folder");
        Properties stored = new Properties();
        for (Map.Entry<String, List<String>> entry : entries.entrySet())
            stored.setProperty(entry.getKey(), String.join("\u001f", entry.getValue()));
        File temporary = File.createTempFile("tags-", ".tmp", directory);
        try {
            try (OutputStream output = Files.newOutputStream(temporary.toPath())) { stored.store(output, null); }
            if (temporary.length() > MAX_BYTES) throw new IOException("Saved tags exceed size limit");
            try { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temporary.toPath()); }
    }
}
