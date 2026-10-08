package com.chillednems.ikemenlab;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;

import static org.junit.Assert.*;

public final class PngStageTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void centerCropKeepsCorrectAxisAndBounds() throws Exception {
        PngStageGeometry wide = PngStageGeometry.of(2000, 1000, 1280, 720);
        assertEquals(111, wide.left);
        assertEquals(1888, wide.right);
        assertEquals(0, wide.top);
        assertEquals(1000, wide.bottom);
        PngStageGeometry tall = PngStageGeometry.of(1000, 1600, 1280, 720);
        assertEquals(0, tall.left);
        assertEquals(1000, tall.right);
        assertEquals(519, tall.top);
        assertEquals(1081, tall.bottom);
        PngStageGeometry square = PngStageGeometry.of(1000, 1000, 1280, 720);
        assertEquals(219, square.top);
        assertEquals(781, square.bottom);
        assertEquals(0, square.left);
        assertEquals(1000, square.right);
        PngStageGeometry exact = PngStageGeometry.of(1280, 720, 1280, 720);
        assertFalse(exact.cropped(1280, 720));
    }

    @Test public void rejectsUnboundedDimensionsAndUnsafeNames() throws Exception {
        try { PngStageGeometry.of(4096, 4096, 1280, 720); fail(); }
        catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("8 million")); }
        try { PngStageGeometry.of(319, 240, 1280, 720); fail(); }
        catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("320")); }
        assertEquals("png-stage-my-arena", PngStageGenerator.slug("My Arena.png"));
        assertEquals("png-stage-arena", PngStageGenerator.slug("../Arena.PNG"));
        try { PngStageGenerator.slug("bad.zip"); fail(); }
        catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("PNG")); }
    }

    @Test public void pngInputStreamIsCappedBeforeDecode() throws Exception {
        File scratch = new File(temporary.getRoot(), "oversize.png");
        InputStream repeated = new InputStream() {
            int remaining = PngStageGenerator.MAX_INPUT + 1;
            @Override public int read() { if (remaining-- <= 0) return -1; return 0; }
            @Override public int read(byte[] buffer, int offset, int length) {
                if (remaining <= 0) return -1;
                int count = Math.min(length, remaining);
                remaining -= count;
                return count;
            }
        };
        try { PngStageGenerator.copyBounded(repeated, scratch, () -> false); fail(); }
        catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("16 MB")); }
    }

    @Test public void sffRoundTripsThroughPreviewAndStaticStageRenderer() throws Exception {
        File directory = temporary.newFolder("png-stage-fixture");
        byte[] thumbnail = png(240, 100, 0xff365e9c);
        byte[] background = pngStriped(1280, 720, 0xffff0000, 0xff21543b, 0xff0000ff);
        File sff = new File(directory, "fixture.sff");
        SffV2Writer.write(sff,
                new SffV2Writer.Sprite(9000, 1, 240, 100, thumbnail),
                new SffV2Writer.Sprite(0, 0, 1280, 720, background));
        try (PreviewSff archive = new PreviewSff(sff)) {
            assertTrue(archive.has(9000, 1));
            assertTrue(archive.has(0, 0));
            PreviewSff.Sprite thumb = archive.sprite(9000, 1);
            PreviewSff.Sprite main = archive.sprite(0, 0);
            assertEquals(0xff365e9c, thumb.argb[0]);
            assertEquals(0xff21543b, main.argb[360 * 1280 + 640]);
            assertEquals(0xffff0000, main.argb[360 * 1280 + 10]);
            assertEquals(0xff0000ff, main.argb[360 * 1280 + 1270]);
            assertEquals(1280, main.width);
            assertEquals(720, main.height);
        }
        File def = new File(directory, "fixture.def");
        Files.write(def.toPath(), PngStageGenerator.stageDef("fixture")
                .getBytes(StandardCharsets.UTF_8));
        PreviewFrame frame = StagePreview.render(def, 64, 36);
        assertEquals("2D scene (1 layers)", frame.source);
        assertEquals(0xff21543b, frame.argb[18 * 64 + 32]);
        assertEquals(0xffff0000, frame.argb[18 * 64 + 5]);
        assertEquals(0xff0000ff, frame.argb[18 * 64 + 59]);
        AddonPackage staged = AddonPackage.fromFolder(LibraryFiles.local(directory),
                temporary.newFolder("private-stage"), "stages");
        AddonHealth.Report health = AddonHealth.inspectStage(staged,
                LibraryFiles.local(temporary.newFolder("empty-source")));
        assertFalse(health.summary(), health.blocked);
        assertEquals(1, health.checked);
    }

    @Test public void cropCoordinatesRetainCenterMarkerAndExcludeEdges() throws Exception {
        PngStageGeometry crop = PngStageGeometry.of(2000, 1000, 1280, 720);
        int centerMarker = 1000;
        assertTrue(centerMarker >= crop.left && centerMarker < crop.right);
        assertTrue(crop.left > 0);
        assertTrue(crop.right < 2000);
    }

    private static byte[] png(int width, int height, int color) throws Exception {
        return pngStriped(width, height, color, color, color);
    }

    private static byte[] pngStriped(int width, int height, int leftColor, int centerColor,
                                     int rightColor) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10});
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        big32(header, width); big32(header, height);
        header.write(new byte[]{8, 6, 0, 0, 0});
        chunk(out, "IHDR", header.toByteArray());
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        byte[] row = new byte[1 + width * 4];
        for (int x = 0; x < width; x++) {
            int color = x < width / 6 ? leftColor : x >= width * 5 / 6 ? rightColor : centerColor;
            int at = 1 + x * 4;
            row[at] = (byte) (color >>> 16); row[at + 1] = (byte) (color >>> 8);
            row[at + 2] = (byte) color; row[at + 3] = (byte) (color >>> 24);
        }
        try (DeflaterOutputStream zip = new DeflaterOutputStream(compressed))
            { for (int y = 0; y < height; y++) zip.write(row); }
        chunk(out, "IDAT", compressed.toByteArray());
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void big32(ByteArrayOutputStream out, int value) {
        out.write(value >>> 24); out.write(value >>> 16); out.write(value >>> 8); out.write(value);
    }

    private static void chunk(ByteArrayOutputStream out, String name, byte[] data) throws Exception {
        byte[] type = name.getBytes(StandardCharsets.US_ASCII);
        big32(out, data.length); out.write(type); out.write(data);
        CRC32 crc = new CRC32(); crc.update(type); crc.update(data); big32(out, (int) crc.getValue());
    }
}
