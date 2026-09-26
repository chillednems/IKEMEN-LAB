package com.chillednems.ikemenlab;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.InflaterInputStream;

/** Small bounded PNG decoder for SFF preview sprites (8-bit, noninterlaced). */
final class PreviewPng {
    private static final byte[] SIGNATURE = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
    private PreviewPng() {}

    static int[] decode(byte[] data, int start, int length, int[] sffPalette,
                        boolean indexedSff, int expectedWidth, int expectedHeight) throws IOException {
        if (length < 45 || start < 0 || start > data.length - length) throw new IOException("Truncated PNG");
        for (int i = 0; i < 8; i++) if (data[start + i] != SIGNATURE[i]) throw new IOException("Invalid PNG signature");
        int end = start + length, at = start + 8, width = 0, height = 0, type = -1, channels = 0;
        int[] palette = new int[256];
        byte[] transparency = null;
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        boolean hasEnd = false;
        while (at <= end - 12) {
            long n = be32(data, at);
            if (n > end - at - 12) throw new IOException("PNG chunk out of bounds");
            int chunk = at + 8, size = (int) n;
            String kind = new String(data, at + 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
            if ("IHDR".equals(kind)) {
                if (size != 13 || width != 0) throw new IOException("Invalid PNG header");
                width = (int) be32(data, chunk); height = (int) be32(data, chunk + 4);
                if (width != expectedWidth || height != expectedHeight ||
                    width < 1 || height < 1 || (long) width * height > PreviewSff.MAX_PIXELS)
                    throw new IOException("PNG dimensions do not match SFF");
                if ((data[chunk + 8] & 255) != 8 || data[chunk + 12] != 0 ||
                    data[chunk + 10] != 0 || data[chunk + 11] != 0)
                    throw new IOException("Unsupported PNG depth or interlace");
                type = data[chunk + 9] & 255;
                channels = type == 2 ? 3 : type == 3 ? 1 : type == 6 ? 4 : 0;
                if (channels == 0) throw new IOException("Unsupported PNG color type");
            } else if ("PLTE".equals(kind)) {
                if (size == 0 || size > 768 || size % 3 != 0) throw new IOException("Invalid PNG palette");
                for (int i = 0; i < size / 3; i++) {
                    int p = chunk + i * 3;
                    palette[i] = 0xff000000 | (data[p] & 255) << 16 | (data[p + 1] & 255) << 8 | (data[p + 2] & 255);
                }
            } else if ("tRNS".equals(kind)) {
                if (size > 256) throw new IOException("Invalid PNG transparency");
                transparency = new byte[size]; System.arraycopy(data, chunk, transparency, 0, size);
            } else if ("IDAT".equals(kind)) {
                if (width == 0 || compressed.size() + size > 16 * 1024 * 1024) throw new IOException("PNG data exceeds preview limit");
                compressed.write(data, chunk, size);
            } else if ("IEND".equals(kind)) { hasEnd = true; break; }
            at = chunk + size + 4;
        }
        if (!hasEnd || width == 0 || compressed.size() == 0) throw new IOException("Incomplete PNG");
        if (type == 3 && sffPalette != null && indexedSff) palette = sffPalette;
        else if (type == 3 && transparency != null) for (int i = 0; i < transparency.length; i++)
            palette[i] = (palette[i] & 0xffffff) | (transparency[i] & 255) << 24;
        int stride = width * channels;
        int[] pixels = new int[width * height];
        try (InflaterInputStream stream = new InflaterInputStream(new ByteArrayInputStream(compressed.toByteArray()))) {
            byte[] previous = new byte[stride], row = new byte[stride];
            for (int y = 0; y < height; y++) {
                int filter = stream.read();
                if (filter < 0 || filter > 4) throw new IOException("Invalid PNG row filter");
                readFully(stream, row);
                for (int i = 0; i < stride; i++) {
                    int left = i >= channels ? row[i - channels] & 255 : 0;
                    int up = previous[i] & 255;
                    int upperLeft = i >= channels ? previous[i - channels] & 255 : 0;
                    int predictor = filter == 1 ? left : filter == 2 ? up :
                            filter == 3 ? (left + up) / 2 : filter == 4 ? paeth(left, up, upperLeft) : 0;
                    row[i] = (byte) ((row[i] & 255) + predictor);
                }
                for (int x = 0; x < width; x++) {
                    int p = x * channels, color;
                    if (type == 3) color = palette[row[p] & 255];
                    else {
                        int alpha = type == 6 ? row[p + 3] & 255 : 255;
                        color = alpha << 24 | (row[p] & 255) << 16 | (row[p + 1] & 255) << 8 | (row[p + 2] & 255);
                    }
                    pixels[y * width + x] = color;
                }
                byte[] swap = previous; previous = row; row = swap;
            }
        } catch (java.util.zip.ZipException invalid) {
            throw new IOException("Invalid PNG compression", invalid);
        }
        return pixels;
    }

    private static int paeth(int a, int b, int c) {
        int p = a + b - c, da = Math.abs(p - a), db = Math.abs(p - b), dc = Math.abs(p - c);
        return da <= db && da <= dc ? a : db <= dc ? b : c;
    }
    private static void readFully(java.io.InputStream in, byte[] row) throws IOException {
        for (int at = 0; at < row.length;) {
            int n = in.read(row, at, row.length - at);
            if (n <= 0) throw new IOException("Truncated PNG rows");
            at += n;
        }
    }
    private static long be32(byte[] d, int a) {
        return ((long) (d[a] & 255) << 24) | (long) (d[a + 1] & 255) << 16 |
                (long) (d[a + 2] & 255) << 8 | (d[a + 3] & 255);
    }
}
