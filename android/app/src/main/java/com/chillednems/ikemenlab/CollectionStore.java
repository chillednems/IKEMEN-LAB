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
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

/** Bounded app-private collections scoped to one selected library source. */
final class CollectionStore {
    static final int MAX_COLLECTIONS = 50;
    private static final int MAX_FILE_BYTES = 512 * 1024;
    static final class Rule {
        final String field, value;
        Rule(String field, String value) { this.field = field; this.value = value; }
    }
    static final class Record {
        final String id;
        String sourceIdentity;
        String name;
        final boolean smart;
        boolean allRules = true;
        final List<String> characters = new ArrayList<>(), stages = new ArrayList<>();
        final List<Rule> rules = new ArrayList<>();
        Record(String id, String name, boolean smart) { this.id = id; this.name = name; this.smart = smart; }
        RosterProfile.Snapshot snapshot() { return new RosterProfile.Snapshot(characters, stages); }
    }
    private final File directory;
    private final String sourceIdentity;

    CollectionStore(File privateRoot, String sourceIdentity) throws IOException {
        if (sourceIdentity == null || sourceIdentity.isEmpty()) throw new IOException("Collection source is unavailable");
        this.sourceIdentity = sourceIdentity;
        directory = new File(privateRoot, SelectStorage.hash(sourceIdentity.getBytes(StandardCharsets.UTF_8)));
    }

    List<Record> list() throws IOException {
        List<Record> result = new ArrayList<>();
        if (!directory.exists()) return result;
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".properties"));
        if (files == null) throw new IOException("Cannot read private collections");
        if (files.length > MAX_COLLECTIONS) throw new IOException("Collection count exceeds limit");
        for (File file : files) result.add(read(file));
        result.sort(Comparator.comparing(record -> record.name, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    Record get(String id) throws IOException {
        File file = path(id);
        if (!file.isFile()) throw new IOException("Collection no longer exists");
        return read(file);
    }

    Record create(String name, boolean smart, RosterProfile.Snapshot snapshot) throws IOException {
        Record record = new Record(UUID.randomUUID().toString(), name, smart);
        record.sourceIdentity = sourceIdentity;
        if (!smart && snapshot != null) {
            record.characters.addAll(snapshot.characters);
            record.stages.addAll(snapshot.stages);
        }
        if (smart) throw new IOException("Create a smart collection with at least one rule");
        save(record);
        return record;
    }

    void save(Record record) throws IOException {
        if (record.sourceIdentity != null && !record.sourceIdentity.equals(sourceIdentity))
            throw new IOException("Collection belongs to another source");
        record.sourceIdentity = sourceIdentity;
        validate(record);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create private collection folder");
        File target = path(record.id);
        if (!target.isFile() && list().size() >= MAX_COLLECTIONS) throw new IOException("At most 50 collections per source");
        Properties values = new Properties();
        values.setProperty("version", "1");
        values.setProperty("name", record.name);
        values.setProperty("smart", Boolean.toString(record.smart));
        values.setProperty("allRules", Boolean.toString(record.allRules));
        putEntries(values, "character", record.characters);
        putEntries(values, "stage", record.stages);
        values.setProperty("rules", Integer.toString(record.rules.size()));
        for (int i = 0; i < record.rules.size(); i++) {
            values.setProperty("rule." + i + ".field", record.rules.get(i).field);
            values.setProperty("rule." + i + ".value", record.rules.get(i).value);
        }
        File temporary = File.createTempFile("collection-", ".tmp", directory);
        try {
            try (OutputStream output = Files.newOutputStream(temporary.toPath())) { values.store(output, null); }
            if (temporary.length() > MAX_FILE_BYTES) throw new IOException("Collection exceeds private file limit");
            try { Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temporary.toPath()); }
    }

    void delete(String id) throws IOException { Files.deleteIfExists(path(id).toPath()); }

    private File path(String id) throws IOException {
        try { UUID.fromString(id); }
        catch (IllegalArgumentException invalid) { throw new IOException("Invalid collection identifier", invalid); }
        return new File(directory, id + ".properties");
    }

    private Record read(File file) throws IOException {
        if (file.length() > MAX_FILE_BYTES) throw new IOException("Saved collection exceeds limit");
        String filename = file.getName();
        String id = filename.substring(0, filename.length() - ".properties".length());
        try { UUID.fromString(id); }
        catch (IllegalArgumentException invalid) { throw new IOException("Invalid saved collection identifier", invalid); }
        Properties values = new Properties();
        try (InputStream input = Files.newInputStream(file.toPath())) { values.load(input); }
        if (!"1".equals(values.getProperty("version"))) throw new IOException("Unsupported saved collection version");
        String smartValue = values.getProperty("smart"), allValue = values.getProperty("allRules");
        if (!"true".equals(smartValue) && !"false".equals(smartValue)
                || !"true".equals(allValue) && !"false".equals(allValue))
            throw new IOException("Invalid saved collection options");
        Record record = new Record(id, values.getProperty("name"), Boolean.parseBoolean(smartValue));
        record.sourceIdentity = sourceIdentity;
        record.allRules = Boolean.parseBoolean(allValue);
        readEntries(values, "character", record.characters, RosterProfile.MAX_CHARACTERS);
        readEntries(values, "stage", record.stages, RosterProfile.MAX_STAGES);
        int count = count(values.getProperty("rules"), 6);
        for (int i = 0; i < count; i++)
            record.rules.add(new Rule(values.getProperty("rule." + i + ".field"), values.getProperty("rule." + i + ".value")));
        validate(record);
        return record;
    }

    private static void putEntries(Properties values, String prefix, List<String> entries) {
        values.setProperty(prefix + "s", Integer.toString(entries.size()));
        for (int i = 0; i < entries.size(); i++) values.setProperty(prefix + "." + i, entries.get(i));
    }

    private static void readEntries(Properties values, String prefix, List<String> destination, int maximum) throws IOException {
        int count = count(values.getProperty(prefix + "s"), maximum);
        for (int i = 0; i < count; i++) destination.add(values.getProperty(prefix + "." + i));
    }

    private static int count(String text, int maximum) throws IOException {
        try {
            int value = Integer.parseInt(text);
            if (value < 0 || value > maximum) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException | NullPointerException invalid) { throw new IOException("Invalid saved collection count", invalid); }
    }

    private static void validate(Record record) throws IOException {
        try { UUID.fromString(record.id); }
        catch (IllegalArgumentException invalid) { throw new IOException("Invalid collection identifier", invalid); }
        if (record.name == null || record.name.trim().isEmpty() || record.name.length() > 60
                || record.name.indexOf('\n') >= 0 || record.name.indexOf('\r') >= 0)
            throw new IOException("Collection name must be 1–60 characters");
        if (record.smart) {
            if (!record.characters.isEmpty() || !record.stages.isEmpty() || record.rules.isEmpty() || record.rules.size() > 6)
                throw new IOException("Smart collection rules are invalid");
            for (Rule rule : record.rules) SmartCollectionRules.validate(rule);
        } else {
            if (!record.rules.isEmpty()) throw new IOException("Static collection cannot contain smart rules");
            RosterProfile.validate(record.snapshot());
        }
    }
}
