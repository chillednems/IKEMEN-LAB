package com.chillednems.ikemenlab;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Bounded SFF artwork extraction. A preview is one sprite, not a composited game scene. */
public final class SffPreview {
    public static final class Result {
        public final int width, height;
        public final int[] argb;
        public final byte[] png;
        public final String unavailable;
        private Result(int width, int height, int[] argb, byte[] png, String unavailable) {
            this.width = width; this.height = height; this.argb = argb; this.png = png; this.unavailable = unavailable;
        }
        public boolean available() { return unavailable == null; }
    }

    private static final int MAX_FILE_BYTES = 64 * 1024 * 1024;
    private static final int MAX_PIXELS = 4_000_000;
    private SffPreview() {}
    private static Result no(String reason) { return new Result(0, 0, null, null, reason); }
    private static int u8(byte[] data, int at) { return data[at] & 255; }
    private static int u16(byte[] data, int at) { return u8(data, at) | (u8(data, at + 1) << 8); }
    private static long u32(byte[] data, int at) { return (long) u8(data, at) | ((long) u8(data, at + 1) << 8) | ((long) u8(data, at + 2) << 16) | ((long) u8(data, at + 3) << 24); }
    private static boolean range(byte[] data, long at, long length) { return at >= 0 && length >= 0 && at <= data.length && length <= data.length - at; }
    private static boolean dimensions(int width, int height) { return width > 0 && height > 0 && width <= 2048 && height <= 2048 && (long) width * height <= MAX_PIXELS; }

    public static Result extract(File file, boolean portrait, int stageGroup, int stageImage) {
        if (file == null || !file.isFile()) return no("No SFF artwork file");
        if (file.length() > MAX_FILE_BYTES) return no("SFF exceeds 64 MB preview limit");
        try { return extract(Files.readAllBytes(file.toPath()), portrait, stageGroup, stageImage); }
        catch (IOException error) { return no("Could not read SFF artwork"); }
    }

    public static Result extract(byte[] data, boolean portrait, int stageGroup, int stageImage) {
        if (data.length < 64 || data.length > MAX_FILE_BYTES) return no("Invalid or oversized SFF");
        byte[] signature = "ElecbyteSpr".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        for (int i = 0; i < signature.length; i++) if (data[i] != signature[i]) return no("Invalid SFF signature");
        return u8(data, 15) >= 2 ? v2(data, portrait, stageGroup, stageImage) : v1(data, portrait, stageGroup, stageImage);
    }

    private static int priority(int group, int image, boolean portrait, int stageGroup, int stageImage) {
        if (portrait) return group == 9000 ? (image == 1 ? 0 : image == 2 ? 1 : image == 0 ? 2 : 99) : 99;
        if (group == stageGroup && image == stageImage) return 0;
        if (group == 9000) return 1;
        return group == 0 && image == 0 ? 2 : 99;
    }

    private static Result v1(byte[] data, boolean portrait, int stageGroup, int stageImage) {
        long count = u32(data, 20), offset = u32(data, 24);
        List<long[]> candidates = new ArrayList<>();
        byte[] shared = null;
        for (int i = 0; i < Math.min(count, 5000); i++) {
            if (!range(data, offset, 32)) break;
            int at = (int) offset;
            long next = u32(data, at), length = u32(data, at + 4);
            int group = u16(data, at + 12), image = u16(data, at + 14);
            if (range(data, offset + 32, length)) {
                if (shared == null) shared = pcxPalette(data, (int) (offset + 32), (int) length);
                int rank = priority(group, image, portrait, stageGroup, stageImage);
                if (rank < 99 && u16(data, at + 16) == 0 && length > 0) candidates.add(new long[] {rank, offset + 32, length});
            }
            if (next <= offset) break;
            offset = next;
        }
        candidates.sort(Comparator.comparingLong(candidate -> candidate[0]));
        for (long[] candidate : candidates) {
            Result decoded = pcx(data, (int) candidate[1], (int) candidate[2], shared);
            if (decoded.available()) return decoded;
        }
        return no(candidates.isEmpty() ? "No portrait or stage sprite in SFF" : "Unsupported or malformed SFF v1 PCX sprite");
    }

