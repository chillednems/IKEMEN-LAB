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
}
