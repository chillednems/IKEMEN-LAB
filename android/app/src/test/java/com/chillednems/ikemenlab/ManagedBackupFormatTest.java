package com.chillednems.ikemenlab;

import org.junit.Test;
import java.io.IOException;
import java.util.Properties;
import static org.junit.Assert.*;

public final class ManagedBackupFormatTest {
    @Test public void rejectsProviderAppendedExtensionBeforeSourceReplacement() throws Exception {
        String requested = "select.def.backup.11111111-1111-1111-1111-111111111111.properties";
        ManagedBackupFormat.requireExactName(requested, requested);
        try {
            ManagedBackupFormat.requireExactName(requested, requested + ".txt");
            fail("provider rename accepted");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains(".txt"));
        }
    }
    @Test public void foreignValidHashPairIsNotManagedSourceVersion() {
        String id = "11111111-1111-1111-1111-111111111111";
        byte[] bytes = "original".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Properties foreign = new Properties();
        foreign.setProperty("sha256", SelectStorage.hash(bytes));
        foreign.setProperty("bytes", Integer.toString(bytes.length));
        foreign.setProperty("createdAt", "1");
        foreign.setProperty("origin", "source");
        assertFalse(ManagedBackupFormat.validSource(id, foreign, bytes));
        ManagedBackupFormat.mark(foreign, id, "source");
        assertTrue(ManagedBackupFormat.validSource(id, foreign, bytes));
        foreign.setProperty("id", "different");
        assertFalse(ManagedBackupFormat.validSource(id, foreign, bytes));
    }
}
