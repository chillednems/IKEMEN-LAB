package com.chillednems.ikemenlab;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/** One bounded add-on copied into private staging before any source mutation. */
final class AddonPackage {
    static final long MAX_ARCHIVE = 256L * 1024 * 1024;
    static final long MAX_TOTAL = 512L * 1024 * 1024;
    static final long MAX_FILE = 256L * 1024 * 1024;
    static final int MAX_FILES = 3000, MAX_DEPTH = 12;
    static final class Entry {
        final String path, sha256;
        final long bytes;
        Entry(String path, String sha256, long bytes) {
            this.path = path; this.sha256 = sha256; this.bytes = bytes;
        }
    }
    final File directory;
    final File stageRoot;
    final String name, kind;
    final List<Entry> files;
    final long totalBytes;

    private AddonPackage(File stageRoot, File directory, String name, String kind, List<Entry> files, long totalBytes) {
        this.stageRoot = stageRoot; this.directory = directory; this.name = name; this.kind = kind;
        this.files = files; this.totalBytes = totalBytes;
    }

    static AddonPackage reopen(File stageRoot, String relativeDirectory, String name, String kind,
                               List<Entry> entries, long bytes) throws IOException {
        requireKind(kind);
        validSegment(name);
        if (!relativeDirectory.equals("content") && !relativeDirectory.equals("addon"))
            throw new IOException("Import journal has invalid stage path");
        File directory = new File(stageRoot, relativeDirectory);
        if (!directory.isDirectory()) throw new IOException("Private add-on stage is missing");
        for (Entry entry : entries) validPath(entry.path, false);
        return new AddonPackage(stageRoot, directory, name, kind, entries, bytes);
    }

    static AddonPackage fromFolder(LibraryFiles.Node folder, File privateRoot, String kind) throws IOException {
        requireKind(kind);
        if (folder == null || !folder.directory() || folder.symbolicLink())
            throw new IOException("Choose one real add-on folder; links are unsupported");
        String name = validSegment(folder.name());
        File staging = createStage(privateRoot);
        File content = new File(staging, "content");
        if (!content.mkdir()) { erase(staging); throw new IOException("Cannot stage add-on contents"); }
        Collector collector = new Collector(content);
        try {
            copyFolder(folder, "", 0, collector);
            return finish(staging, content, name, kind, collector);
        } catch (IOException failure) { erase(staging); throw failure; }
    }

    static AddonPackage fromZip(InputStream archive, File privateRoot, String kind) throws IOException {
        requireKind(kind);
        File staging = createStage(privateRoot);
        File compressed = new File(staging, "input.zip");
        try {
            try (InputStream input = archive; FileOutputStream output = new FileOutputStream(compressed)) {
                copyBounded(input, output, MAX_ARCHIVE, null);
                output.getFD().sync();
            }
            ZipNames names = ZipNames.inspect(compressed);
            File content = new File(staging, "content");
            if (!content.mkdir()) throw new IOException("Cannot stage add-on contents");
            Collector collector = new Collector(content);
            try (ZipInputStream zip = new ZipInputStream(new FileInputStream(compressed), StandardCharsets.UTF_8)) {
                ZipEntry entry;
                int observed = 0;
                while ((entry = zip.getNextEntry()) != null) {
                    observed++;
                    if (observed > MAX_FILES * 2) throw new IOException("ZIP has too many entries");
                    String raw = entry.getName();
                    if (!names.entries.remove(raw)) throw new IOException("ZIP entry metadata changed or duplicated");
                    if (ignoredMacMetadata(raw)) {
                        collector.discard(zip, false);
                        zip.closeEntry(); continue;
                    }
                    String path = validPath(raw, entry.isDirectory());
                    if (entry.isDirectory()) {
                        collector.discard(zip, true);
                        collector.addDirectory(path);
                    }
                    else collector.add(path, zip);
                    zip.closeEntry();
                }
                if (!names.entries.isEmpty()) throw new IOException("ZIP central directory does not match entries");
            }
            Files.delete(compressed.toPath());
            String root = singleTopFolder(collector.files);
            String name;
            if (root != null) {
                name = validSegment(root);
                File nested = new File(content, root);
                File flattened = new File(staging, "addon");
                if (!nested.renameTo(flattened)) throw new IOException("Cannot prepare add-on folder");
                erase(content);
                content = flattened;
                for (int i = 0; i < collector.files.size(); i++) {
                    Entry old = collector.files.get(i);
                    collector.files.set(i, new Entry(old.path.substring(root.length() + 1), old.sha256, old.bytes));
                }
            } else {
                name = primaryDefName(collector.files);
            }
            return finish(staging, content, name, kind, collector);
        } catch (IOException | RuntimeException failure) {
            erase(staging);
            if (failure instanceof IOException) throw (IOException) failure;
            throw new IOException("ZIP could not be staged safely", failure);
        }
    }

