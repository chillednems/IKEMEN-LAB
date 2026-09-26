package com.chillednems.ikemenlab;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;
import static org.junit.Assert.*;

public final class AdvancedPreviewTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private static void put16(byte[] d, int at, int n) { d[at] = (byte) n; d[at + 1] = (byte) (n >>> 8); }
    private static void put32(byte[] d, int at, int n) { put16(d, at, n); put16(d, at + 2, n >>> 16); }
    private static void big32(ByteArrayOutputStream out, int n) {
        out.write(n >>> 24); out.write(n >>> 16); out.write(n >>> 8); out.write(n);
    }
    private static void chunk(ByteArrayOutputStream out, String name, byte[] body) throws IOException {
        byte[] type = name.getBytes(StandardCharsets.US_ASCII);
        big32(out, body.length); out.write(type); out.write(body);
        CRC32 crc = new CRC32(); crc.update(type); crc.update(body); big32(out, (int) crc.getValue());
    }
    private static byte[] png(int paletteIndex) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[] {(byte) 137,80,78,71,13,10,26,10});
        chunk(out, "IHDR", new byte[] {0,0,0,2,0,0,0,2,8,3,0,0,0});
        chunk(out, "PLTE", new byte[] {0,0,0,(byte)255,0,0,0,(byte)255,0});
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (DeflaterOutputStream zip = new DeflaterOutputStream(compressed)) {
            zip.write(new byte[] {0,(byte) paletteIndex,(byte) paletteIndex,0,(byte) paletteIndex,(byte) paletteIndex});
        }
        chunk(out, "IDAT", compressed.toByteArray()); chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }
    private static byte[] fixture() throws IOException {
        byte[] red = png(1), green = png(2);
        int ldata = 220, first = 12, second = first + 4 + red.length;
        byte[] d = new byte[ldata + second + 4 + green.length];
        System.arraycopy("ElecbyteSpr".getBytes(StandardCharsets.US_ASCII), 0, d, 0, 11); d[15] = 2;
        put32(d, 36, 80); put32(d, 40, 3); put32(d, 44, 164); put32(d, 48, 1); put32(d, 52, ldata);
        // 0,0 red; 677,131 green; 677,0 links to the green record.
        put16(d, 80, 0); put16(d, 82, 0); put16(d, 84, 2); put16(d, 86, 2);
        d[94] = 10; put32(d, 96, first); put32(d, 100, 4 + red.length);
        put16(d, 108, 677); put16(d, 110, 131); put16(d, 112, 2); put16(d, 114, 2);
        d[122] = 10; put32(d, 124, second); put32(d, 128, 4 + green.length);
        put16(d, 136, 677); put16(d, 138, 0); put16(d, 140, 2); put16(d, 142, 2);
        put16(d, 148, 1); d[150] = 1;
        put32(d, 176, 12);
        d[ldata + 3] = (byte) 255; // palette zero is transparent
        d[ldata + 4] = (byte) 255; d[ldata + 7] = (byte) 255;
        d[ldata + 9] = (byte) 255; d[ldata + 11] = (byte) 255;
        System.arraycopy(red, 0, d, ldata + first + 4, red.length);
        System.arraycopy(green, 0, d, ldata + second + 4, green.length);
        return d;
    }
    private File file(String name, byte[] bytes) throws IOException {
        File f = folder.newFile(name); Files.write(f.toPath(), bytes); return f;
    }
    private File textFile(String name, String text) throws IOException {
        return file(name, text.getBytes(StandardCharsets.UTF_8));
    }

    @Test public void indexedPngAndLinkedNeutralFollowAirActionZero() throws Exception {
        File sff = file("pose.sff", fixture());
        try (PreviewSff archive = new PreviewSff(sff)) {
            assertEquals(0xffff0000, archive.sprite(0, 0).argb[0]);
            PreviewSff.Sprite linked = archive.sprite(677, 0);
            assertEquals(677, linked.group); assertEquals(0, linked.image);
            assertEquals(0xff00ff00, linked.argb[0]);
        }
        textFile("pose.air", "[Begin Action 0]\nClsn2: 1\nClsn2[0] = -1, -1, 1, 1\n677,0, 0,0, 10\n");
        File def = textFile("pose.def", "[Files]\nsprite = pose.sff\nanim = pose.air\n");
        PreviewFrame frame = CharacterPreview.render(def, CharacterPreview.Mode.NEUTRAL, 40, 40);
        assertTrue(frame.source.contains("677,0"));
        assertTrue(java.util.Arrays.stream(frame.argb).anyMatch(pixel -> pixel == 0xff00ff00));
    }

    @Test public void repeatedBgSectionsComposeInFileOrder() throws Exception {
        file("scene.sff", fixture());
        File def = textFile("scene.def", "[BGDef]\nspr=scene.sff\n[StageInfo]\nlocalcoord=4,4\n" +
                "[BG 1]\ntype=normal\nspriteno=0,0\nstart=0,0\ntile=1,1\n" +
                "[BG 1]\ntype=normal\nspriteno=677,131\nstart=0,0\ntile=1,1\n");
        PreviewFrame frame = StagePreview.render(def, 40, 40);
        assertEquals("2D scene (2 layers)", frame.source);
        assertEquals(0xff00ff00, frame.argb[20 * 40 + 20]);
    }

    @Test public void malformedBoundsAndWorkAreRejected() throws Exception {
        byte[] bad = fixture(); put32(bad, 96, Integer.MAX_VALUE);
        File sff = file("bad.sff", bad);
        try (PreviewSff archive = new PreviewSff(sff)) {
            try { archive.sprite(0, 0); fail("bad offset accepted"); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("limit") || expected.getMessage().contains("bounds")); }
        }
        file("many.sff", fixture());
        StringBuilder def = new StringBuilder("[BGDef]\nspr=many.sff\n");
        for (int i = 0; i < 65; i++) def.append("[BG ").append(i).append("]\ntype=normal\nspriteno=0,0\n");
        File many = textFile("many.def", def.toString());
        try { StagePreview.render(many, 40, 40); fail("unbounded stage accepted"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("work limit")); }
    }

    @Test public void largeSparseSffUsesBoundedSpriteReadAndRejectsHugePng() throws Exception {
        File sparse = folder.newFile("sparse.sff");
        try (java.io.RandomAccessFile out = new java.io.RandomAccessFile(sparse, "rw")) {
            out.write(fixture()); out.setLength(65L * 1024 * 1024);
        }
        SffPreview.Result result = SffPreview.extract(sparse, false, 0, 0);
        assertTrue(result.unavailable, result.available());
        assertEquals(0xffff0000, result.argb[0]);
        byte[] invalid = png(1);
        invalid[16] = 0x7f;
        try { PreviewPng.decode(invalid, 0, invalid.length, null, false, 2, 2); fail("huge PNG accepted"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("dimensions")); }
    }
}
