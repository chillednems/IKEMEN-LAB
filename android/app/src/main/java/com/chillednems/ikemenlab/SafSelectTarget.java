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
import java.util.List;
import java.util.Locale;

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
        Uri folder = exactChild(data, "select-backups", true);
        if (folder == null) {
            try { folder = DocumentsContract.createDocument(resolver, data, MIME_DIR, "select-backups"); }
            catch (Exception failure) { throw new IOException("Could not create source backup directory", failure); }
        }
        if (folder == null) throw new IOException("Could not create source backup directory");
        String name = "select.def.backup." + transactionId + ".bak";
        Uri backup;
        try { backup = DocumentsContract.createDocument(resolver, folder, "application/octet-stream", name); }
        catch (Exception failure) { throw new IOException("Could not create source preimage backup", failure); }
        if (backup == null) throw new IOException("Could not create source preimage backup");
        try {
            try (OutputStream output = resolver.openOutputStream(backup, "rwt")) {
                if (output == null) throw new IOException("Could not write source preimage backup");
                output.write(preimage); output.flush();
            }
            if (!java.security.MessageDigest.isEqual(readDocument(backup), preimage))
                throw new IOException("Source backup readback failed");
        } catch (IOException | RuntimeException failure) {
            try { DocumentsContract.deleteDocument(resolver, backup); } catch (Exception ignored) { }
            if (failure instanceof IOException) throw (IOException) failure;
            throw new IOException("Source backup write denied", failure);
        }
        return backup.toString();
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
