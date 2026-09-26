package com.chillednems.ikemenlab;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Persisted Android document tree used directly for library metadata and artwork. */
final class SafLibraryFiles {
    private static final int MAX_ENTRIES = 20_000;
    private static final int MAX_DEPTH = 20;
    private static final String DIR = DocumentsContract.Document.MIME_TYPE_DIR;
    private final ContentResolver resolver;
    private final Uri tree;
    private int visited;

    SafLibraryFiles(ContentResolver resolver, Uri tree) throws IOException {
        SafSelectTarget.requireGrant(resolver, tree, false);
        this.resolver = resolver;
        this.tree = tree;
    }

    LibraryFiles.Node root() throws IOException {
        try {
            String id = DocumentsContract.getTreeDocumentId(tree);
            Uri document = DocumentsContract.buildDocumentUriUsingTree(tree, id);
            String name = "Selected folder";
            try (Cursor cursor = resolver.query(document,
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                            DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
                if (cursor == null || !cursor.moveToFirst()) throw new IOException("Selected folder is unavailable");
                if (!DIR.equals(cursor.getString(1))) throw new IOException("Selected item is not a folder");
                if (cursor.getString(0) != null && !cursor.getString(0).isEmpty()) name = cursor.getString(0);
            }
            return new Entry(id, document, name, true, -1, null, 0);
        } catch (IOException unavailable) { throw unavailable;
        } catch (RuntimeException invalid) { throw new IOException("Selected folder is unavailable", invalid); }
    }

    private final class Entry implements LibraryFiles.Node {
        private final String id, name;
        private final Uri uri;
        private final boolean directory;
        private final long size;
        private final Entry parent;
        private final int depth;
        private List<LibraryFiles.Node> cachedChildren;

        Entry(String id, Uri uri, String name, boolean directory, long size, Entry parent, int depth) {
            this.id = id; this.uri = uri; this.name = name; this.directory = directory;
            this.size = size; this.parent = parent; this.depth = depth;
        }
        @Override public String name() { return name; }
        @Override public String displayPath() {
            return parent == null ? name : parent.displayPath() + "/" + name;
        }
        @Override public boolean directory() { return directory; }
        @Override public long size() { return size; }
        @Override public LibraryFiles.Node parent() { return parent; }
        @Override public List<LibraryFiles.Node> children() throws IOException {
            if (!directory) return Collections.emptyList();
            if (cachedChildren != null) return cachedChildren;
            if (depth >= MAX_DEPTH) throw new IOException("Library folder nesting exceeds 20 levels");
            List<LibraryFiles.Node> result = new ArrayList<>();
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id);
            String[] columns = {DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE};
            try (Cursor cursor = resolver.query(children, columns, null, null, null)) {
                if (cursor == null) throw new IOException("Folder provider did not return contents");
                while (cursor.moveToNext()) {
                    if (++visited > MAX_ENTRIES) throw new IOException("Library exceeds 20,000 listed entries");
                    String childId = cursor.getString(0), childName = cursor.getString(1), mime = cursor.getString(2);
                    long length = cursor.isNull(3) ? -1 : cursor.getLong(3);
                    if (childId == null || childName == null || childName.isEmpty())
                        throw new IOException("Folder provider returned an invalid entry");
                    result.add(new Entry(childId, DocumentsContract.buildDocumentUriUsingTree(tree, childId),
                            childName, DIR.equals(mime), length, this, depth + 1));
                }
            } catch (SecurityException denied) { throw new IOException("Library folder permission was revoked; reconnect it", denied); }
            cachedChildren = Collections.unmodifiableList(result);
            return cachedChildren;
        }
        @Override public InputStream open() throws IOException {
            if (directory) throw new IOException("Cannot read a folder");
            try {
                InputStream input = resolver.openInputStream(uri);
                if (input == null) throw new IOException("Document provider did not open " + name);
                return input;
            } catch (SecurityException denied) { throw new IOException("Library folder permission was revoked; reconnect it", denied); }
        }
        @Override public SeekableByteChannel openSeekable() throws IOException {
            if (directory) throw new IOException("Cannot read a folder");
            ParcelFileDescriptor descriptor;
            try { descriptor = resolver.openFileDescriptor(uri, "r"); }
            catch (SecurityException denied) { throw new IOException("Library folder permission was revoked; reconnect it", denied); }
            if (descriptor == null) throw new IOException("Document provider did not open artwork");
            ParcelFileDescriptor.AutoCloseInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
            try {
                java.nio.channels.FileChannel channel = input.getChannel();
                channel.position(0);
                channel.size();
                return new SeekableByteChannel() {
                    @Override public int read(ByteBuffer destination) throws IOException { return channel.read(destination); }
                    @Override public int write(ByteBuffer source) throws IOException { throw new IOException("Read-only artwork"); }
                    @Override public long position() throws IOException { return channel.position(); }
                    @Override public SeekableByteChannel position(long next) throws IOException { channel.position(next); return this; }
                    @Override public long size() throws IOException { return channel.size(); }
                    @Override public SeekableByteChannel truncate(long next) throws IOException { throw new IOException("Read-only artwork"); }
                    @Override public boolean isOpen() { return channel.isOpen(); }
                    @Override public void close() throws IOException { input.close(); }
                };
            } catch (IOException nonSeekable) {
                input.close();
                throw new IOException("This folder provider does not support seekable artwork previews", nonSeekable);
            }
        }
    }
}
