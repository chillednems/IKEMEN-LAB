package com.chillednems.ikemenlab;

import java.io.IOException;

/** Integer center crop shared by generation and preview. */
final class PngStageGeometry {
    static final int WIDTH = 1280, HEIGHT = 720;
    final int left, top, right, bottom;

    private PngStageGeometry(int left, int top, int right, int bottom) {
        this.left = left; this.top = top; this.right = right; this.bottom = bottom;
    }

    static PngStageGeometry of(int width, int height, int targetWidth, int targetHeight) throws IOException {
        if (width < 320 || height < 240 || width > 4096 || height > 4096
                || (long) width * height > 8_000_000 || targetWidth < 1 || targetHeight < 1)
            throw new IOException("PNG dimensions must be 320×240–4096 per side and at most 8 million pixels");
        long scaledWidth = (long) height * targetWidth;
        long scaledHeight = (long) width * targetHeight;
        if (scaledWidth < scaledHeight) {
            int cropWidth = Math.max(1, (int) ((long) height * targetWidth / targetHeight));
            int left = (width - cropWidth) / 2;
            return new PngStageGeometry(left, 0, left + cropWidth, height);
        }
        int cropHeight = Math.max(1, (int) ((long) width * targetHeight / targetWidth));
        int top = (height - cropHeight) / 2;
        return new PngStageGeometry(0, top, width, top + cropHeight);
    }

    boolean cropped(int width, int height) {
        return left != 0 || top != 0 || right != width || bottom != height;
    }
}
