package com.chillednems.ikemenlab;

import java.io.IOException;
import java.util.Properties;

/** Identifies app-owned backup pairs before listing or deleting them. */
final class ManagedBackupFormat {
    static final String FORMAT = "ikemen-select-backup-v1";
    private ManagedBackupFormat() {}

    static void mark(Properties properties, String id, String origin) {
        properties.setProperty("format", FORMAT);
        properties.setProperty("id", id);
        properties.setProperty("origin", origin);
    }
    static boolean validLocal(String id, Properties properties, byte[] bytes) {
        return id != null && id.matches("[0-9]{13,}-[0-9a-fA-F-]{36}")
                && validCommon(id, properties, bytes)
                && ("working".equals(properties.getProperty("origin"))
                    || properties.getProperty("origin", "").startsWith("legacy:"));
    }
    static boolean validSource(String id, Properties properties, byte[] bytes) {
        return id != null && id.matches("[0-9a-fA-F-]{36}")
                && validCommon(id, properties, bytes)
                && "source".equals(properties.getProperty("origin"));
    }
    private static boolean validCommon(String id, Properties properties, byte[] bytes) {
        if (!FORMAT.equals(properties.getProperty("format")) || !id.equals(properties.getProperty("id"))
                || !SelectStorage.hash(bytes).equals(properties.getProperty("sha256"))
                || !Integer.toString(bytes.length).equals(properties.getProperty("bytes"))) return false;
        try { return Long.parseLong(properties.getProperty("createdAt", "")) > 0; }
        catch (NumberFormatException invalid) { return false; }
    }
    static void requireExactName(String requested, String actual) throws IOException {
        if (!requested.equals(actual))
            throw new IOException("Document provider renamed " + requested + " to " + actual);
    }
}
