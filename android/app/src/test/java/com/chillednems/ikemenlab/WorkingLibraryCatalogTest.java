package com.chillednems.ikemenlab;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;

public final class WorkingLibraryCatalogTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void previousPrivateRosterCanBeDiscoveredAgain() throws Exception {
        File parent = temporary.newFolder("libraries");
        File old = new File(parent, "089f9100-0909-4a92-8774-ae6318c4b199");
        File current = new File(parent, "d506a87e-f095-4a02-8c05-971523829606");
        for (File root : new File[]{old, current}) {
            File data = new File(root, "data");
            assertTrue(data.mkdirs());
            Files.write(new File(data, "select.def").toPath(), "[Characters]\nA\n".getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(2, WorkingLibraryCatalog.list(parent).size());
        assertTrue(WorkingLibraryCatalog.list(parent).contains(old));
        assertTrue(WorkingLibraryCatalog.list(parent).contains(current));
    }

    @Test public void pendingExportOrRestorePreventsSwitchingAway() throws Exception {
        File root = temporary.newFolder("working");
        File backup = new File(root, "data/select-backups");
        assertTrue(backup.mkdirs());
        for (String journal : new String[]{"pending-export.properties", "pending-restore.properties"}) {
            File file = new File(backup, journal);
            Files.write(file.toPath(), new byte[]{1});
            try { WorkingLibraryCatalog.requireSwitchable(root); fail("Allowed switch with " + journal); }
            catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("pending")); }
            assertTrue(file.delete());
        }
        WorkingLibraryCatalog.requireSwitchable(root);
    }
}
