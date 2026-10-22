package com.chillednems.ikemenlab;

import org.junit.Test;
import static org.junit.Assert.*;

public final class SafSelectTargetNamespaceTest {
    @Test public void sharedCustomParentSeparatesBackupsByExactSourceDocument() {
        String a = SafSelectTarget.customNamespaceFor("content://provider/tree/A/document/A%3Adata%2Fselect.def");
        String b = SafSelectTarget.customNamespaceFor("content://provider/tree/B/document/B%3Adata%2Fselect.def");
        assertNotEquals(a, b);
        assertEquals(a, SafSelectTarget.customNamespaceFor("content://provider/tree/A/document/A%3Adata%2Fselect.def"));
        assertTrue(a.startsWith("ikemen-select-"));
    }

    @Test public void sourceBackupsForTwoSelectFilesInOneFolderUseDistinctFolders() {
        String global = "content://provider/tree/game/document/game%3Adata%2Fselect.def";
        String alternate = "content://provider/tree/game/document/game%3Adata%2Falternate.def";
        assertNull(SafSelectTarget.sourceBackupNamespaceFor("data/select.def", global));
        String alternateFolder = SafSelectTarget.sourceBackupNamespaceFor("data/alternate.def", alternate);
        assertNotNull(alternateFolder);
        assertEquals(alternateFolder, SafSelectTarget.sourceBackupNamespaceFor("data/alternate.def", alternate));
        assertNotEquals(alternateFolder,
                SafSelectTarget.sourceBackupNamespaceFor("data/other.def", "content://provider/tree/game/document/game%3Adata%2Fother.def"));
        assertEquals("Source data/select-backups", SafSelectTarget.sourceBackupLabelFor("data/select.def", null));
        assertEquals("Source data/select-backups/" + alternateFolder,
                SafSelectTarget.sourceBackupLabelFor("data/alternate.def", alternateFolder));
        assertEquals("Source data/qa_pack/select-backups/" + alternateFolder,
                SafSelectTarget.sourceBackupLabelFor("data/qa_pack/select.def", alternateFolder));
    }
}
