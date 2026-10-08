package com.chillednems.ikemenlab;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Dynamic rules over fields the Android scanner and private tag store actually provide. */
final class SmartCollectionRules {
    private SmartCollectionRules() { }

    static void validate(CollectionStore.Rule rule) throws IOException {
        if (rule == null || rule.field == null || rule.value == null || rule.value.trim().isEmpty()
                || rule.value.length() > 60 || rule.value.indexOf('\r') >= 0 || rule.value.indexOf('\n') >= 0)
            throw new IOException("Smart rule value is invalid");
        switch (rule.field) {
            case "name": case "author": case "manualTag": case "inferredTag": break;
            case "type":
                if (!rule.value.equals("Characters") && !rule.value.equals("Stages")) throw new IOException("Unsupported type rule");
                break;
            case "status":
                if (!rule.value.equals("Enabled") && !rule.value.equals("Disabled")
                        && !rule.value.equals("Unlisted"))
                    throw new IOException("Unsupported status rule");
                break;
            default: throw new IOException("Unsupported smart rule field");
        }
    }

    static RosterProfile.Snapshot evaluate(CollectionStore.Record collection, LibraryScanner.Catalog catalog,
                                           TagStore tags) throws IOException {
        if (!collection.smart || collection.rules.isEmpty() || collection.rules.size() > 6)
            throw new IOException("Smart collection has no supported rules");
        List<String> characters = new ArrayList<>(), stages = new ArrayList<>();
        for (LibraryScanner.Item item : catalog.characters) if (item.warning == null && item.defNode != null
                && matches(collection, item, tags)) {
            if (characters.size() >= RosterProfile.MAX_CHARACTERS) throw new IOException("Smart character matches exceed limit");
            characters.add(item.reference);
        }
        for (LibraryScanner.Item item : catalog.stages) if (item.warning == null && item.defNode != null
                && matches(collection, item, tags)) {
            if (stages.size() >= RosterProfile.MAX_STAGES) throw new IOException("Smart stage matches exceed limit");
            stages.add(item.reference);
        }
        RosterProfile.Snapshot result = new RosterProfile.Snapshot(characters, stages);
        RosterProfile.validate(result);
        return result;
    }

    private static boolean matches(CollectionStore.Record collection, LibraryScanner.Item item, TagStore tags) throws IOException {
        boolean any = false;
        for (CollectionStore.Rule rule : collection.rules) {
            validate(rule);
            boolean matched = matches(rule, item, tags);
            if (collection.allRules && !matched) return false;
            any |= matched;
        }
        return collection.allRules || any;
    }

    private static boolean matches(CollectionStore.Rule rule, LibraryScanner.Item item, TagStore tags) {
        String sought = rule.value.toLowerCase(Locale.ROOT);
        switch (rule.field) {
            case "name": return item.name.toLowerCase(Locale.ROOT).contains(sought);
            case "author": return item.author.toLowerCase(Locale.ROOT).contains(sought);
            case "type": return rule.value.equals("Characters") == item.kind.equals("characters");
            case "status":
                if (rule.value.equals("Enabled")) return Boolean.TRUE.equals(item.enabled);
                if (rule.value.equals("Disabled")) return Boolean.FALSE.equals(item.enabled);
                return item.enabled == null;
            case "manualTag":
                for (String tag : tags.get(item.kind + "|" + item.reference)) if (tag.equalsIgnoreCase(rule.value)) return true;
                return false;
            case "inferredTag":
                for (String tag : TagInference.from(item.reference, item.name, item.author))
                    if (tag.equalsIgnoreCase(rule.value)) return true;
                return false;
            default: return false;
        }
    }
}
