package com.chillednems.ikemenlab;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/** Add-only source transaction; provider writes are never assumed atomic. */
final class AddonInstallTransaction {
    interface Destination {
        final class Node {
            final String id, name;
            final boolean directory;
            Node(String id, String name, boolean directory) {
                this.id = id; this.name = name; this.directory = directory;
            }
        }
        String identity();
        Node target(String kind) throws IOException;
        List<Node> children(Node folder) throws IOException;
        void requireCapabilities(Node folder) throws IOException;
        Node create(Node parent, String name, boolean directory) throws IOException;
        void write(Node file, InputStream input, long bytes) throws IOException;
        InputStream read(Node file) throws IOException;
        Node rename(Node folder, String name) throws IOException;
        void delete(Node node) throws IOException;
    }
    static final class Review {
        final String source, parentId, kind, name, manifest, healthSignature;
        final boolean blocked;
        final long bytes;
        final int files;
        Review(String source, String parentId, String kind, String name, String manifest, String healthSignature,
               boolean blocked, long bytes, int files) {
            this.source = source; this.parentId = parentId; this.kind = kind; this.name = name;
            this.manifest = manifest; this.healthSignature = healthSignature; this.blocked = blocked;
            this.bytes = bytes; this.files = files;
        }
    }
    private static final String JOURNAL = "install.properties";
    private static final String PENDING_PREFIX = ".ikemen-pending-";
    private static final long MAX_JOURNAL = 16L * 1024 * 1024;

    static Review review(AddonPackage addon, Destination destination, LibraryFiles.Node source) throws IOException {
        verifyStage(addon);
        AddonHealth.Report health = AddonHealth.inspectStage(addon, source);
        Destination.Node parent = destination.target(addon.kind);
        destination.requireCapabilities(parent);
        requireFree(destination.children(parent), addon.name);
        return new Review(destination.identity(), parent.id, addon.kind, addon.name,
                manifest(addon), health.signature(), health.blocked, addon.totalBytes, addon.files.size());
    }

    static void install(AddonPackage addon, Destination destination, LibraryFiles.Node source,
                        Review reviewed) throws IOException {
        Review fresh = review(addon, destination, source);
        if (fresh.blocked || reviewed.blocked)
            throw new IOException("Add-on DEF or required file references failed health validation");
        if (!same(reviewed, fresh)) throw new IOException("Add-on or destination changed; review import again");
        File journalFile = new File(addon.stageRoot, JOURNAL);
        if (journalFile.exists()) throw new IOException("Resolve the previous incomplete add-on install first");
        String pendingName = PENDING_PREFIX + UUID.randomUUID();
        Properties journal = new Properties();
        journal.setProperty("version", "1");
        journal.setProperty("source", reviewed.source);
        journal.setProperty("parent", reviewed.parentId);
        journal.setProperty("kind", reviewed.kind);
        journal.setProperty("name", reviewed.name);
        journal.setProperty("pendingName", pendingName);
        journal.setProperty("manifest", reviewed.manifest);
        journal.setProperty("stageDirectory", addon.directory.getName());
        journal.setProperty("fileCount", Integer.toString(addon.files.size()));
        for (int i = 0; i < addon.files.size(); i++) {
            AddonPackage.Entry entry = addon.files.get(i);
            journal.setProperty("file." + i + ".path", entry.path);
            journal.setProperty("file." + i + ".hash", entry.sha256);
            journal.setProperty("file." + i + ".bytes", Long.toString(entry.bytes));
        }
        journal.setProperty("phase", "prepared");
        journal.setProperty("created", "0");
        save(journalFile, journal);
        Destination.Node parent = destination.target(addon.kind);
        Destination.Node pending = null;
        try {
            requireFree(destination.children(parent), pendingName);
            journal.setProperty("phase", "creating"); save(journalFile, journal);
            pending = destination.create(parent, pendingName, true);
            if (pending == null || pending.id == null)
                throw new IOException("Provider did not identify the pending folder");
            journal.setProperty("pendingId", pending.id);
            journal.setProperty("phase", "writing");
            save(journalFile, journal);
            requireExact(pending, pendingName, true);
            destination.requireCapabilities(pending);
            Map<String, Destination.Node> folders = new HashMap<>();
            folders.put("", pending);
            for (AddonPackage.Entry entry : addon.files) {
                String[] pieces = entry.path.split("/");
                String path = "";
                for (int i = 0; i < pieces.length - 1; i++) {
                    String next = path.isEmpty() ? pieces[i] : path + "/" + pieces[i];
                    if (!folders.containsKey(next)) {
                        Destination.Node created = destination.create(folders.get(path), pieces[i], true);
                        if (created == null || created.id == null)
                            throw new IOException("Provider did not identify a created folder");
                        record(journalFile, journal, next, created);
                        requireExact(created, pieces[i], true);
                        folders.put(next, created);
                    }
                    path = next;
                }
                Destination.Node file = destination.create(folders.get(path), pieces[pieces.length - 1], false);
                if (file == null || file.id == null)
                    throw new IOException("Provider did not identify a created file");
                record(journalFile, journal, entry.path, file);
                requireExact(file, pieces[pieces.length - 1], false);
                try (InputStream input = new FileInputStream(new File(addon.directory, entry.path))) {
                    destination.write(file, input, entry.bytes);
                }
                if (!entry.sha256.equals(readHash(destination.read(file), entry.bytes)))
                    throw new IOException("Provider readback hash differs: " + entry.path);
            }
            verifyTree(addon, destination, pending, journal, true);
            journal.setProperty("phase", "verified"); save(journalFile, journal);
            requireFree(destination.children(parent), addon.name);
            Destination.Node renamed = destination.rename(pending, addon.name);
            if (renamed == null || renamed.id == null)
                throw new IOException("Provider did not identify the renamed add-on");
            journal.setProperty("finalId", renamed.id);
            journal.setProperty("phase", "renamed"); save(journalFile, journal);
            requireExact(renamed, addon.name, true);
            Destination.Node exact = exactChild(destination.children(parent), addon.name);
            if (exact == null || !exact.id.equals(renamed.id)) throw new IOException("Final add-on name or identity changed");
            verifyTree(addon, destination, exact, journal, false);
            journal.setProperty("phase", "committed"); save(journalFile, journal);
            AddonPackage.erase(addon.stageRoot);
        } catch (IOException failure) {
            throw new IOException("Add-on install incomplete; open Import recovery. " + failure.getMessage(), failure);
        }
    }

