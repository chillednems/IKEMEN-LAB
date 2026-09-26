package com.chillednems.ikemenlab;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Random-access, bounded SFF sprite reader for library previews. */
public final class PreviewSff implements Closeable {
    public static final int MAX_PIXELS = 4_000_000;
    private static final int MAX_ENTRIES = 10_000;
    private static final int MAX_COMPRESSED = 16 * 1024 * 1024;
    private final SeekableByteChannel file;
    private final long size;
    private final int version;
    private final boolean legacyPaletteAlpha;
    private final long ldata, tdata, paletteTable;
    private final int paletteCount;
    private final List<Entry> entries = new ArrayList<>();
    private final Map<Long, Integer> index = new HashMap<>();
    private final Map<Integer, int[]> paletteCache = new HashMap<>();

    public static final class Sprite {
        public final int group, image, width, height, axisX, axisY;
        public final int[] argb;
        Sprite(Entry entry, int[] argb) {
            group = entry.group; image = entry.image; width = entry.width; height = entry.height;
            axisX = entry.x; axisY = entry.y; this.argb = argb;
        }
    }

    private static final class Entry {
        int group, image, width, height, x, y, link, format, palette;
        long start, length;
        boolean samePalette;
    }

    public PreviewSff(File path) throws IOException { this(LibraryFiles.local(path)); }

    public PreviewSff(LibraryFiles.Node path) throws IOException {
        file = path.openSeekable();
        try {
            size = file.size();
            byte[] header = read(0, 64);
            if (!new String(header, 0, 11, java.nio.charset.StandardCharsets.US_ASCII).equals("ElecbyteSpr"))
                throw new IOException("Invalid SFF signature");
            version = header[15] & 255;
            legacyPaletteAlpha = header[13] == 0;
            if (version >= 2) {
                long list = u32(header, 36), count = u32(header, 40);
                paletteTable = u32(header, 44); paletteCount = checkedCount(u32(header, 48));
                ldata = u32(header, 52); tdata = u32(header, 60);
                int n = checkedCount(count);
                if (!within(list, (long) n * 28)) throw new IOException("Invalid SFF sprite table");
                if (!within(paletteTable, (long) paletteCount * 16)) throw new IOException("Invalid SFF palette table");
                byte[] table = read(list, n * 28);
                for (int i = 0; i < n; i++) {
                    int at = i * 28;
                    Entry e = new Entry();
                    e.group = u16(table, at); e.image = u16(table, at + 2);
                    e.width = u16(table, at + 4); e.height = u16(table, at + 6);
                    e.x = s16(table, at + 8); e.y = s16(table, at + 10);
                    e.link = u16(table, at + 12); e.format = table[at + 14] & 255;
                    e.start = (((u16(table, at + 26) & 1) == 0) ? ldata : tdata) + u32(table, at + 16);
                    e.length = u32(table, at + 20); e.palette = u16(table, at + 24);
                    add(e);
                }
            } else {
                paletteTable = ldata = tdata = 0; paletteCount = 0;
                int n = checkedCount(u32(header, 20)); long at = u32(header, 24);
                Set<Long> visited = new HashSet<>();
                for (int i = 0; i < n && visited.add(at); i++) {
                    byte[] h = read(at, 32);
                    Entry e = new Entry();
                    long next = u32(h, 0); e.length = u32(h, 4); e.start = at + 32;
                    e.x = s16(h, 8); e.y = s16(h, 10);
                    e.group = u16(h, 12); e.image = u16(h, 14); e.link = u16(h, 16);
                    e.samePalette = h[18] != 0; e.format = 1;
                    add(e);
                    if (next == 0 || next <= at) break;
                    at = next;
                }
            }
        } catch (IOException | RuntimeException error) {
            file.close();
            throw error;
        }
    }

