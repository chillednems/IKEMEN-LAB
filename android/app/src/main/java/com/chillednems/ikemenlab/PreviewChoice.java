package com.chillednems.ikemenlab;

import java.io.File;

/** Saved character display choice and identity for asynchronous preview work. */
final class PreviewChoice {
    private PreviewChoice() {}

    static CharacterPreview.Mode fromPreference(String value) {
        if (value != null) try { return CharacterPreview.Mode.valueOf(value); }
        catch (IllegalArgumentException ignored) { }
        return CharacterPreview.Mode.NEUTRAL;
    }

    static String label(CharacterPreview.Mode mode) {
        return switch (mode) {
            case PORTRAIT -> "Portrait";
            case NEUTRAL -> "Neutral stance";
            case NEUTRAL_OVER_PORTRAIT -> "Neutral over portrait";
        };
    }

    static String key(File library, LibraryScanner.Item item, CharacterPreview.Mode mode) {
        return library.getAbsolutePath() + '\u0000' + item.kind + '\u0000' + item.reference + '\u0000'
                + item.file + '\u0000' + item.previewFile + '\u0000' + mode.name();
    }
}
