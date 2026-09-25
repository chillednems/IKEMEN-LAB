package com.chillednems.ikemenlab;

import org.junit.Test;
import java.util.Base64;
import static org.junit.Assert.*;

public final class SffPreviewTest {
    private static void put16(byte[] data, int at, int value) { data[at] = (byte) value; data[at + 1] = (byte) (value >> 8); }
    private static void put32(byte[] data, int at, int value) { put16(data, at, value); put16(data, at + 2, value >> 16); }
    private static void signature(byte[] data, int version) {
        byte[] mark = "ElecbyteSpr".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(mark, 0, data, 0, mark.length);
        data[15] = (byte) version;
    }

    private static byte[] v1(int group, int image) {
        int pcxLength = 128 + 4 + 769;
        byte[] data = new byte[64 + 32 + pcxLength];
        signature(data, 1); put32(data, 20, 1); put32(data, 24, 64);
        put32(data, 68, pcxLength); put16(data, 76, group); put16(data, 78, image);
        int pcx = 96;
        data[pcx] = 10; data[pcx + 2] = 1; data[pcx + 3] = 8;
        put16(data, pcx + 8, 1); put16(data, pcx + 10, 1);
        data[pcx + 65] = 1; put16(data, pcx + 66, 2);
        for (int i = 0; i < 4; i++) data[pcx + 128 + i] = 1;
        int palette = pcx + pcxLength - 769;
        data[palette] = 12; data[palette + 4] = (byte) 255;
        return data;
    }

    @Test public void v1PortraitAndReferencedStageAreRealRedPixels() {
        SffPreview.Result portrait = SffPreview.extract(v1(9000, 0), true, 0, 0);
        assertTrue(portrait.unavailable, portrait.available());
        assertEquals(2, portrait.width); assertEquals(0xffff0000, portrait.argb[0]);
        SffPreview.Result stage = SffPreview.extract(v1(20, 3), false, 20, 3);
        assertTrue(stage.unavailable, stage.available());
        assertEquals(0xffff0000, stage.argb[3]);
    }

    @Test public void v2EmbeddedPngIsExtracted() {
        byte[] png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=");
        byte[] data = new byte[108 + 4 + png.length];
        signature(data, 2); put32(data, 36, 80); put32(data, 40, 1); put32(data, 52, 108);
        put16(data, 80, 9000); put16(data, 82, 1); put16(data, 84, 1); put16(data, 86, 1);
        data[94] = 11; put32(data, 100, 4 + png.length);
        System.arraycopy(png, 0, data, 112, png.length);
        SffPreview.Result result = SffPreview.extract(data, true, 0, 0);
        assertTrue(result.unavailable, result.available());
        assertArrayEquals(png, result.png);
    }

    @Test public void invalidAndUnsupportedSpritesReportUnavailable() {
        assertFalse(SffPreview.extract(new byte[64], true, 0, 0).available());
        byte[] data = new byte[136];
        signature(data, 2); put32(data, 36, 80); put32(data, 40, 1);
        put16(data, 80, 9000); put16(data, 82, 1); put16(data, 84, 2); put16(data, 86, 2);
        data[94] = 2;
        SffPreview.Result result = SffPreview.extract(data, true, 0, 0);
        assertFalse(result.available());
        assertTrue(result.unavailable.contains("not supported"));
    }
}
