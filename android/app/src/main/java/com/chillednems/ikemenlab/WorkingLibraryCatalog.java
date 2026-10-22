package com.chillednems.ikemenlab;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** App-private working rosters that can be reopened without copying source game files. */
final class WorkingLibraryCatalog {
    private WorkingLibraryCatalog() {}

    static List<File> list(File parent) {
        File[] children = parent.listFiles();
        List<File> result = new ArrayList<>();
        if (children == null) return result;
        for (File child : children)
            if (child.getName().matches("[0-9a-fA-F-]{36}") && child.isDirectory()
                    && RosterStore.selectFile(child).isFile()) result.add(child);
        result.sort(Comparator.comparingLong(File::lastModified).reversed());
        return result;
    }

    static boolean hasPending(File root) {
        File folder = new File(RosterStore.selectFile(root).getParentFile(), "select-backups");
        return new File(folder, "pending-export.properties").isFile()
                || new File(folder, "pending-restore.properties").isFile();
    }

    static void requireSwitchable(File current) throws IOException {
        if (current != null && hasPending(current))
            throw new IOException("Finish the current library's pending source operation before switching");
    }
}
