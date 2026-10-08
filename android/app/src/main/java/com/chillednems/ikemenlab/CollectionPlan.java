package com.chillednems.ikemenlab;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Exact local activation preview bound to a working roster preimage. */
final class CollectionPlan {
    final String workingSha;
    final byte[] replacement;
    final RosterProfile.Snapshot entries;
    final List<String> missing;
    final boolean changed;

    private CollectionPlan(String workingSha, byte[] replacement, RosterProfile.Snapshot entries, List<String> missing,
                           boolean changed) {
        this.workingSha = workingSha; this.replacement = replacement;
        this.entries = entries; this.missing = missing; this.changed = changed;
    }

    static void requireActiveWorkingRoster(LibraryFiles.Node source) throws IOException {
        ScreenpackStatus active = ScreenpackStatus.inspect(source);
        if (active.knownAlternate)
            throw new IOException("Active screenpack uses " + active.select
                    + "; this collection would change a roster the game does not use");
    }

    static CollectionPlan prepare(byte[] working, CollectionStore.Record collection,
                                  LibraryScanner.Catalog catalog, TagStore tags) throws IOException {
        RosterProfile.Snapshot entries = collection.smart
                ? SmartCollectionRules.evaluate(collection, catalog, tags) : collection.snapshot();
        RosterProfile profile = new RosterProfile(working);
        byte[] replacement = profile.activate(entries);
        List<String> missing = new ArrayList<>();
        for (String line : entries.characters) {
            String ref = RosterProfile.reference("characters", line);
            if (ref.equalsIgnoreCase("empty") || ref.equalsIgnoreCase("randomselect")) continue;
            if (!found(catalog.characters, ref, true)) missing.add(ref);
        }
        for (String line : entries.stages) {
            String ref = RosterProfile.reference("extrastages", line);
            if (!found(catalog.stages, ref, false)) missing.add(ref);
        }
        return new CollectionPlan(SelectStorage.hash(working), replacement, entries, missing,
                !java.util.Arrays.equals(working, replacement));
    }

    private static boolean found(List<LibraryScanner.Item> items, String reference, boolean character) {
        String sought = reference.toLowerCase(Locale.ROOT);
        for (LibraryScanner.Item item : items) {
            if (item.warning != null || item.defNode == null) continue;
            String actual = item.reference.toLowerCase(Locale.ROOT);
            if (actual.equals(sought)) return true;
            if (character && !sought.contains("/") && actual.startsWith(sought + "/")) return true;
            if (!character && actual.startsWith("stages/") && actual.substring(7).equals(sought)) return true;
        }
        return false;
    }
}
