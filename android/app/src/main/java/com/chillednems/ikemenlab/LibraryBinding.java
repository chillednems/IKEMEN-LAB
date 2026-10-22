package com.chillednems.ikemenlab;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import java.io.File;
import java.io.IOException;

/** Maps one small private working roster and its backups to a selected source folder. */
public final class LibraryBinding {
    private static final String PREFS = "library_sources";
    public final File workingRoot;
    public final Uri sourceTree;
    public final int grantedFlags;
    public final long generation;
    public final String displayName;
    public final String selectPath;

    private LibraryBinding(File workingRoot, Uri sourceTree, int grantedFlags, long generation, String displayName, String selectPath) {
        this.workingRoot = workingRoot; this.sourceTree = sourceTree;
        this.grantedFlags = grantedFlags; this.generation = generation; this.displayName = displayName;
        this.selectPath = selectPath;
    }
    public static LibraryBinding load(Context context, File workingRoot) throws IOException {
        String key = key(workingRoot);
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String value = prefs.getString(key + ".tree", null);
        return new LibraryBinding(workingRoot, value == null ? null : Uri.parse(value),
                prefs.getInt(key + ".flags", 0), prefs.getLong(key + ".generation", 0),
                prefs.getString(key + ".name", null), prefs.getString(key + ".select", "data/select.def"));
    }
    /** Call only after the source scan and private working-roster initialization succeed. */
    public static LibraryBinding bindSource(Context context, File workingRoot, Uri sourceTree, int grantedFlags) throws IOException {
        return bindSource(context, workingRoot, sourceTree, grantedFlags, null);
    }
    public static LibraryBinding bindSource(Context context, File workingRoot, Uri sourceTree,
                                            int grantedFlags, String displayName) throws IOException {
        return bindSource(context, workingRoot, sourceTree, grantedFlags, displayName, "data/select.def");
    }
    public static LibraryBinding bindSource(Context context, File workingRoot, Uri sourceTree,
                                            int grantedFlags, String displayName, String selectPath) throws IOException {
        if (sourceTree == null) throw new IOException("Source tree is missing");
        if (selectPath == null || selectPath.isEmpty()) throw new IOException("Active select target is unknown");
        String key = key(workingRoot);
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long generation = prefs.getLong(key + ".generation", 0) + 1;
        if (!prefs.edit().putString(key + ".tree", sourceTree.toString())
                .putInt(key + ".flags", grantedFlags).putLong(key + ".generation", generation)
                .putString(key + ".name", displayName).putString(key + ".select", selectPath).commit())
            throw new IOException("Could not save source folder binding");
        return new LibraryBinding(workingRoot, sourceTree, grantedFlags, generation, displayName, selectPath);
    }
    public static LibraryBinding localOnly(Context context, File workingRoot) throws IOException {
        String key = key(workingRoot);
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long generation = prefs.getLong(key + ".generation", 0) + 1;
        if (!prefs.edit().remove(key + ".tree").remove(key + ".flags").remove(key + ".name").remove(key + ".select")
                .putLong(key + ".generation", generation).commit())
            throw new IOException("Could not save local library binding");
        return new LibraryBinding(workingRoot, null, 0, generation, null, "data/select.def");
    }
    static boolean acceptsReconnect(String rememberedTree, String selectedTree) {
        return selectedTree != null && (rememberedTree == null || rememberedTree.equals(selectedTree));
    }
    public void requireCurrent(Context context) throws IOException {
        LibraryBinding current = load(context, workingRoot);
        if (current.generation != generation ||
                (current.sourceTree == null ? sourceTree != null : !current.sourceTree.equals(sourceTree))
                || !current.selectPath.equals(selectPath))
            throw new IOException("Library source changed; refresh the operation");
    }
    public void requireActiveTarget(ScreenpackStatus status) throws IOException {
        if (status == null || !status.select.equalsIgnoreCase(selectPath)
                || (!status.globalRoster && !status.knownAlternate))
            throw new IOException("Active screenpack roster changed or is unavailable. Relink the source folder; the private working roster is preserved.");
    }
    private static String key(File root) throws IOException {
        // Working roots are app-private UUID directories. The full canonical path prevents cross-library reuse.
        return "source." + SelectStorage.hash(root.getCanonicalPath().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
