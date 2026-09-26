package com.chillednems.ikemenlab;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;

/** Character artwork modes built from declared DEF/AIR assets. */
public final class CharacterPreview {
    public enum Mode { PORTRAIT, NEUTRAL, NEUTRAL_OVER_PORTRAIT }
    private CharacterPreview() {}

    public static PreviewFrame render(File defFile, Mode mode, int width, int height) throws IOException {
        return render(LibraryFiles.child(LibraryFiles.local(defFile.getParentFile()), defFile.getName()), mode, width, height);
    }

    public static PreviewFrame render(LibraryFiles.Node defFile, Mode mode, int width, int height) throws IOException {
        if (width < 1 || height < 1 || (long) width * height > 1_000_000) throw new IOException("Preview viewport exceeds limit");
        PreviewMetadata def = PreviewMetadata.parse(LibraryScanner.readText(defFile));
        PreviewMetadata.Section files = def.first("files");
        String sffPath = files.get("sprite", files.get("spr", ""));
        LibraryFiles.Node sff = PreviewMetadata.resolve(defFile, sffPath);
        int[] canvas = new int[width * height];
        Arrays.fill(canvas, 0xff151923);
        try (PreviewSff archive = new PreviewSff(sff)) {
            PreviewSff.Sprite portrait = null, neutral = null;
            int[] neutralFrame = null;
            if (mode != Mode.NEUTRAL) {
                IOException last = null;
                for (int image : new int[] {1, 2, 0}) {
                    if (!archive.has(9000, image)) continue;
                    try { portrait = archive.sprite(9000, image); break; }
                    catch (IOException error) { last = error; }
                }
                if (portrait == null && mode == Mode.PORTRAIT) throw new IOException("Character portrait unavailable", last);
            }
            if (mode != Mode.PORTRAIT) {
                LibraryFiles.Node air = PreviewMetadata.resolve(defFile, files.get("anim", ""));
                neutralFrame = PreviewMetadata.actionFirstFrame(PreviewMetadata.parse(LibraryScanner.readText(air)), 0);
                neutral = archive.sprite(neutralFrame[0], neutralFrame[1]);
                if (mode == Mode.NEUTRAL_OVER_PORTRAIT && portrait != null)
                    drawFit(canvas, width, height, portrait, 0, 0, width, height, 0.55);
                int boxX = mode == Mode.NEUTRAL_OVER_PORTRAIT ? width / 3 : 0;
                int boxWidth = mode == Mode.NEUTRAL_OVER_PORTRAIT ? width * 2 / 3 : width;
                drawNeutral(canvas, width, height, neutral, neutralFrame[2], neutralFrame[3], boxX, boxWidth);
            } else drawFit(canvas, width, height, portrait, 0, 0, width, height, 1);
            String source = mode == Mode.PORTRAIT ? "portrait " + portrait.group + "," + portrait.image :
                    "AIR action 0 " + neutral.group + "," + neutral.image;
            String notice = mode == Mode.NEUTRAL_OVER_PORTRAIT && portrait == null ?
                    "Portrait unavailable; showing neutral stance" : null;
            return new PreviewFrame(width, height, canvas, source, notice);
        }
    }

    private static void drawNeutral(int[] dest, int dw, int dh, PreviewSff.Sprite sprite,
                                    int frameX, int frameY, int bx, int bw) {
        double scale = Math.min((double) bw * 0.8 / sprite.width, (double) dh * 0.82 / sprite.height);
        int w = Math.max(1, (int) Math.round(sprite.width * scale));
        int h = Math.max(1, (int) Math.round(sprite.height * scale));
        int left = (int) Math.round(bx + bw / 2.0 - (sprite.axisX - frameX) * scale);
        int top = (int) Math.round(dh * 0.92 - (sprite.axisY - frameY) * scale);
        drawScaled(dest, dw, dh, sprite, left, top, w, h, 1);
    }

    static void drawFit(int[] dest, int dw, int dh, PreviewSff.Sprite sprite,
                        int bx, int by, int bw, int bh, double alpha) {
        double scale = Math.min((double) bw * 0.9 / sprite.width, (double) bh * 0.9 / sprite.height);
        int w = Math.max(1, (int) Math.round(sprite.width * scale)), h = Math.max(1, (int) Math.round(sprite.height * scale));
        int left = bx + (bw - w) / 2, top = by + (bh - h) / 2;
        drawScaled(dest, dw, dh, sprite, left, top, w, h, alpha);
    }

    private static void drawScaled(int[] dest, int dw, int dh, PreviewSff.Sprite sprite,
                                   int left, int top, int w, int h, double alpha) {
        for (int y = Math.max(0, top); y < Math.min(dh, top + h); y++)
            for (int x = Math.max(0, left); x < Math.min(dw, left + w); x++) {
                int sx = Math.min(sprite.width - 1, (int) ((long) (x - left) * sprite.width / w));
                int sy = Math.min(sprite.height - 1, (int) ((long) (y - top) * sprite.height / h));
                int color = sprite.argb[sy * sprite.width + sx];
                dest[y * dw + x] = blend(dest[y * dw + x], color, alpha);
            }
    }
    static int blend(int below, int above, double extraAlpha) {
        int a = (int) Math.round(((above >>> 24) & 255) * extraAlpha);
        if (a <= 0) return below;
        int r = (((above >>> 16) & 255) * a + ((below >>> 16) & 255) * (255 - a)) / 255;
        int g = (((above >>> 8) & 255) * a + ((below >>> 8) & 255) * (255 - a)) / 255;
        int b = ((above & 255) * a + (below & 255) * (255 - a)) / 255;
        return 0xff000000 | r << 16 | g << 8 | b;
    }
}