    private static byte[] pcxPalette(byte[] data, int start, int length) {
        if (length < 769 || u8(data, start + length - 769) != 12) return null;
        return java.util.Arrays.copyOfRange(data, start + length - 768, start + length);
    }

    private static Result pcx(byte[] data, int start, int length, byte[] shared) {
        if (!range(data, start, length) || length < 129 || u8(data, start) != 10 || u8(data, start + 3) != 8 || u8(data, start + 65) != 1)
            return no("Unsupported PCX format");
        int width = u16(data, start + 8) - u16(data, start + 4) + 1;
        int height = u16(data, start + 10) - u16(data, start + 6) + 1;
        int bytesPerLine = u16(data, start + 66);
        if (!dimensions(width, height) || bytesPerLine < width || bytesPerLine > 8192) return no("PCX dimensions exceed preview limit");
        byte[] palette = pcxPalette(data, start, length);
        if (palette == null) palette = shared;
        if (palette == null) return no("PCX palette unavailable");
        int[] pixels = new int[width * height];
        int source = start + 128, end = start + length - (pcxPalette(data, start, length) == null ? 0 : 769);
        int encoding = u8(data, start + 2);
        for (int y = 0; y < height; y++) {
            int x = 0;
            while (x < bytesPerLine) {
                if (source >= end) return no("Truncated PCX sprite");
                int value = u8(data, source++), run = 1;
                if (encoding == 1 && (value & 0xc0) == 0xc0) {
                    run = value & 0x3f;
                    if (source >= end || run == 0) return no("Malformed PCX run");
                    value = u8(data, source++);
                }
                if (x + run > bytesPerLine) return no("PCX run exceeds row");
                int color = value * 3;
                int argb = (value == 0 ? 0 : 0xff000000) | (u8(palette, color) << 16) | (u8(palette, color + 1) << 8) | u8(palette, color + 2);
                for (int j = 0; j < run; j++, x++) if (x < width) pixels[y * width + x] = argb;
            }
        }
        return new Result(width, height, pixels, null, null);
    }

    private static Result v2(byte[] data, boolean portrait, int stageGroup, int stageImage) {
        long list = u32(data, 36), count = u32(data, 40), ldata = u32(data, 52), tdata = u32(data, 60);
        List<long[]> candidates = new ArrayList<>();
        for (int i = 0; i < Math.min(count, 5000); i++) {
            long at = list + (long) i * 28;
            if (!range(data, at, 28)) break;
            int index = (int) at, group = u16(data, index), image = u16(data, index + 2);
            int rank = priority(group, image, portrait, stageGroup, stageImage);
            if (rank < 99) candidates.add(new long[] {rank, at});
        }
        candidates.sort(Comparator.comparingLong(candidate -> candidate[0]));
        boolean unsupported = false;
        for (long[] candidate : candidates) {
            int at = (int) candidate[1], width = u16(data, at + 4), height = u16(data, at + 6);
            int linked = u16(data, at + 12);
            if (!dimensions(width, height) || (linked != 0 && linked != 0xffff)) continue;
            int format = u8(data, at + 14);
            if (format != 11 && format != 12) { unsupported = true; continue; }
            long base = (u16(data, at + 26) & 1) == 0 ? ldata : tdata;
            long start = base + u32(data, at + 16), length = u32(data, at + 20);
            if (!range(data, start, length) || length <= 12) continue;
            int pngStart = (int) start + 4;
            byte[] pngSignature = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
            boolean valid = true;
            for (int i = 0; i < pngSignature.length; i++) if (data[pngStart + i] != pngSignature[i]) valid = false;
            if (valid) return new Result(width, height, null, java.util.Arrays.copyOfRange(data, pngStart, (int) (start + length)), null);
        }
        return no(unsupported ? "SFF v2 compression format is not supported for preview" : "No decodable PNG sprite in SFF v2");
    }
}
