package com.chillednems.ikemenlab;

import java.io.IOException;

/** Bounds storage use and rejects provider names that could escape the staging directory. */
public final class ImportLimits {
    public static final int MAX_DEPTH = 20;
    public static final int MAX_ENTRIES = 20_000;
    public static final long MAX_BYTES = 8L * 1024 * 1024 * 1024;
    private int entries;
    private long bytes;

    public void enter(String name, int depth) throws IOException {
        if (name == null || name.trim().isEmpty() || name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\") || name.indexOf('\0') >= 0)
            throw new IOException("Unsafe document name");
        if (depth > MAX_DEPTH || ++entries > MAX_ENTRIES) throw new IOException("Import limit exceeded");
    }

    public void addBytes(int count) throws IOException {
        if (count < 0 || bytes > MAX_BYTES - count) throw new IOException("Library exceeds 8 GB import limit");
        bytes += count;
    }
}
