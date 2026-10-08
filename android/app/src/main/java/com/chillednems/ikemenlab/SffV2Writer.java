package com.chillednems.ikemenlab;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** SFF v2.01 with unlinked PNG32 sprites in literal data. */
final class SffV2Writer {
    static final class Sprite {
        final int group, image, width, height;
        final byte[] png;
        Sprite(int group, int image, int width, int height, byte[] png) {
            this.group = group; this.image = image; this.width = width; this.height = height;
            this.png = png;
        }
    }

    private SffV2Writer() {}

    static void write(File file, Sprite... sprites) throws IOException {
        if (sprites.length < 1 || sprites.length > 8) throw new IOException("Invalid SFF sprite count");
        long dataLength = 4;
        for (Sprite sprite : sprites) {
            if (sprite.group < 0 || sprite.group > 65535 || sprite.image < 0 || sprite.image > 65535
                    || sprite.width < 1 || sprite.height < 1 || sprite.width > 4096 || sprite.height > 4096
                    || (long) sprite.width * sprite.height > PreviewSff.MAX_PIXELS
                    || sprite.png == null || sprite.png.length < 24 || sprite.png.length > 16 * 1024 * 1024)
                throw new IOException("Invalid SFF PNG sprite");
            dataLength += 4L + sprite.png.length;
        }
        long ldata = 68L + sprites.length * 28L + 16L;
        if (ldata + dataLength > Integer.MAX_VALUE) throw new IOException("SFF exceeds size limit");
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(file))) {
            out.write("ElecbyteSpr\0".getBytes(StandardCharsets.US_ASCII));
            out.write(new byte[]{0, 1, 0, 2});
            out.write(new byte[20]);
            u32(out, 68);
            u32(out, sprites.length);
            u32(out, 68 + sprites.length * 28);
            u32(out, 1);
            u32(out, ldata);
            u32(out, dataLength);
            u32(out, ldata + dataLength);
            u32(out, 0);
            long offset = 4;
            for (Sprite sprite : sprites) {
                u16(out, sprite.group); u16(out, sprite.image);
                u16(out, sprite.width); u16(out, sprite.height);
                u16(out, 0); u16(out, 0);
                u16(out, 65535);
                out.write(12); out.write(32);
                u32(out, offset); u32(out, sprite.png.length + 4L);
                u16(out, 0); u16(out, 0);
                offset += sprite.png.length + 4L;
            }
            u16(out, 0); u16(out, 0); u16(out, 1); u16(out, 0);
            u32(out, 0); u32(out, 4);
            u32(out, 0);
            for (Sprite sprite : sprites) {
                u32(out, (long) sprite.width * sprite.height * 4);
                out.write(sprite.png);
            }
        }
    }

    private static void u16(OutputStream out, int value) throws IOException {
        out.write(value & 255); out.write((value >>> 8) & 255);
    }
    private static void u32(OutputStream out, long value) throws IOException {
        out.write((int) value & 255); out.write((int) (value >>> 8) & 255);
        out.write((int) (value >>> 16) & 255); out.write((int) (value >>> 24) & 255);
    }
}
