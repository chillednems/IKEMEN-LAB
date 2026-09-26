package com.chillednems.ikemenlab;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Counts roster-reference changes against the exact destination preimage. */
final class RosterChangeSummary {
    final int enabled, disabled, added, removed;

    private RosterChangeSummary(int enabled, int disabled, int added, int removed) {
        this.enabled = enabled; this.disabled = disabled; this.added = added; this.removed = removed;
    }

    static RosterChangeSummary compare(File root, byte[] before, byte[] after) throws IOException {
        Map<String, Boolean> oldEntries = entries(root, before);
        Map<String, Boolean> newEntries = entries(root, after);
        int enabled = 0, disabled = 0, added = 0, removed = 0;
        for (Map.Entry<String, Boolean> entry : newEntries.entrySet()) {
            Boolean previous = oldEntries.get(entry.getKey());
            if (previous == null) added++;
            else if (!previous && entry.getValue()) enabled++;
            else if (previous && !entry.getValue()) disabled++;
        }
        for (String key : oldEntries.keySet()) if (!newEntries.containsKey(key)) removed++;
        return new RosterChangeSummary(enabled, disabled, added, removed);
    }

    private static Map<String, Boolean> entries(File root, byte[] bytes) throws IOException {
        Map<String, Boolean> result = new HashMap<>();
        for (RosterDiagnostics.Entry entry : RosterDiagnostics.entries(root, bytes))
            result.merge(entry.section + "|" + entry.rawReference.toLowerCase(Locale.ROOT),
                    entry.active, (earlier, later) -> earlier || later);
        return result;
    }

    String describe() {
        return "Enabled " + enabled + ", disabled " + disabled + ", added " + added + ", removed " + removed
                + ". Added/removed count references absent/present in the destination; enabled/disabled count state flips of existing references.";
    }
}
