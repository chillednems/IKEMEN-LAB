package com.chillednems.ikemenlab;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Counts roster-reference changes against the exact destination preimage. */
final class RosterChangeSummary {
    final int enabled, disabled, added, removed, reorderedPositions;
    final boolean otherContentChanged;

    private RosterChangeSummary(int enabled, int disabled, int added, int removed,
                                int reorderedPositions, boolean otherContentChanged) {
        this.enabled = enabled; this.disabled = disabled; this.added = added; this.removed = removed;
        this.reorderedPositions = reorderedPositions; this.otherContentChanged = otherContentChanged;
    }

    static RosterChangeSummary compare(File root, byte[] before, byte[] after) throws IOException {
        return compare(LibraryFiles.local(root), before, after);
    }
    static RosterChangeSummary compare(LibraryFiles.Node root, byte[] before, byte[] after) throws IOException {
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
        int reordered = 0;
        boolean other = false;
        if (!Arrays.equals(before, after)) {
            try {
                RosterArrangement oldOrder = new RosterArrangement(before);
                RosterArrangement newOrder = new RosterArrangement(after);
                List<String> oldSlots = oldOrder.slotTexts(), newSlots = newOrder.slotTexts();
                if (sameMultiset(oldSlots, newSlots)) {
                    for (int i = 0; i < oldSlots.size(); i++)
                        if (!oldSlots.get(i).equals(newSlots.get(i))) reordered++;
                    other = !Arrays.equals(oldOrder.bytesIgnoringSlotText(), newOrder.bytesIgnoringSlotText());
                } else other = true;
            } catch (IOException previewLimit) { other = true; }
        }
        return new RosterChangeSummary(enabled, disabled, added, removed, reordered, other);
    }

    private static boolean sameMultiset(List<String> first, List<String> second) {
        if (first.size() != second.size()) return false;
        Map<String, Integer> counts = new HashMap<>();
        for (String value : first) counts.merge(value, 1, Integer::sum);
        for (String value : second) {
            Integer count = counts.get(value);
            if (count == null) return false;
            if (count == 1) counts.remove(value);
            else counts.put(value, count - 1);
        }
        return counts.isEmpty();
    }

    private static Map<String, Boolean> entries(LibraryFiles.Node root, byte[] bytes) throws IOException {
        Map<String, Boolean> result = new HashMap<>();
        for (RosterDiagnostics.Entry entry : RosterDiagnostics.entries(root, bytes))
            result.merge(entry.section + "|" + entry.rawReference.toLowerCase(Locale.ROOT),
                    entry.active, (earlier, later) -> earlier || later);
        return result;
    }

    String describe() {
        return "Enabled " + enabled + ", disabled " + disabled + ", added " + added + ", removed " + removed
                + ", reordered positions " + reorderedPositions + ", other content changed " + otherContentChanged
                + ". Added/removed count references absent/present in the destination; enabled/disabled count state flips of existing references.";
    }
}
