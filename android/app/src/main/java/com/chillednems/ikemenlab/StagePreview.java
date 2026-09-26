package com.chillednems.ikemenlab;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Static, representative 2D stage scene from all ordered DEF BG layers. */
public final class StagePreview {
    private StagePreview() {}

    public static PreviewFrame render(File defFile, int width, int height) throws IOException {
        if (width < 1 || height < 1 || (long) width * height > 1_000_000) throw new IOException("Preview viewport exceeds limit");
        PreviewMetadata def = PreviewMetadata.parse(LibraryScanner.readText(defFile));
        String spr = def.first("bgdef").get("spr", def.first("files").get("spr", ""));
        File sffFile = PreviewMetadata.resolve(defFile, spr);
        int[] canvas = new int[width * height];
        Arrays.fill(canvas, 0xff202630);
        List<PreviewMetadata.Section> layers = new ArrayList<>();
        for (PreviewMetadata.Section section : def.sections)
            if (section.name.startsWith("bg ") && section.get("type", "").length() > 0)
                layers.add(section);
        if (layers.size() > 64 || (long) layers.size() * width * height > 30_000_000L)
            throw new IOException("Stage composition exceeds preview work limit");
        try (PreviewSff archive = new PreviewSff(sffFile)) {
            if (layers.isEmpty()) {
                if (!archive.has(9000, 1)) throw new IOException("Stage has no 2D BG layers");
                PreviewSff.Sprite thumbnail = archive.sprite(9000, 1);
                CharacterPreview.drawFit(canvas, width, height, thumbnail, 0, 0, width, height, 1);
                return new PreviewFrame(width, height, canvas, "SFF thumbnail",
                        "3D or layerless stage; full scene preview unavailable");
            }
            int[] local = PreviewMetadata.pair(def.first("stageinfo").get("localcoord", "320,240"), 320, 240);
            if (local[0] < 1 || local[1] < 1 || local[0] > 4096 || local[1] > 4096)
                throw new IOException("Invalid stage local coordinates");
            int cameraX = PreviewMetadata.integer(def.first("camera").get("startx", "0"), 0);
            int cameraY = PreviewMetadata.integer(def.first("camera").get("starty", "0"), 0);
            double scale = Math.min((double) width / local[0], (double) height / local[1]);
            double left = (width - local[0] * scale) / 2, top = (height - local[1] * scale) / 2;
            int drawn = 0, omitted = 0;
            for (PreviewMetadata.Section layer : layers) {
                if (drawn >= 100) throw new IOException("Too many stage preview layers");
                String kind = layer.get("type", "normal").toLowerCase(Locale.ROOT);
                int[] ref;
                if (kind.equals("anim")) {
                    int action = PreviewMetadata.integer(layer.get("actionno", "0"), 0);
                    try { ref = PreviewMetadata.actionFirstFrame(def, action); }
                    catch (IOException unsupported) { omitted++; continue; }
                } else {
                    ref = PreviewMetadata.pair(layer.get("spriteno", "-1,-1"), -1, -1);
                }
                if (ref[0] < 0 || ref[1] < 0 || !archive.has(ref[0], ref[1])) { omitted++; continue; }
                PreviewSff.Sprite sprite;
                try { sprite = archive.sprite(ref[0], ref[1]); }
                catch (IOException unsupported) { omitted++; continue; }
                drawLayer(canvas, width, height, sprite, layer, local, cameraX, cameraY, scale, left, top);
                drawn++;
            }
            if (drawn == 0) throw new IOException("No decodable 2D stage BG layers");
            String notice = omitted > 0 ? omitted + " unsupported stage layers omitted; static frame" : "Static frame at camera start";
            return new PreviewFrame(width, height, canvas, "2D scene (" + drawn + " layers)", notice);
        }
    }

