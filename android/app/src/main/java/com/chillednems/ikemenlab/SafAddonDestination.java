package com.chillednems.ikemenlab;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** Narrow SAF writer for one reviewed add-only folder under chars or stages. */
final class SafAddonDestination implements AddonInstallTransaction.Destination {
    private static final String DIRECTORY = DocumentsContract.Document.MIME_TYPE_DIR;
    private final ContentResolver resolver;
    private final Uri tree;

    SafAddonDestination(ContentResolver resolver, Uri tree) throws IOException {
        if (tree == null || !DocumentsContract.isTreeUri(tree)) throw new IOException("Select a source folder");
        SafSelectTarget.requireGrant(resolver, tree, true);
        this.resolver = resolver; this.tree = tree;
    }

    @Override public String identity() { return tree.toString(); }

    @Override public Node target(String kind) throws IOException {
        if (!"chars".equals(kind) && !"stages".equals(kind)) throw new IOException("Unsupported import destination");
        String rootId = DocumentsContract.getTreeDocumentId(tree);
        Node root = query(rootId);
        if (!root.directory) throw new IOException("Selected source is not a folder");
        Node found = null;
        for (Node child : children(root)) if (child.name.equalsIgnoreCase(kind)) {
            if (found != null) throw new IOException("Ambiguous destination folder");
            found = child;
        }
        if (found == null || !found.directory || !found.name.equals(kind))
            throw new IOException("Source has no exact " + kind + " folder");
        return found;
    }

    @Override public List<Node> children(Node folder) throws IOException {
        if (folder == null || !folder.directory) throw new IOException("Destination is not a folder");
        List<Node> result = new ArrayList<>();
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, folder.id);
        String[] columns = {DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE};
        try (Cursor cursor = resolver.query(children, columns, null, null, null)) {
            if (cursor == null) throw new IOException("Provider did not list import destination");
            while (cursor.moveToNext()) {
                if (result.size() >= 20_000) throw new IOException("Import destination has too many entries");
                String id = cursor.getString(0), name = cursor.getString(1), mime = cursor.getString(2);
                if (id == null || name == null || name.isEmpty())
                    throw new IOException("Provider returned an invalid destination child");
                result.add(new Node(id, name, DIRECTORY.equals(mime)));
            }
        } catch (SecurityException denied) { throw new IOException("Source folder access denied", denied); }
        return result;
    }

    @Override public void requireCapabilities(Node folder) throws IOException {
        SafSelectTarget.requireGrant(resolver, tree, true);
        int flags = flags(folder);
        if ((flags & DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE) == 0
                || (flags & DocumentsContract.Document.FLAG_SUPPORTS_RENAME) == 0
                || (flags & DocumentsContract.Document.FLAG_SUPPORTS_DELETE) == 0)
            throw new IOException("This document provider does not advertise add, rename, and delete support");
    }

    @Override public Node create(Node parent, String name, boolean directory) throws IOException {
        if (!parent.directory) throw new IOException("Cannot add beneath a file");
        AddonPackage.validSegment(name);
        try {
            Uri created = DocumentsContract.createDocument(resolver, uri(parent.id),
                    directory ? DIRECTORY : "application/octet-stream", name);
            if (created == null) throw new IOException("Provider did not create add-on entry");
            String id = DocumentsContract.getDocumentId(created);
            try { return query(id); }
            catch (IOException unavailable) { return new Node(id, "", directory); }
        } catch (SecurityException denied) { throw new IOException("Source write access denied", denied); }
        catch (RuntimeException failure) { throw new IOException("Provider could not create add-on entry", failure); }
    }

    @Override public void write(Node file, InputStream input, long bytes) throws IOException {
        if (file.directory) throw new IOException("Cannot write add-on bytes to a folder");
        if ((flags(file) & DocumentsContract.Document.FLAG_SUPPORTS_WRITE) == 0)
            throw new IOException("Provider does not support writing this add-on file");
        try (OutputStream output = resolver.openOutputStream(uri(file.id), "rwt")) {
            if (output == null) throw new IOException("Provider did not open add-on file for writing");
            byte[] buffer = new byte[64 * 1024];
            long written = 0;
            int length;
            while ((length = input.read(buffer)) != -1) {
                written += length;
                if (written > bytes) throw new IOException("Staged add-on file changed during import");
                output.write(buffer, 0, length);
            }
            if (written != bytes) throw new IOException("Staged add-on file length changed during import");
            output.flush();
        } catch (SecurityException denied) { throw new IOException("Source write access denied", denied); }
    }

    @Override public InputStream read(Node file) throws IOException {
        if (file.directory) throw new IOException("Cannot read a folder as an add-on file");
        try {
            InputStream input = resolver.openInputStream(uri(file.id));
            if (input == null) throw new IOException("Provider did not open add-on readback");
            return input;
        } catch (SecurityException denied) { throw new IOException("Source readback denied", denied); }
    }

    @Override public Node rename(Node folder, String name) throws IOException {
        if (!folder.directory || (flags(folder) & DocumentsContract.Document.FLAG_SUPPORTS_RENAME) == 0)
            throw new IOException("Provider cannot rename the pending add-on folder");
        try {
            Uri renamed = DocumentsContract.renameDocument(resolver, uri(folder.id), name);
            if (renamed == null) throw new IOException("Provider did not finish add-on rename");
            String id = DocumentsContract.getDocumentId(renamed);
            try { return query(id); }
            catch (IOException unavailable) { return new Node(id, "", true); }
        } catch (SecurityException denied) { throw new IOException("Source rename access denied", denied); }
        catch (RuntimeException failure) { throw new IOException("Provider could not rename pending add-on", failure); }
    }

    @Override public void delete(Node node) throws IOException {
        if ((flags(node) & DocumentsContract.Document.FLAG_SUPPORTS_DELETE) == 0)
            throw new IOException("Provider cannot remove the pending add-on folder");
        try {
            if (!DocumentsContract.deleteDocument(resolver, uri(node.id)))
                throw new IOException("Provider did not remove pending add-on folder");
        } catch (SecurityException denied) { throw new IOException("Source delete access denied", denied); }
        catch (RuntimeException failure) { throw new IOException("Provider could not remove pending add-on", failure); }
    }

    private Uri uri(String id) { return DocumentsContract.buildDocumentUriUsingTree(tree, id); }

    private Node query(String id) throws IOException {
        Uri document = uri(id);
        String[] columns = {DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE};
        try (Cursor cursor = resolver.query(document, columns, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) throw new IOException("Provider did not identify add-on document");
            String name = cursor.getString(0), mime = cursor.getString(1);
            if (name == null || name.isEmpty()) throw new IOException("Provider returned an unnamed document");
            return new Node(id, name, DIRECTORY.equals(mime));
        } catch (SecurityException denied) { throw new IOException("Source folder access denied", denied); }
    }

    private int flags(Node node) throws IOException {
        try (Cursor cursor = resolver.query(uri(node.id),
                new String[]{DocumentsContract.Document.COLUMN_FLAGS}, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) throw new IOException("Provider did not report document capabilities");
            return cursor.getInt(0);
        } catch (SecurityException denied) { throw new IOException("Source folder access denied", denied); }
    }
}