    private static AddonPackage finish(File stageRoot, File folder, String name, String kind, Collector collector) throws IOException {
        int defCount = 0;
        for (Entry entry : collector.files)
            if (entry.path.indexOf('/') < 0 && entry.path.toLowerCase(Locale.ROOT).endsWith(".def")) defCount++;
        if (defCount != 1) throw new IOException("Add-on needs exactly one root DEF file");
        if (collector.files.isEmpty()) throw new IOException("Add-on folder is empty");
        return new AddonPackage(stageRoot, folder, name, kind, new ArrayList<>(collector.files), collector.total);
    }

    private static String primaryDefName(List<Entry> files) throws IOException {
        String name = null;
        for (Entry entry : files) if (entry.path.indexOf('/') < 0
                && entry.path.toLowerCase(Locale.ROOT).endsWith(".def")) {
            if (name != null) throw new IOException("ZIP has multiple root DEF files");
            name = entry.path.substring(0, entry.path.length() - 4);
        }
        if (name == null) throw new IOException("ZIP has no root DEF file");
        return validSegment(name);
    }

    private static String singleTopFolder(List<Entry> files) {
        String prefix = null;
        for (Entry entry : files) {
            int slash = entry.path.indexOf('/');
            if (slash <= 0) return null;
            String current = entry.path.substring(0, slash);
            if (prefix == null) prefix = current;
            else if (!prefix.equals(current)) return null;
        }
        return prefix;
    }

    private static void copyFolder(LibraryFiles.Node folder, String prefix, int depth, Collector collector) throws IOException {
        if (depth > MAX_DEPTH) throw new IOException("Add-on folder nesting exceeds limit");
        for (LibraryFiles.Node child : LibraryFiles.sorted(folder)) {
            if (child.symbolicLink()) throw new IOException("Add-on folder links are unsupported");
            String segment = validSegment(child.name());
            String path = prefix.isEmpty() ? segment : prefix + "/" + segment;
            if (child.directory()) {
                collector.addDirectory(path);
                copyFolder(child, path, depth + 1, collector);
            }
            else {
                if (child.size() > MAX_FILE) throw new IOException("Add-on file exceeds limit: " + segment);
                try (InputStream input = child.open()) { collector.add(path, input); }
            }
        }
    }

    private static String validPath(String path, boolean directory) throws IOException {
        if (path == null || path.isEmpty() || path.length() > 240 || path.indexOf('\\') >= 0 || path.startsWith("/"))
            throw new IOException("Unsafe add-on path");
        String trimmed = directory && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        String[] parts = trimmed.split("/", -1);
        if (parts.length > MAX_DEPTH + 1) throw new IOException("Add-on path exceeds nesting limit");
        for (String part : parts) validSegment(part);
        return trimmed;
    }