    public boolean has(int group, int image) { return index.containsKey(key(group, image)); }
    public Sprite sprite(int group, int image) throws IOException {
        Integer at = index.get(key(group, image));
        if (at == null) throw new IOException("Sprite " + group + "," + image + " is missing");
        Entry requested = entries.get(at), source = requested;
        Set<Integer> visited = new HashSet<>();
        while (source.length == 0) {
            if (!visited.add(at) || source.link >= entries.size()) throw new IOException("Invalid SFF sprite link");
            at = source.link; source = entries.get(at);
        }
        if (version >= 2 && !dimensions(source.width, source.height)) throw new IOException("Sprite dimensions exceed preview limit");
        if (!within(source.start, source.length) || source.length > MAX_COMPRESSED) throw new IOException("Sprite data exceeds preview limit");
        byte[] bytes = read(source.start, (int) source.length);
        int[] pixels;
        if (version < 2) pixels = pcx(source, at, bytes);
        else {
            if (bytes.length < 4) throw new IOException("Truncated SFF sprite");
            switch (source.format) {
                case 10: pixels = PreviewPng.decode(bytes, 4, bytes.length - 4, palette(source.palette), true, source.width, source.height); break;
                case 11: case 12: pixels = PreviewPng.decode(bytes, 4, bytes.length - 4, null, false, source.width, source.height); break;
                case 4: pixels = indexed(lz5(bytes, source.width * source.height), palette(source.palette)); break;
                case 2: pixels = indexed(rle8(bytes, source.width * source.height), palette(source.palette)); break;
                default: throw new IOException("Unsupported SFF sprite format " + source.format);
            }
        }
        if (pixels.length != source.width * source.height) throw new IOException("Sprite pixels do not match dimensions");
        // A linked header supplies identity and axes; its target supplies size and pixels.
        requested.width = source.width; requested.height = source.height;
        return new Sprite(requested, pixels);
    }

    private int[] palette(int id) throws IOException {
        int[] cached = paletteCache.get(id);
        if (cached != null) return cached;
        if (id < 0 || id >= paletteCount) throw new IOException("Invalid SFF palette index");
        Set<Integer> visited = new HashSet<>();
        int current = id;
        byte[] h;
        do {
            if (!visited.add(current)) throw new IOException("Cyclic SFF palette link");
            h = read(paletteTable + (long) current * 16, 16);
            if (u32(h, 12) != 0) break;
            current = u16(h, 6);
            if (current >= paletteCount) throw new IOException("Invalid SFF palette link");
        } while (true);
        long length = u32(h, 12);
        if (length < 4 || length > 1024 || (length & 3) != 0) throw new IOException("Invalid SFF palette size");
        byte[] raw = read(ldata + u32(h, 8), (int) length);
        int[] colors = new int[256];
        for (int i = 0; i < raw.length / 4; i++) {
            int at = i * 4;
            int alpha = legacyPaletteAlpha ? (i == 0 ? 0 : 255) : raw[at + 3] & 255;
            colors[i] = alpha << 24 | (raw[at] & 255) << 16 | (raw[at + 1] & 255) << 8 | (raw[at + 2] & 255);
        }
        paletteCache.put(id, colors);
        return colors;
    }

    private int[] pcx(Entry e, int sourceIndex, byte[] bytes) throws IOException {
        if (bytes.length < 128 || bytes[0] != 10 || bytes[3] != 8 || bytes[65] != 1) throw new IOException("Unsupported PCX");
        int w = u16(bytes, 8) - u16(bytes, 4) + 1, h = u16(bytes, 10) - u16(bytes, 6) + 1;
        if (!dimensions(w, h)) throw new IOException("PCX dimensions exceed preview limit");
        e.width = w; e.height = h;
        int stride = u16(bytes, 66);
        if (stride < w || stride > 8192) throw new IOException("Invalid PCX stride");
        byte[] pal = null;
        if (e.samePalette) {
            // Shared palettes follow the preceding sprite, which can itself be shared.
            for (int i = sourceIndex - 1; i >= 0; i--) {
                Entry previous = entries.get(i);
                if (previous.samePalette || previous.length < 769 || previous.length > MAX_COMPRESSED ||
                    !within(previous.start, previous.length)) continue;
                byte[] earlier = read(previous.start, (int) previous.length);
                if (earlier[earlier.length - 769] == 12) {
                    pal = new byte[768];
                    System.arraycopy(earlier, earlier.length - 768, pal, 0, 768);
                    break;
                }
            }
        } else if (bytes.length >= 769 && bytes[bytes.length - 769] == 12) {
            pal = new byte[768]; System.arraycopy(bytes, bytes.length - 768, pal, 0, 768);
        }
        if (pal == null) throw new IOException("PCX palette unavailable");
        int[] out = new int[w * h]; int p = 128, end = bytes.length - (bytes.length >= 769 && bytes[bytes.length - 769] == 12 ? 769 : 0);
        for (int y = 0; y < h; y++) for (int x = 0; x < stride;) {
            if (p >= end) throw new IOException("Truncated PCX");
            int value = bytes[p++] & 255, run = 1;
            if ((bytes[2] & 255) == 1 && (value & 0xc0) == 0xc0) {
                run = value & 63;
                if (run == 0 || p >= end) throw new IOException("Malformed PCX run");
                value = bytes[p++] & 255;
            }
            if (x + run > stride) throw new IOException("PCX run exceeds row");
            int k = value * 3, color = (value == 0 ? 0 : 0xff000000) | (pal[k] & 255) << 16 | (pal[k + 1] & 255) << 8 | (pal[k + 2] & 255);
            for (int j = 0; j < run; j++, x++) if (x < w) out[y * w + x] = color;
        }
        return out;
    }

