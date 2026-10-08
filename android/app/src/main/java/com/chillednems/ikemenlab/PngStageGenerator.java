package com.chillednems.ikemenlab;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/** Creates one deliberately simple, static 2D stage in private storage. */
final class PngStageGenerator {
    static final int MAX_INPUT = 16 * 1024 * 1024;
    static final class Result {
        final File background;
        final String cropNote;
        Result(File background, String cropNote) {
            this.background = background; this.cropNote = cropNote;
        }
    }

    private PngStageGenerator() {}

    static String slug(String displayName) throws IOException {
        if (displayName == null) throw new IOException("PNG document has no name");
        String lower = displayName.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".png")) throw new IOException("Choose one PNG image");
        String base = lower.substring(0, lower.length() - 4).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (base.length() > 40) base = base.substring(0, 40).replaceAll("-+$", "");
        if (base.isEmpty()) throw new IOException("PNG name needs an ASCII letter or digit");
        return "png-stage-" + base;
    }

    static Result generate(InputStream input, File folder, String slug, BooleanSupplier canceled)
            throws IOException {
        if (input == null || !slug.matches("png-stage-[a-z0-9][a-z0-9-]{0,39}"))
            throw new IOException("Invalid PNG stage request");
        File scratch = new File(folder, ".source-image");
        Bitmap source = null, background = null, thumbnail = null;
        try {
            copyBounded(input, scratch, canceled);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(scratch.getAbsolutePath(), bounds);
            PngStageGeometry crop = PngStageGeometry.of(bounds.outWidth, bounds.outHeight,
                    PngStageGeometry.WIDTH, PngStageGeometry.HEIGHT);
            check(canceled);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            int cropWidth = crop.right - crop.left, cropHeight = crop.bottom - crop.top;
            options.inSampleSize = cropWidth >= 2560 && cropHeight >= 1440 ? 2 : 1;
            BitmapRegionDecoder decoder = BitmapRegionDecoder.newInstance(scratch.getAbsolutePath(), false);
            try { source = decoder.decodeRegion(new Rect(crop.left, crop.top, crop.right, crop.bottom), options); }
            finally { decoder.recycle(); }
            if (source == null) throw new IOException("PNG crop could not be decoded");
            background = Bitmap.createBitmap(PngStageGeometry.WIDTH, PngStageGeometry.HEIGHT, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(background);
            Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
            canvas.drawBitmap(source, new Rect(0, 0, source.getWidth(), source.getHeight()),
                    new Rect(0, 0, PngStageGeometry.WIDTH, PngStageGeometry.HEIGHT), paint);
            source.recycle(); source = null;
            check(canceled);
            thumbnail = Bitmap.createBitmap(240, 100, Bitmap.Config.ARGB_8888);
            new Canvas(thumbnail).drawBitmap(background,
                    new Rect(0, 80, PngStageGeometry.WIDTH, 640), new Rect(0, 0, 240, 100), paint);
            byte[] bgPng = encode(background, canceled);
            byte[] thumbPng = encode(thumbnail, canceled);
            if (!folder.isDirectory() || !folder.getName().equals(slug))
                throw new IOException("Private stage folder unavailable");
            File png = new File(folder, slug + ".png");
            write(png, bgPng);
            SffV2Writer.write(new File(folder, slug + ".sff"),
                    new SffV2Writer.Sprite(9000, 1, 240, 100, thumbPng),
                    new SffV2Writer.Sprite(0, 0, PngStageGeometry.WIDTH, PngStageGeometry.HEIGHT, bgPng));
            write(new File(folder, slug + ".def"), stageDef(slug).getBytes(StandardCharsets.UTF_8));
            check(canceled);
            Files.delete(scratch.toPath());
            String note = bounds.outWidth + "×" + bounds.outHeight + " source → 1280×720 static background"
                    + (crop.cropped(bounds.outWidth, bounds.outHeight)
                    ? "; centered crop removes outer image edges" : "; full image kept");
            return new Result(png, note);
        } catch (OutOfMemoryError exhausted) {
            throw new IOException("PNG stage exceeds available image memory", exhausted);
        } finally {
            if (thumbnail != null) thumbnail.recycle();
            if (background != null) background.recycle();
            if (source != null) source.recycle();
            if (scratch.exists()) scratch.delete();
        }
    }

    static void copyBounded(InputStream input, File scratch, BooleanSupplier canceled) throws IOException {
        try (InputStream in = input; FileOutputStream out = new FileOutputStream(scratch)) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int count;
            while ((count = in.read(buffer)) != -1) {
                check(canceled);
                if (total > MAX_INPUT - count) throw new IOException("PNG exceeds 16 MB input limit");
                out.write(buffer, 0, count);
                total += count;
            }
            if (total < 24) throw new IOException("Input is not a PNG image");
            out.getFD().sync();
        }
        try (FileInputStream verify = new FileInputStream(scratch)) {
            byte[] magic = new byte[8];
            if (verify.read(magic) != 8 || magic[0] != (byte) 0x89 || magic[1] != 'P'
                    || magic[2] != 'N' || magic[3] != 'G' || magic[4] != 13
                    || magic[5] != 10 || magic[6] != 26 || magic[7] != 10)
                throw new IOException("Input is not a PNG image");
        }
    }

    private static byte[] encode(Bitmap image, BooleanSupplier canceled) throws IOException {
        check(canceled);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!image.compress(Bitmap.CompressFormat.PNG, 100, out)
                || out.size() > MAX_INPUT) throw new IOException("Generated PNG exceeds size limit");
        check(canceled);
        return out.toByteArray();
    }

    private static void write(File file, byte[] bytes) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes); out.getFD().sync();
        }
    }

    private static void check(BooleanSupplier canceled) throws IOException {
        if (canceled.getAsBoolean()) throw new IOException("PNG stage creation canceled");
    }

    static String stageDef(String name) {
        return "; Experimental static stage generated by IKEMEN Lab\n"
                + "[Info]\nname = \"" + name + "\"\ndisplayname = \"" + name
                + "\"\nauthor = \"IKEMEN Lab\"\n\n"
                + "[Camera]\nstartx = 0\nstarty = 0\nboundleft = 0\nboundright = 0\n"
                + "boundhigh = 0\nboundlow = 0\ntension = 50\nverticalfollow = 0\n"
                + "zoomout = 1\nzoomin = 1\n\n"
                + "[PlayerInfo]\np1startx = -160\np1starty = 0\np1facing = 1\n"
                + "p2startx = 160\np2starty = 0\np2facing = -1\n"
                + "leftbound = -600\nrightbound = 600\n\n"
                + "[StageInfo]\nzoffset = 680\nautoturn = 1\nresetBG = 1\n"
                + "localcoord = 1280,720\nxscale = 1\nyscale = 1\n\n"
                + "[Shadow]\nintensity = 0\n\n[Reflection]\nintensity = 0\n\n"
                + "[BGDef]\nspr = " + name + ".sff\ndebugbg = 0\n\n"
                + "[Begin Action 9000]\n9000,1, 0,0, -1\n\n"
                + "[BG Main]\ntype = normal\nspriteno = 0,0\nlayerno = 0\n"
                + "start = -640,0\ndelta = 1,1\nmask = 0\ntile = 0,0\n";
    }
}
