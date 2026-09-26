package com.chillednems.ikemenlab;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Per-working-library choice for verified external select.def preimages. */
final class BackupPolicy {
    enum Kind { SOURCE, APP, CUSTOM }
    private static final String PREFS = "backup_destinations";
    final File library;
    final Kind kind;
    final Uri customTree;
    final String customName;
    final long generation;
    final List<Uri> knownCustomTrees;
    final Map<String, String> knownCustomNames;

    private BackupPolicy(File library, Kind kind, Uri customTree, String customName,
                         long generation, List<Uri> knownCustomTrees, Map<String, String> knownCustomNames) {
        this.library = library; this.kind = kind; this.customTree = customTree;
        this.customName = customName; this.generation = generation;
        this.knownCustomTrees = knownCustomTrees;
        this.knownCustomNames = knownCustomNames;
    }
    static BackupPolicy load(Context context, File library) throws IOException {
        String prefix = prefix(library);
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Kind kind;
        try { kind = Kind.valueOf(p.getString(prefix + ".kind", Kind.SOURCE.name())); }
        catch (IllegalArgumentException invalid) { throw new IOException("Invalid saved backup destination", invalid); }
        String uri = p.getString(prefix + ".custom", null);
        List<Uri> history = new ArrayList<>();
        Map<String, String> names = new LinkedHashMap<>();
        int count = Math.min(20, Math.max(0, p.getInt(prefix + ".known_count", 0)));
        for (int i = 0; i < count; i++) {
            String saved = p.getString(prefix + ".known_" + i, null);
            if (saved != null) {
                history.add(Uri.parse(saved));
                names.put(saved, p.getString(prefix + ".known_name_" + i, "Custom folder"));
            }
        }
        return new BackupPolicy(library, kind, uri == null ? null : Uri.parse(uri),
                p.getString(prefix + ".custom_name", "Custom folder"),
                p.getLong(prefix + ".generation", 0), history, names);
    }
    static BackupPolicy choose(Context context, File library, Kind kind, Uri customTree, String customName) throws IOException {
        if (kind == Kind.CUSTOM && (customTree == null || customName == null || customName.trim().isEmpty()))
            throw new IOException("Choose a named custom backup folder");
        BackupPolicy prior = load(context, library);
        List<Uri> history = new ArrayList<>(prior.knownCustomTrees);
        Map<String, String> names = new LinkedHashMap<>(prior.knownCustomNames);
        if (kind == Kind.CUSTOM) {
            history.remove(customTree);
            history.add(0, customTree);
            names.put(customTree.toString(), customName.trim());
            if (history.size() > 20) history = new ArrayList<>(history.subList(0, 20));
        }
        String prefix = prefix(library);
        SharedPreferences.Editor edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(prefix + ".kind", kind.name())
                .putLong(prefix + ".generation", prior.generation + 1)
                .putInt(prefix + ".known_count", history.size());
        if (kind == Kind.CUSTOM) edit.putString(prefix + ".custom", customTree.toString())
                .putString(prefix + ".custom_name", customName.trim());
        else if (prior.customTree != null) edit.putString(prefix + ".custom", prior.customTree.toString())
                .putString(prefix + ".custom_name", prior.customName);
        for (int i = 0; i < history.size(); i++) edit.putString(prefix + ".known_" + i, history.get(i).toString())
                .putString(prefix + ".known_name_" + i,
                        names.getOrDefault(history.get(i).toString(), "Custom folder"));
        if (!edit.commit()) throw new IOException("Could not save backup destination");
        return load(context, library);
    }
    void requireCurrent(Context context) throws IOException {
        BackupPolicy now = load(context, library);
        if (now.generation != generation || now.kind != kind
                || (kind == Kind.CUSTOM && !customTree.equals(now.customTree)))
            throw new IOException("Backup destination changed; review this operation again");
    }
    String displayName() {
        switch (kind) {
            case APP: return "App backup directory";
            case CUSTOM: return customName;
            default: return "Source data/select-backups";
        }
    }
    String displayNameFor(Uri tree) {
        return knownCustomNames.getOrDefault(tree.toString(), "Custom folder");
    }
    private static String prefix(File library) throws IOException {
        return "policy." + SelectStorage.hash(library.getCanonicalPath().getBytes(StandardCharsets.UTF_8));
    }
}