    private static int[] indexed(byte[] indices, int[] palette) {
        int[] out = new int[indices.length];
        for (int i = 0; i < indices.length; i++) out[i] = palette[indices[i] & 255];
        return out;
    }

    private static byte[] rle8(byte[] data, int pixels) throws IOException {
        byte[] out = new byte[pixels]; int s = 4, d = 0;
        while (d < pixels) {
            if (s >= data.length) throw new IOException("Truncated RLE8");
            int v = data[s++] & 255, count = 1;
            if ((v & 0xc0) == 0x40) {
                count = v & 63;
                if (s >= data.length || count == 0) throw new IOException("Invalid RLE8");
                v = data[s++] & 255;
            }
            if (count > pixels - d) throw new IOException("RLE8 exceeds sprite");
            java.util.Arrays.fill(out, d, d + count, (byte) v); d += count;
        }
        return out;
    }

    private static byte[] lz5(byte[] data, int pixels) throws IOException {
        byte[] out = new byte[pixels]; int s = 4, d = 0, flags = 0, bits = 8, recent = 0, recentBits = 0;
        while (d < pixels) {
            if (bits == 8) { if (s >= data.length) throw new IOException("Truncated LZ5"); flags = data[s++] & 255; bits = 0; }
            if (s >= data.length) throw new IOException("Truncated LZ5");
            int v = data[s++] & 255;
            if ((flags & (1 << bits)) == 0) {
                int count;
                if ((v & 0xe0) == 0) { if (s >= data.length) throw new IOException("Truncated LZ5 run"); count = (data[s++] & 255) + 8; }
                else { count = v >>> 5; v &= 31; }
                if (count <= 0 || count > pixels - d) throw new IOException("LZ5 run exceeds sprite");
                java.util.Arrays.fill(out, d, d + count, (byte) v); d += count;
            } else {
                int distance, count;
                if ((v & 63) == 0) {
                    if (s + 1 >= data.length) throw new IOException("Truncated LZ5 copy");
                    distance = ((v << 2) | (data[s++] & 255)) + 1;
                    count = (data[s++] & 255) + 3;
                } else {
                    recent |= (v & 0xc0) >>> recentBits; recentBits += 2;
                    count = (v & 63) + 1;
                    if (recentBits < 8) { if (s >= data.length) throw new IOException("Truncated LZ5 copy"); distance = (data[s++] & 255) + 1; }
                    else { distance = recent + 1; recent = 0; recentBits = 0; }
                }
                if (distance <= 0 || distance > d || count > pixels - d) throw new IOException("Invalid LZ5 backreference");
                for (int i = 0; i < count; i++) { out[d] = out[d - distance]; d++; }
            }
            bits++;
        }
        return out;
    }

    private void add(Entry e) { index.putIfAbsent(key(e.group, e.image), entries.size()); entries.add(e); }
    private static long key(int group, int image) { return ((long) group << 32) | (image & 0xffffffffL); }
    private static boolean dimensions(int w, int h) { return w > 0 && h > 0 && w <= 4096 && h <= 4096 && (long) w * h <= MAX_PIXELS; }
    private static int checkedCount(long n) throws IOException { if (n > MAX_ENTRIES) throw new IOException("SFF entry count exceeds preview limit"); return (int) n; }
    private boolean within(long at, long length) { return at >= 0 && length >= 0 && at <= size && length <= size - at; }
    private byte[] read(long at, int length) throws IOException {
        if (!within(at, length)) throw new IOException("SFF offset out of bounds");
        byte[] out = new byte[length];
        file.position(at);
        ByteBuffer destination = ByteBuffer.wrap(out);
        while (destination.hasRemaining()) {
            int read = file.read(destination);
            if (read < 0) throw new IOException("Truncated SFF data");
            if (read == 0) throw new IOException("SFF provider stopped reading");
        }
        return out;
    }
    private static int u16(byte[] d, int a) { return (d[a] & 255) | (d[a + 1] & 255) << 8; }
    private static int s16(byte[] d, int a) { return (short) u16(d, a); }
    private static long u32(byte[] d, int a) { return ((long) u16(d, a) | (long) u16(d, a + 2) << 16) & 0xffffffffL; }
    @Override public void close() throws IOException { file.close(); }
}
