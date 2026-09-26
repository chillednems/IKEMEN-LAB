package com.chillednems.ikemenlab;

import org.junit.Test;

import java.io.File;

import static org.junit.Assert.*;

public final class PreviewChoiceTest {
    @Test public void savedModeDefaultsToNeutralAndAcceptsAllThreeChoices() {
        assertEquals(CharacterPreview.Mode.NEUTRAL, PreviewChoice.fromPreference(null));
        assertEquals(CharacterPreview.Mode.NEUTRAL, PreviewChoice.fromPreference("unexpected"));
        for (CharacterPreview.Mode mode : CharacterPreview.Mode.values())
            assertEquals(mode, PreviewChoice.fromPreference(mode.name()));
    }

    @Test public void requestIdentityChangesWithLibrarySelectionAndMode() {
        LibraryScanner.Item first = new LibraryScanner.Item("characters", "chars/a.def", "A", "", "/one/a.def", true);
        LibraryScanner.Item second = new LibraryScanner.Item("characters", "chars/b.def", "B", "", "/one/b.def", true);
        String key = PreviewChoice.key(new File("/one"), first, CharacterPreview.Mode.NEUTRAL);
        assertNotEquals(key, PreviewChoice.key(new File("/two"), first, CharacterPreview.Mode.NEUTRAL));
        assertNotEquals(key, PreviewChoice.key(new File("/one"), second, CharacterPreview.Mode.NEUTRAL));
        assertNotEquals(key, PreviewChoice.key(new File("/one"), first, CharacterPreview.Mode.PORTRAIT));
        assertNotEquals(key, PreviewChoice.key(new File("/one"), first, CharacterPreview.Mode.NEUTRAL_OVER_PORTRAIT));
    }
}
