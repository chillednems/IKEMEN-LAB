package com.chillednems.ikemenlab;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Read-only paths inside one selected library. No content is imported to app storage. */
final class LibraryFiles {
    interface Node {
        String name();
        String displayPath();
        boolean directory();
        long size(); // -1 when the provider does not report a length.
        List<Node> children() throws IOException;
        InputStream open() throws IOException;
        SeekableByteChannel openSeekable() throws IOException;
        Node parent();
    }

    private LibraryFiles() {}

    static Node local(File root) { return new LocalNode(root, null); }

    static Node child(Node parent, String name) throws IOException {
        if (parent == null || !parent.directory()) return null;
        Node found = null;
        for (Node child : parent.children()) if (child.name().equalsIgnoreCase(name)) {
            if (found != null) throw new IOException("Ambiguous case-insensitive name: " + name);
            found = child;
        }
        return found;
    }

    static Node resolve(Node base, String path) throws IOException {
        if (base == null || path == null || path.isEmpty() || path.startsWith("/")
                || path.matches("(?i)^[a-z]:.*")) throw new IOException("Invalid relative asset path");
        Node current = base;
        for (String segment : path.replace('\\', '/').split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals(".."))
                throw new IOException("Invalid relative asset path");
            current = child(current, segment);
            if (current == null) return null;
        }
        return current;
    }

    static byte[] readLimited(Node node, int maximum) throws IOException {
        if (node == null || node.directory()) throw new IOException("File is missing");
        if (node.size() > maximum) throw new IOException("File exceeds " + maximum + " byte limit: " + node.name());
        try (InputStream input = node.open(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() > maximum - count) throw new IOException("File exceeds " + maximum + " byte limit: " + node.name());
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    static List<Node> sorted(Node folder) throws IOException {
        if (folder == null || !folder.directory()) return Collections.emptyList();
        List<Node> result = new ArrayList<>(folder.children());
        result.sort(Comparator.comparing(Node::name, String.CASE_INSENSITIVE_ORDER));
        for (int i = 1; i < result.size(); i++)
            if (result.get(i - 1).name().equalsIgnoreCase(result.get(i).name()))
                throw new IOException("Ambiguous case-insensitive name: " + result.get(i).name());
        return result;
    }

    private static final class LocalNode implements Node {
        final File file;
        final Node parent;
        LocalNode(File file, Node parent) { this.file = file; this.parent = parent; }
        @Override public String name() { return file.getName(); }
        @Override public String displayPath() { return file.getAbsolutePath(); }
        @Override public boolean directory() { return file.isDirectory(); }
        @Override public long size() { return file.isFile() ? file.length() : -1; }
        @Override public Node parent() { return parent; }
        @Override public List<Node> children() throws IOException {
            File[] files = file.listFiles();
            if (files == null) {
                if (file.isDirectory()) throw new IOException("Cannot list folder: " + file.getName());
                return Collections.emptyList();
            }
            List<Node> result = new ArrayList<>(files.length);
            for (File child : files) result.add(new LocalNode(child, this));
            return result;
        }
        @Override public InputStream open() throws IOException { return Files.newInputStream(file.toPath()); }
        @Override public SeekableByteChannel openSeekable() throws IOException {
            return Files.newByteChannel(file.toPath(), StandardOpenOption.READ);
        }
    }
}
