package com.chillednems.ikemenlab;

/** ARGB preview and provenance for the Android bitmap adapter. */
public final class PreviewFrame {
    public final int width, height;
    public final int[] argb;
    public final String source, notice;
    PreviewFrame(int width, int height, int[] argb, String source, String notice) {
        this.width = width; this.height = height; this.argb = argb; this.source = source; this.notice = notice;
    }
}
