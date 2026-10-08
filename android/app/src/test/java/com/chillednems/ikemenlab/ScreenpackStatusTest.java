package com.chillednems.ikemenlab;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class ScreenpackStatusTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private void write(String path, String text) throws Exception {
        File file = new File(temporary.getRoot(), path);
        assertTrue(file.getParentFile().isDirectory() || file.getParentFile().mkdirs());
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    @Test public void sectionedIniAndRedirectedScreenpackUseGlobalRoster() throws Exception {
        write("save/config.ini", "[Other]\nMotif = data/wrong/system.def\n[Config]\nMotif = data/pack/system.def ; active\n");
        write("data/pack/system.def", "[Files]\nselect = ../select.def\n[Select Info]\nrows = 3\ncolumns = 7\n");
        write("data/select.def", "[Characters]\nKFM\n");
        ScreenpackStatus status = ScreenpackStatus.inspect(LibraryFiles.local(temporary.getRoot()));
        assertTrue(status.globalRoster);
        assertEquals("data/select.def", status.select);
        assertEquals(21, status.capacity());
    }

    @Test public void localOrUnsafeSelectNeverClaimsGlobalRoster() throws Exception {
        write("save/config.ini", "Motif = data/pack/system.def\n");
        write("data/pack/system.def", "[Files]\nselect = select.def\n[Select Info]\nrows = 99999999\ncolumns = 2\n");
        write("data/pack/select.def", "[Characters]\nKFM\n");
        ScreenpackStatus local = ScreenpackStatus.inspect(LibraryFiles.local(temporary.getRoot()));
        assertTrue(local.knownAlternate);
        assertEquals(0, local.capacity());
        write("data/pack/system.def", "[Files]\nselect = ../../../escape.def\n");
        ScreenpackStatus unsafe = ScreenpackStatus.inspect(LibraryFiles.local(temporary.getRoot()));
        assertFalse(unsafe.globalRoster);
        assertFalse(unsafe.knownAlternate);
    }
}
