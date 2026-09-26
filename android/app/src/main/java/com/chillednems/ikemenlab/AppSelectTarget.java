package com.chillednems.ikemenlab;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

/** Source select.def with verified preimages in a separate app-private, per-source folder. */
final class AppSelectTarget implements SelectStorage.External {
    private final SelectStorage.External source;
    private final File folder;

    AppSelectTarget(SelectStorage.External source, File appFiles, String libraryIdentity,
                    String sourceTreeIdentity) throws IOException {
        this.source = source;
        String namespace = SelectStorage.hash((libraryIdentity + "\n" + sourceTreeIdentity)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        this.folder = new File(new File(appFiles, "source-preimage-backups"), namespace);
    }
    @Override public String identity() throws IOException {
        if (source == null) throw new IOException("Source folder unavailable; reconnect it in Settings");
        return source.identity();
    }
    @Override public byte[] read() throws IOException { if (source == null) identity(); return source.read(); }
    @Override public void replace(byte[] bytes) throws IOException { if (source == null) identity(); source.replace(bytes); }
    @Override public String backupIdentity() throws IOException { return "app:" + folder.getCanonicalPath(); }
    @Override public String backupLabel() { return "App backup directory"; }
    @Override public String backup(byte[] preimage, String transactionId) throws IOException {
        if (!transactionId.matches("[0-9a-fA-F-]{36}")) throw new IOException("Invalid backup transaction");
        if (preimage.length > SelectStorage.MAX_BYTES) throw new IOException("select.def exceeds 2 MB");
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Could not create app backup directory");
        File body = body(transactionId), manifest = manifest(transactionId);
        if (body.exists() || manifest.exists()) throw new IOException("Backup ID already exists");
        try {
            writeSynced(body, preimage);
            if (!java.security.MessageDigest.isEqual(readLimited(body), preimage))
                throw new IOException("App backup readback failed");
            Properties metadata = new Properties();
            metadata.setProperty("sha256", SelectStorage.hash(preimage));
            metadata.setProperty("bytes", Integer.toString(preimage.length));
            long createdAt = System.currentTimeMillis();
            for (SelectStorage.Version existing : listBackups())
                createdAt = Math.max(createdAt, existing.createdAt + 1);
            metadata.setProperty("createdAt", Long.toString(createdAt));
            metadata.setProperty("reason", "source replacement");
            ManagedBackupFormat.mark(metadata, transactionId, "source");
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            metadata.store(output, "managed select.def source backup");
            writeSynced(manifest, output.toByteArray());
            if (!java.security.MessageDigest.isEqual(readLimited(manifest), output.toByteArray()))
                throw new IOException("App backup metadata readback failed");
            return body.getAbsolutePath();
        } catch (IOException failure) {
            Files.deleteIfExists(manifest.toPath());
            Files.deleteIfExists(body.toPath());
            throw failure;
        }
    }
    @Override public List<SelectStorage.Version> listBackups() throws IOException {
        List<SelectStorage.Version> result = new ArrayList<>();
        if (!folder.isDirectory()) return result;
        File[] entries = folder.listFiles();
        if (entries == null) throw new IOException("App backup directory unavailable");
        if (entries.length > 20_000) throw new IOException("App backup directory exceeds 20,000 entries");
        for (File entry : entries) {
            String name = entry.getName();
            if (!name.matches("select\\.def\\.backup\\.[0-9a-fA-F-]{36}\\.properties")) continue;
            String id = name.substring("select.def.backup.".length(), name.length() - ".properties".length());
            File body = body(id);
            try {
                Properties p = new Properties();
                p.load(new java.io.ByteArrayInputStream(readLimited(entry)));
                byte[] bytes = readLimited(body);
                if (ManagedBackupFormat.validSource(id, p, bytes))
                    result.add(new SelectStorage.Version(id, p, bytes.length));
            } catch (IOException | NumberFormatException ignored) { } // Unknown files remain untouched.
        }
        result.sort(Comparator.comparingLong((SelectStorage.Version v) -> v.createdAt).reversed());
        return result;
    }
    @Override public byte[] readBackup(String id) throws IOException {
        if (id == null || !id.matches("[0-9a-fA-F-]{36}")) throw new IOException("Invalid app backup ID");
        for (SelectStorage.Version version : listBackups()) if (version.id.equals(id)) {
            byte[] bytes = readLimited(body(id));
            if (!SelectStorage.hash(bytes).equals(version.sha256)) throw new IOException("App backup changed");
            return bytes;
        }
        throw new IOException("App backup missing or failed verification");
    }
    @Override public void pruneBackups(Integer retention, String protectedVersionId) throws IOException {
        if (retention == null) return;
        if (retention < 1) throw new IOException("Retention must be positive");
        List<SelectStorage.Version> versions = listBackups();
        for (int i = retention; i < versions.size(); i++) {
            String id = versions.get(i).id;
            if (id.equals(protectedVersionId)) continue;
            Files.deleteIfExists(manifest(id).toPath());
            Files.deleteIfExists(body(id).toPath());
        }
    }
    private File body(String id) { return new File(folder, "select.def.backup." + id + ".bak"); }
    private File manifest(String id) { return new File(folder, "select.def.backup." + id + ".properties"); }
    private static void writeSynced(File file, byte[] bytes) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(bytes); output.flush(); output.getFD().sync();
        }
    }
    private static byte[] readLimited(File file) throws IOException {
        if (!file.isFile() || file.length() > SelectStorage.MAX_BYTES) throw new IOException("Backup missing or exceeds 2 MB");
        byte[] bytes = Files.readAllBytes(file.toPath());
        if (bytes.length > SelectStorage.MAX_BYTES) throw new IOException("Backup exceeds 2 MB");
        return bytes;
    }
}
