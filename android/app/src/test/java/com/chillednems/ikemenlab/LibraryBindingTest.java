package com.chillednems.ikemenlab;

import org.junit.Test;

import static org.junit.Assert.*;

public final class LibraryBindingTest {
    @Test public void reconnectRequiresTheRememberedTreeButAllowsFirstLegacyConnection() {
        String original = "content://provider/tree/original";
        assertTrue(LibraryBinding.acceptsReconnect(original, original));
        assertFalse(LibraryBinding.acceptsReconnect(original, "content://provider/tree/other"));
        assertTrue(LibraryBinding.acceptsReconnect(null, original));
        assertFalse(LibraryBinding.acceptsReconnect(original, null));
    }
}