    private static void drawLayer(int[] canvas, int width, int height, PreviewSff.Sprite sprite,
                                  PreviewMetadata.Section bg, int[] local, int cameraX, int cameraY,
                                  double scale, double left, double top) {
        int[] start = PreviewMetadata.pair(bg.get("start", "0,0"), 0, 0);
        int[] tile = PreviewMetadata.pair(bg.get("tile", "0,0"), 0, 0);
        double[] delta = PreviewMetadata.decimalPair(bg.get("delta", "1,1"), 1, 1);
        double[] xscale = PreviewMetadata.decimalPair(bg.get("xscale", "1,1"), 1, 1);
        double[] widthPair = PreviewMetadata.decimalPair(bg.get("width", "0,0"), 0, 0);
        boolean parallax = bg.get("type", "").equalsIgnoreCase("parallax");
        String blend = bg.get("trans", "none").toLowerCase(Locale.ROOT);
        double[] alpha = PreviewMetadata.decimalPair(bg.get("alpha", "256,256"), 256, 256);
        double opacity = blend.equals("addalpha") ? Math.max(0, Math.min(1, alpha[0] / 256)) : 1;
        if (opacity <= 0) return;
        double anchorX = start[0] - cameraX * delta[0] - sprite.axisX;
        double anchorY = start[1] - cameraY * delta[1] - sprite.axisY;
        int y0 = Math.max(0, (int) Math.floor(top)), y1 = Math.min(height, (int) Math.ceil(top + local[1] * scale));
        int x0 = Math.max(0, (int) Math.floor(left)), x1 = Math.min(width, (int) Math.ceil(left + local[0] * scale));
        for (int y = y0; y < y1; y++) {
            double ly = (y + 0.5 - top) / scale;
            int sy = (int) Math.floor(ly - anchorY);
            if (tile[1] != 0) sy = Math.floorMod(sy, sprite.height);
            else if (sy < 0 || sy >= sprite.height) continue;
            double t = sprite.height <= 1 ? 0 : (double) sy / (sprite.height - 1);
            double horizontalScale = 1;
            if (parallax) {
                if (widthPair[0] > 0 && widthPair[1] > 0)
                    horizontalScale = (widthPair[0] + (widthPair[1] - widthPair[0]) * t) / sprite.width;
                horizontalScale *= xscale[0] + (xscale[1] - xscale[0]) * t;
            }
            if (horizontalScale <= 0.01 || horizontalScale > 100) continue;
            for (int x = x0; x < x1; x++) {
                double lx = (x + 0.5 - left) / scale - local[0] / 2.0;
                int sx = (int) Math.floor((lx - anchorX) / horizontalScale);
                if (tile[0] != 0) sx = Math.floorMod(sx, sprite.width);
                else if (sx < 0 || sx >= sprite.width) continue;
                int pixel = sprite.argb[sy * sprite.width + sx];
                int at = y * width + x;
                if (blend.equals("add")) canvas[at] = add(canvas[at], pixel);
                else if (blend.equals("sub")) canvas[at] = subtract(canvas[at], pixel);
                else canvas[at] = CharacterPreview.blend(canvas[at], pixel, opacity);
            }
        }
    }

    private static int add(int below, int above) {
        int a = (above >>> 24) & 255;
        int r = Math.min(255, ((below >>> 16) & 255) + ((above >>> 16) & 255) * a / 255);
        int g = Math.min(255, ((below >>> 8) & 255) + ((above >>> 8) & 255) * a / 255);
        int b = Math.min(255, (below & 255) + (above & 255) * a / 255);
        return 0xff000000 | r << 16 | g << 8 | b;
    }
    private static int subtract(int below, int above) {
        int a = (above >>> 24) & 255;
        int r = Math.max(0, ((below >>> 16) & 255) - ((above >>> 16) & 255) * a / 255);
        int g = Math.max(0, ((below >>> 8) & 255) - ((above >>> 8) & 255) * a / 255);
        int b = Math.max(0, (below & 255) - (above & 255) * a / 255);
        return 0xff000000 | r << 16 | g << 8 | b;
    }
}