    static String pendingDescription(File stageRoot) throws IOException {
        File file = new File(stageRoot, JOURNAL);
        if (!file.isFile()) return null;
        Properties journal = load(file);
        return journal.getProperty("name", "Unknown") + " · " + journal.getProperty("phase", "unknown");
    }

    static void recover(File stageRoot, Destination destination) throws IOException {
        File journalFile = new File(stageRoot, JOURNAL);
        Properties journal = load(journalFile);
        if (!destination.identity().equals(journal.getProperty("source")))
            throw new IOException("Reconnect the original source folder for recovery");
        Destination.Node parent = destination.target(journal.getProperty("kind"));
        if (!parent.id.equals(journal.getProperty("parent")))
            throw new IOException("Import destination changed; recovery is unavailable");
        String phase = journal.getProperty("phase");
        if ("committed".equals(phase)) { AddonPackage.erase(stageRoot); return; }
        String pendingName = journal.getProperty("pendingName");
        if (pendingName == null || !pendingName.matches("\\.ikemen-pending-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new IOException("Import journal has invalid pending-folder identity");
        List<Destination.Node> parentChildren = destination.children(parent);
        Destination.Node finalNode = exactChild(parentChildren, journal.getProperty("name"));
        String finalId = journal.getProperty("finalId");
        if (finalId != null && (finalNode == null || !finalNode.id.equals(finalId)))
            throw new IOException("Renamed add-on identity or name is uncertain; it was not removed");
        if (finalNode != null && ("renamed".equals(phase) || "verified".equals(phase))) {
            if (!finalNode.id.equals(journal.getProperty("finalId", journal.getProperty("pendingId"))))
                throw new IOException("Final add-on identity is uncertain; source was left unchanged");
            AddonPackage staged = staged(stageRoot, journal);
            verifyTree(staged, destination, finalNode, journal, false);
            AddonPackage.erase(stageRoot);
            return;
        }
        Destination.Node pending = byId(parentChildren, journal.getProperty("pendingId"));
        if (pending == null) {
            if ("creating".equals(phase))
                throw new IOException("Pending creation outcome is uncertain; import journal retained");
            if (journal.getProperty("pendingId") == null)
                for (Destination.Node child : parentChildren)
                    if (fold(child.name).startsWith(fold(pendingName)))
                        throw new IOException("Pending creation outcome is uncertain; import journal retained");
            if ("verified".equals(phase) || "renamed".equals(phase))
                throw new IOException("Provider rename outcome is uncertain; import journal retained");
            AddonPackage.erase(stageRoot); return;
        }
        if (!pending.directory || !pending.name.equals(pendingName))
            throw new IOException("Pending folder name or type changed; journal retained");
        Map<String, String> owned = owned(journal);
        java.util.Set<String> remaining = new java.util.HashSet<>(owned.values());
        verifyOwnedIds(destination, pending, remaining);
        if (!remaining.isEmpty())
            throw new IOException("Some created documents are no longer under the pending folder; journal retained");
        destination.delete(pending);
        if (byId(destination.children(parent), pending.id) != null)
            throw new IOException("Provider did not remove the pending folder");
        AddonPackage.erase(stageRoot);
    }

    private static AddonPackage staged(File stageRoot, Properties journal) throws IOException {
        int count;
        try { count = Integer.parseInt(journal.getProperty("fileCount")); }
        catch (NumberFormatException invalid) { throw new IOException("Import journal is damaged", invalid); }
        if (count < 1 || count > AddonPackage.MAX_FILES) throw new IOException("Import journal file count is invalid");
        List<AddonPackage.Entry> entries = new ArrayList<>();
        long total = 0;
        for (int i = 0; i < count; i++) {
            String path = journal.getProperty("file." + i + ".path");
            String hash = journal.getProperty("file." + i + ".hash");
            long bytes;
            try { bytes = Long.parseLong(journal.getProperty("file." + i + ".bytes")); }
            catch (NumberFormatException invalid) { throw new IOException("Import journal is damaged", invalid); }
            if (path == null || hash == null || !hash.matches("[0-9a-f]{64}")
                    || bytes < 0 || bytes > AddonPackage.MAX_FILE || total + bytes > AddonPackage.MAX_TOTAL)
                throw new IOException("Import journal is damaged");
            entries.add(new AddonPackage.Entry(path, hash, bytes));
            total += bytes;
        }
        AddonPackage addon = AddonPackage.reopen(stageRoot, journal.getProperty("stageDirectory", ""),
                journal.getProperty("name", ""), journal.getProperty("kind", ""), entries, total);
        verifyStage(addon);
        if (!manifest(addon).equals(journal.getProperty("manifest")))
            throw new IOException("Private add-on stage no longer matches import review");
        return addon;
    }

    private static void record(File file, Properties journal, String path, Destination.Node node) throws IOException {
        int count = Integer.parseInt(journal.getProperty("created"));
        journal.setProperty("created." + count + ".path", path);
        journal.setProperty("created." + count + ".id", node.id);
        journal.setProperty("created", Integer.toString(count + 1));
        save(file, journal);
    }

    private static Map<String, String> owned(Properties journal) throws IOException {
        Map<String, String> result = new HashMap<>();
        int count;
        try { count = Integer.parseInt(journal.getProperty("created")); }
        catch (NumberFormatException invalid) { throw new IOException("Import journal is damaged", invalid); }
        if (count < 0 || count > AddonPackage.MAX_FILES + AddonPackage.MAX_FILES * AddonPackage.MAX_DEPTH)
            throw new IOException("Import journal exceeds limit");
        for (int i = 0; i < count; i++) {
            String path = journal.getProperty("created." + i + ".path");
            String id = journal.getProperty("created." + i + ".id");
            if (path == null || id == null || result.put(path, id) != null)
                throw new IOException("Import journal is damaged");
        }
        return result;
    }

    private static void verifyOwnedTree(Destination destination, Destination.Node folder, String prefix,
                                        Map<String, String> owned, java.util.Set<String> remaining) throws IOException {
        for (Destination.Node child : destination.children(folder)) {
            String path = prefix.isEmpty() ? child.name : prefix + "/" + child.name;
            if (!child.id.equals(owned.get(path)))
                throw new IOException("Pending folder has unknown or changed content; it was not removed");
            if (!remaining.remove(child.id)) throw new IOException("Provider duplicated an add-on document");
            if (child.directory) verifyOwnedTree(destination, child, path, owned, remaining);
        }
    }

    private static void verifyOwnedIds(Destination destination, Destination.Node folder,
                                       java.util.Set<String> ids) throws IOException {
        for (Destination.Node child : destination.children(folder)) {
            if (!ids.remove(child.id))
                throw new IOException("Pending folder has unknown or changed content; it was not removed");
            if (child.directory) verifyOwnedIds(destination, child, ids);
        }
    }

    private static Destination.Node byId(List<Destination.Node> children, String id) {
        if (id == null) return null;
        for (Destination.Node child : children) if (id.equals(child.id)) return child;
        return null;
    }

    private static void verifyTree(AddonPackage addon, Destination destination, Destination.Node root,
                                   Properties journal, boolean requireIds) throws IOException {
        Map<String, String> owned = owned(journal);
        if (requireIds) {
            java.util.Set<String> remaining = new java.util.HashSet<>(owned.values());
            verifyOwnedTree(destination, root, "", owned, remaining);
            if (!remaining.isEmpty()) throw new IOException("Provider omitted a created add-on document");
        }
        else verifyNamedTree(destination, root, "", owned);
        for (AddonPackage.Entry entry : addon.files) {
            Destination.Node current = root;
            for (String part : entry.path.split("/")) {
                current = exactChild(destination.children(current), part);
                if (current == null) throw new IOException("Provider omitted add-on file: " + entry.path);
            }
            if (requireIds && !current.id.equals(owned.get(entry.path)) || current.directory
                    || !entry.sha256.equals(readHash(destination.read(current), entry.bytes)))
                throw new IOException("Provider changed staged add-on: " + entry.path);
        }
    }

    private static void verifyNamedTree(Destination destination, Destination.Node folder, String prefix,
                                        Map<String, String> expected) throws IOException {
        for (Destination.Node child : destination.children(folder)) {
            String path = prefix.isEmpty() ? child.name : prefix + "/" + child.name;
            if (!expected.containsKey(path))
                throw new IOException("Final add-on contains an unexpected file");
            if (child.directory) verifyNamedTree(destination, child, path, expected);
        }
    }

    private static String readHash(InputStream input, long expectedBytes) throws IOException {
        try (InputStream source = input) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            long count = 0;
            int length;
            while ((length = source.read(buffer)) != -1) {
                count += length;
                if (count > expectedBytes) throw new IOException("Provider readback exceeds expected length");
                digest.update(buffer, 0, length);
            }
            if (count != expectedBytes) throw new IOException("Provider readback length differs");
            return hex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IOException(impossible); }
    }

    private static void verifyStage(AddonPackage addon) throws IOException {
        long total = 0;
        for (AddonPackage.Entry entry : addon.files) {
            File file = new File(addon.directory, entry.path);
            if (!file.isFile() || file.length() != entry.bytes || !AddonPackage.hash(file).equals(entry.sha256))
                throw new IOException("Private add-on stage changed; choose it again");
            total += entry.bytes;
        }
        if (total != addon.totalBytes) throw new IOException("Private add-on stage size changed");
    }

    private static String manifest(AddonPackage addon) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (AddonPackage.Entry entry : addon.files) {
                digest.update((entry.path + "\0" + entry.bytes + "\0" + entry.sha256 + "\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            return hex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IOException(impossible); }
    }

    private static boolean same(Review a, Review b) {
        return a != null && a.source.equals(b.source) && a.parentId.equals(b.parentId)
                && a.kind.equals(b.kind) && a.name.equals(b.name) && a.manifest.equals(b.manifest)
                && a.healthSignature.equals(b.healthSignature) && a.blocked == b.blocked
                && a.bytes == b.bytes && a.files == b.files;
    }

    private static void requireFree(List<Destination.Node> children, String name) throws IOException {
        for (Destination.Node child : children)
            if (fold(child.name).equals(fold(name))) throw new IOException("Destination name already exists: " + name);
    }

    private static Destination.Node exactChild(List<Destination.Node> children, String name) throws IOException {
        Destination.Node found = null;
        for (Destination.Node child : children) if (fold(child.name).equals(fold(name))) {
            if (found != null) throw new IOException("Ambiguous destination name: " + name);
            if (!child.name.equals(name)) throw new IOException("Provider changed exact destination name");
            found = child;
        }
        return found;
    }

    private static String fold(String name) {
        return java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    private static void requireExact(Destination.Node node, String name, boolean directory) throws IOException {
        if (node == null || !name.equals(node.name) || node.directory != directory)
            throw new IOException("Provider did not create the exact add-on name");
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) value.append(String.format(Locale.ROOT, "%02x", b & 255));
        return value.toString();
    }

    private static Properties load(File file) throws IOException {
        if (!file.isFile() || file.length() > MAX_JOURNAL) throw new IOException("Import journal missing or too large");
        Properties result = new Properties();
        try (FileInputStream input = new FileInputStream(file)) { result.load(input); }
        if (!"1".equals(result.getProperty("version"))) throw new IOException("Unsupported import journal");
        return result;
    }

    private static void save(File file, Properties values) throws IOException {
        File temporary = new File(file.getParentFile(), JOURNAL + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            values.store(output, "add-only import journal"); output.getFD().sync();
        }
        if (temporary.length() > MAX_JOURNAL) throw new IOException("Import journal exceeds limit");
        try { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING); }
        catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