    static String validSegment(String name) throws IOException {
        if (name == null || name.isEmpty() || name.length() > 100 || name.equals(".") || name.equals("..")
                || name.endsWith(" ") || name.endsWith(".") || name.indexOf(':') >= 0)
            throw new IOException("Unsafe add-on name");
        String normalized = Normalizer.normalize(name, Normalizer.Form.NFC);
        if (!normalized.equals(name)) throw new IOException("Non-canonical add-on name");
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '/' || c == '\\' || c < 32 || c == 127) throw new IOException("Unsafe add-on name");
        }
        return name;
    }

    private static boolean ignoredMacMetadata(String path) {
        return path.startsWith("__MACOSX/") || path.equals(".DS_Store") || path.endsWith("/.DS_Store");
    }

    private static File createStage(File root) throws IOException {
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Cannot create private staging folder");
        File folder = new File(root, UUID.randomUUID().toString());
        if (!folder.mkdir()) throw new IOException("Cannot create private add-on stage");
        return folder;
    }

    static void erase(File root) {
        if (root == null || !root.exists()) return;
        File[] children = root.listFiles();
        if (children != null) for (File child : children) erase(child);
        root.delete();
    }

    private static long copyBounded(InputStream input, FileOutputStream output, long maximum,
                                    MessageDigest digest) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long count = 0;
        int length;
        while ((length = input.read(buffer)) != -1) {
            count += length;
            if (count > maximum) throw new IOException("Add-on exceeds byte limit");
            output.write(buffer, 0, length);
            if (digest != null) digest.update(buffer, 0, length);
        }
        return count;
    }

    static String hash(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int length;
                while ((length = input.read(buffer)) != -1) digest.update(buffer, 0, length);
            }
            return hex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IOException(impossible); }
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) text.append(String.format(Locale.ROOT, "%02x", value & 255));
        return text.toString();
    }

    private static void requireKind(String kind) throws IOException {
        if (!"chars".equals(kind) && !"stages".equals(kind)) throw new IOException("Choose characters or stages");
    }

    private static final class Collector {
        final File directory;
        final List<Entry> files = new ArrayList<>();
        final Map<String, String> folded = new HashMap<>();
        final Set<String> directories = new HashSet<>();
        long total, ignored;
        Collector(File directory) { this.directory = directory; }
        void addDirectory(String path) throws IOException {
            validPath(path, true);
            String[] parts = path.split("/");
            String prefix = "";
            for (String part : parts) {
                prefix = prefix.isEmpty() ? part : prefix + "/" + part;
                reserve(prefix, true);
            }
        }
        void add(String path, InputStream input) throws IOException {
            validPath(path, false);
            if (files.size() >= MAX_FILES) throw new IOException("Add-on has too many files");
            int slash = path.lastIndexOf('/');
            if (slash >= 0) addDirectory(path.substring(0, slash));
            reserve(path, false);
            File target = new File(directory, path);
            File parent = target.getParentFile();
            if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot stage add-on file");
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long bytes;
                try (FileOutputStream output = new FileOutputStream(target)) {
                    bytes = copyBounded(input, output, Math.min(MAX_FILE, MAX_TOTAL - total - ignored), digest);
                    output.getFD().sync();
                }
                total += bytes;
                files.add(new Entry(path, hex(digest.digest()), bytes));
            } catch (NoSuchAlgorithmException impossible) { throw new IOException(impossible); }
        }
        void discard(InputStream input, boolean directory) throws IOException {
            byte[] buffer = new byte[8192];
            long bytes = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                bytes += count;
                if (directory && bytes > 0) throw new IOException("ZIP directory entry contains data");
                if (bytes > MAX_FILE || bytes > MAX_TOTAL - total - ignored)
                    throw new IOException("Ignored ZIP entry exceeds byte limit");
            }
            ignored += bytes;
        }
        private void reserve(String path, boolean directory) throws IOException {
            String key = path.toLowerCase(Locale.ROOT);
            String existing = folded.putIfAbsent(key, path);
            if (existing != null && (!existing.equals(path) || !directory || !directories.contains(key)))
                throw new IOException("Duplicate or conflicting add-on path: " + path);
            if (directory) directories.add(key);
            else if (directories.contains(key)) throw new IOException("File conflicts with add-on folder: " + path);
            if (folded.size() > MAX_FILES * 2) throw new IOException("Add-on has too many paths");
        }
    }

    private static final class ZipNames {
        final Set<String> entries = new HashSet<>();
        static ZipNames inspect(File archive) throws IOException {
            ZipNames result = new ZipNames();
            try (ZipFile zip = new ZipFile(archive, StandardCharsets.UTF_8)) {
                java.util.Enumeration<? extends ZipEntry> list = zip.entries();
                while (list.hasMoreElements()) {
                    ZipEntry entry = list.nextElement();
                    if (!result.entries.add(entry.getName())) throw new IOException("Duplicate ZIP entry");
                    if (result.entries.size() > MAX_FILES * 2) throw new IOException("ZIP has too many entries");
                }
            }
            ZipSafety.inspectCentralDirectory(archive);
            return result;
        }
    }
}
