package com.chillednems.ikemenlab;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Copies a picked SAF tree into an isolated staging folder before publishing it. */
public final class SafImporter {
    private SafImporter() {}

    public static File importTree(ContentResolver resolver, Uri tree, File librariesRoot) throws IOException {
        if (!DocumentsContract.isTreeUri(tree)) throw new IOException("Choose a folder");
        if (!librariesRoot.isDirectory() && !librariesRoot.mkdirs()) throw new IOException("Could not create library storage");
        String id = UUID.randomUUID().toString();
        File staging = new File(librariesRoot, ".staging-" + id);
        File destination = new File(librariesRoot, id);
        if (!staging.mkdir()) throw new IOException("Could not stage import");
        try {
            ImportLimits limits = new ImportLimits();
            String rootId = DocumentsContract.getTreeDocumentId(tree);
            Set<String> visited = new HashSet<>();
            visited.add(rootId);
            copyChildren(resolver, tree, rootId, staging, limits, visited, 0);
            LibraryScanner.scan(staging);
            Files.move(staging.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE);
            return destination;
        } catch (Exception failure) {
            deleteRecursively(staging);
            if (failure instanceof IOException) throw (IOException) failure;
            throw new IOException("Could not import selected folder", failure);
        }
    }

    private static void copyChildren(ContentResolver resolver, Uri tree, String parentId, File target,
                                     ImportLimits limits, Set<String> visited, int depth) throws IOException {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        String[] columns = { DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE };
        Set<String> names = new HashSet<>();
        try (Cursor cursor = resolver.query(children, columns, null, null, null)) {
            if (cursor == null) throw new IOException("Folder provider did not return children");
            while (cursor.moveToNext()) {
                String documentId = cursor.getString(0);
                String name = cursor.getString(1);
                String mime = cursor.getString(2);
                limits.enter(name, depth);
                if (!names.add(name.toLowerCase(Locale.ROOT))) throw new IOException("Duplicate document name: " + name);
                if (!visited.add(documentId)) throw new IOException("Folder contains a cycle");
                File output = new File(target, name);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    if (!output.mkdir()) throw new IOException("Could not create " + name);
                    copyChildren(resolver, tree, documentId, output, limits, visited, depth + 1);
                } else {
                    Uri document = DocumentsContract.buildDocumentUriUsingTree(tree, documentId);
                    try (InputStream input = resolver.openInputStream(document);
                         OutputStream stream = Files.newOutputStream(output.toPath())) {
                        if (input == null) throw new IOException("Could not open " + name);
                        byte[] buffer = new byte[65536];
                        int read;
                        while ((read = input.read(buffer)) != -1) {
                            limits.addBytes(read);
                            stream.write(buffer, 0, read);
                        }
                    }
                }
            }
        } catch (SecurityException error) { throw new IOException("Folder access was denied", error); }
    }

    private static void deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        file.delete();
    }
}
