package com.chillednems.ikemenlab;

import android.content.ContentResolver;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/** Existing select.def in a persisted SAF tree. Generic providers offer no atomic replace. */
public final class SafSelectTarget implements SelectStorage.External {
    private static final String MIME_DIR = DocumentsContract.Document.MIME_TYPE_DIR;
    private final ContentResolver resolver;
    private final Uri tree, data, select;
    private final String identity;

    public SafSelectTarget(ContentResolver resolver, Uri tree) throws IOException {
        this.resolver = resolver; this.tree = tree;
        if (!DocumentsContract.isTreeUri(tree)) throw new IOException("Source is not a folder");
        requireGrant(resolver, tree, false);
        Uri root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        data = exactChild(root, "data", true);
        if (data == null) throw new IOException("Source has no data folder");
        select = exactChild(data, "select.def", false);
        if (select == null) throw new IOException("Source has no existing select.def; choose a destination");
        identity = select.toString();
    }

    public static void requireGrant(ContentResolver resolver, Uri tree, boolean write) throws IOException {
        for (UriPermission permission : resolver.getPersistedUriPermissions()) {
            if (permission.getUri().equals(tree) && permission.isReadPermission()
                    && (!write || permission.isWritePermission())) return;
        }
        throw new IOException(write ? "Source folder write access was revoked or never granted"
                : "Source folder read access was revoked");
    }
    @Override public String identity() { return identity; }
    @Override public byte[] read() throws IOException {
        requireGrant(resolver, tree, false);
        return readDocument(select);
    }
    @Override public void replace(byte[] bytes) throws IOException {
        requireGrant(resolver, tree, true);
        if (bytes.length > SelectStorage.MAX_BYTES) throw new IOException("select.def exceeds 2 MB");
        try (OutputStream output = resolver.openOutputStream(select, "rwt")) {
            if (output == null) throw new IOException("Could not open existing select.def for replacement");
            output.write(bytes);
            output.flush();
        } catch (SecurityException failure) { throw new IOException("Source write access denied", failure); }
    }
    @Override public String backup(byte[] preimage, String transactionId) throws IOException {
        requireGrant(resolver, tree, true);
        Uri folder = backupFolder(true);
        if (folder == null) throw new IOException("Could not create source backup directory");
        String name = "select.def.backup." + transactionId + ".bak";
        Uri backup;
        try { backup = DocumentsContract.createDocument(resolver, folder, "application/octet-stream", name); }
        catch (Exception failure) { throw new IOException("Could not create source preimage backup", failure); }
        if (backup == null) throw new IOException("Could not create source preimage backup");
        Uri manifest = null;
        try {
            ManagedBackupFormat.requireExactName(name, displayName(backup));
            try (OutputStream output = resolver.openOutputStream(backup, "rwt")) {
                if (output == null) throw new IOException("Could not write source preimage backup");
                output.write(preimage); output.flush();
            }
            if (!java.security.MessageDigest.isEqual(readDocument(backup), preimage))
                throw new IOException("Source backup readback failed");
            Properties metadata = new Properties();
            metadata.setProperty("sha256", SelectStorage.hash(preimage));
            metadata.setProperty("bytes", Integer.toString(preimage.length));
            long createdAt = System.currentTimeMillis();
            for (SelectStorage.Version existing : listBackups())
                createdAt = Math.max(createdAt, existing.createdAt + 1);
            metadata.setProperty("createdAt", Long.toString(createdAt));
            metadata.setProperty("reason", "source replacement");
            ManagedBackupFormat.mark(metadata, transactionId, "source");
            ByteArrayOutputStream serialized = new ByteArrayOutputStream();
            metadata.store(serialized, "managed select.def source backup");
            String metadataName = "select.def.backup." + transactionId + ".properties";
            manifest = DocumentsContract.createDocument(resolver, folder, "application/octet-stream", metadataName);
            if (manifest == null) throw new IOException("Could not create source backup metadata");
            ManagedBackupFormat.requireExactName(metadataName, displayName(manifest));
            try (OutputStream output = resolver.openOutputStream(manifest, "rwt")) {
                if (output == null) throw new IOException("Could not write source backup metadata");
                output.write(serialized.toByteArray()); output.flush();
            }
            if (!java.security.MessageDigest.isEqual(readDocument(manifest), serialized.toByteArray()))
                throw new IOException("Source backup metadata readback failed");
        } catch (IOException | RuntimeException failure) {
            if (manifest != null) try { DocumentsContract.deleteDocument(resolver, manifest); } catch (Exception ignored) { }
            try { DocumentsContract.deleteDocument(resolver, backup); } catch (Exception ignored) { }
            if (failure instanceof IOException) throw (IOException) failure;
            throw new IOException("Source backup write denied", failure);
        }
        return backup.toString();
    }
    @Override public List<SelectStorage.Version> listBackups() throws IOException {
        requireGrant(resolver, tree, false);
        Uri folder = backupFolder(false);
        List<SelectStorage.Version> result = new ArrayList<>();
        if (folder == null) return result;
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(folder));
        String[] projection = { DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME };
        try (Cursor cursor = resolver.query(children, projection, null, null, null)) {
            if (cursor == null) throw new IOException("Folder provider did not return backups");
            while (cursor.moveToNext()) {
                String name = cursor.getString(1);
                if (name == null || !name.matches("select\\.def\\.backup\\.[0-9a-fA-F-]{36}\\.properties")) continue;
                String id = name.substring("select.def.backup.".length(), name.length() - ".properties".length());
                Uri body = exactChild(folder, "select.def.backup." + id + ".bak", false);
                if (body == null) continue;
                Uri manifest = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0));
                Properties p = new Properties();
                try {
                    p.load(new java.io.ByteArrayInputStream(readDocument(manifest)));
                    byte[] bytes = readDocument(body);
                    if (!ManagedBackupFormat.validSource(id, p, bytes)) continue;
                    result.add(new SelectStorage.Version(id, p, bytes.length));
                } catch (IOException | NumberFormatException ignored) { } // Unverified files remain untouched.
            }
        } catch (SecurityException failure) { throw new IOException("Source backup access denied", failure); }
        result.sort(Comparator.comparingLong((SelectStorage.Version v) -> v.createdAt).reversed());
        return result;
    }
    @Override public byte[] readBackup(String id) throws IOException {
        if (id == null || !id.matches("[0-9a-fA-F-]{36}")) throw new IOException("Invalid source backup ID");
        Uri folder = backupFolder(false);
        if (folder == null) throw new IOException("Source backup folder missing");
        for (SelectStorage.Version version : listBackups()) if (version.id.equals(id)) {
            Uri body = exactChild(folder, "select.def.backup." + id + ".bak", false);
            byte[] selected = readDocument(body);
            if (!SelectStorage.hash(selected).equals(version.sha256)) throw new IOException("Source backup changed");
            return selected;
        }
        throw new IOException("Source backup missing or failed verification");
    }
    @Override public void pruneBackups(Integer retention, String protectedVersionId) throws IOException {
        if (retention == null) return;
        if (retention < 1) throw new IOException("Retention must be positive");
        requireGrant(resolver, tree, true);
        Uri folder = backupFolder(false);
        if (folder == null) return;
        List<SelectStorage.Version> all = listBackups();
        for (int i = retention; i < all.size(); i++) {
            String id = all.get(i).id;
            if (id.equals(protectedVersionId)) continue;
            Uri metadata = exactChild(folder, "select.def.backup." + id + ".properties", false);
            Uri body = exactChild(folder, "select.def.backup." + id + ".bak", false);
            if (metadata == null || body == null) continue;
            if (!DocumentsContract.deleteDocument(resolver, metadata)) throw new IOException("Could not prune source backup metadata");
            if (!DocumentsContract.deleteDocument(resolver, body)) throw new IOException("Could not prune source backup bytes");
        }
    }
    private Uri backupFolder(boolean create) throws IOException {
        Uri folder = exactChild(data, "select-backups", true);
        if (folder == null && create) {
            try { folder = DocumentsContract.createDocument(resolver, data, MIME_DIR, "select-backups"); }
            catch (Exception failure) { throw new IOException("Could not create source backup directory", failure); }
            if (folder != null) ManagedBackupFormat.requireExactName("select-backups", displayName(folder));
        }
        return folder;
    }
    private String displayName(Uri document) throws IOException {
        try (Cursor cursor = resolver.query(document,
                new String[] { DocumentsContract.Document.COLUMN_DISPLAY_NAME }, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) throw new IOException("Provider did not return document name");
            return cursor.getString(0);
        } catch (SecurityException failure) { throw new IOException("Document name access denied", failure); }
    }

    private byte[] readDocument(Uri document) throws IOException {
        try (InputStream input = resolver.openInputStream(document)) {
            if (input == null) throw new IOException("Could not open source document");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int length;
            while ((length = input.read(buffer)) != -1) {
                if (output.size() + length > SelectStorage.MAX_BYTES) throw new IOException("select.def exceeds 2 MB");
                output.write(buffer, 0, length);
            }
            return output.toByteArray();
        } catch (SecurityException failure) { throw new IOException("Source read access denied", failure); }
    }
    private Uri exactChild(Uri parent, String name, boolean directory) throws IOException {
        String parentId = DocumentsContract.getDocumentId(parent);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        String[] projection = { DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE };
        List<String> matches = new ArrayList<>();
        try (Cursor cursor = resolver.query(children, projection, null, null, null)) {
            if (cursor == null) throw new IOException("Folder provider did not return children");
            while (cursor.moveToNext()) {
                String display = cursor.getString(1), mime = cursor.getString(2);
                if (display != null && display.toLowerCase(Locale.ROOT).equals(name.toLowerCase(Locale.ROOT))
                        && (directory == MIME_DIR.equals(mime))) matches.add(cursor.getString(0));
            }
        } catch (SecurityException failure) { throw new IOException("Source folder access denied", failure); }
        if (matches.size() > 1) throw new IOException("Ambiguous source documents named " + name);
        return matches.isEmpty() ? null : DocumentsContract.buildDocumentUriUsingTree(tree, matches.get(0));
    }
}
