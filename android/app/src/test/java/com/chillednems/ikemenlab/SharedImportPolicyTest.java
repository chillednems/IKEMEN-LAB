package com.chillednems.ikemenlab;

import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.*;

public final class SharedImportPolicyTest {
    @Test public void acceptsOneGrantedContentDocumentOrOneHttpsLink() throws Exception {
        SharedImportPolicy.Request file = SharedImportPolicy.accept("android.intent.action.SEND",
                "application/zip", "content://fixture.provider/document/zip", null, true);
        assertFalse(file.link);
        SharedImportPolicy.Request link = SharedImportPolicy.accept("android.intent.action.SEND",
                "text/plain", null, "https://example.org/addon.zip", false);
        assertTrue(link.link);
    }

    @Test public void rejectsUntrustedSchemesMissingGrantAndAmbiguousShare() throws Exception {
        reject("application/zip", "file:///tmp/addon.zip", null, true);
        reject("application/zip", "content://fixture.provider/document/zip", null, false);
        reject("application/zip", "content://fixture.provider/document/zip", "https://example.org/a.zip", true);
        reject("text/plain", null, "http://example.org/a.zip", false);
        reject("text/plain", null, "https://user:secret@example.org/a.zip", false);
        reject("text/plain", null, "https://localhost/a.zip", false);
        reject("text/plain", null, "https://127.0.0.1/a.zip", false);
        reject("text/plain", null, "https://example.org/a.zip trailing", false);
    }

    private void reject(String mime, String stream, String text, boolean grant) throws Exception {
        try {
            SharedImportPolicy.accept("android.intent.action.SEND", mime, stream, text, grant);
            fail("Unsafe share accepted");
        } catch (IOException expected) { assertNotNull(expected.getMessage()); }
    }
}
