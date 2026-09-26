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

    private LibraryBinding(File workingRoot, Uri sourceTree, int grantedFlags, long generation) {
        this.workingRoot = workingRoot; this.sourceTree = sourceTree;
        this.grantedFlags = grantedFlags; this.generation = generation;
    }
    public static LibraryBinding load(Context context, File workingRoot) throws IOException {
        String key = key(workingRoot);
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String value = prefs.getString(key + ".tree", null);
        return new LibraryBinding(workingRoot, value == null ? null : Uri.parse(value),
                prefs.getInt(key + ".flags", 0), prefs.getLong(key + ".generation", 0));
    }
    /** Call only after the source scan and private working-roster initialization succeed. */
    public static LibraryBinding bindSource(Context context, File workingRoot, Uri sourceTree, int grantedFlags) throws IOException {
        if (sourceTree == null) throw new IOException("Source tree is missing");
        String key = key(workingRoot);
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long generation = prefs.getLong(key + ".generation", 0) + 1;
        if (!prefs.edit().putString(key + ".tree", sourceTree.toString())
                .putInt(key + ".flags", grantedFlags).putLong(key + ".generation", generation).commit())
            throw new IOException("Could not save source folder binding");
        return new LibraryBinding(workingRoot, sourceTree, grantedFlags, generation);
    }
    public static LibraryBinding localOnly(Context context, File workingRoot) throws IOException {
        String key = key(workingRoot);
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long generation = prefs.getLong(key + ".generation", 0) + 1;
        if (!prefs.edit().remove(key + ".tree").remove(key + ".flags")
                .putLong(key + ".generation", generation).commit())
            throw new IOException("Could not save local library binding");
        return new LibraryBinding(workingRoot, null, 0, generation);
    }
    static boolean acceptsReconnect(String rememberedTree, String selectedTree) {
        return selectedTree != null && (rememberedTree == null || rememberedTree.equals(selectedTree));
    }
    public void requireCurrent(Context context) throws IOException {
        LibraryBinding current = load(context, workingRoot);
        if (current.generation != generation ||
                (current.sourceTree == null ? sourceTree != null : !current.sourceTree.equals(sourceTree)))
            throw new IOException("Library source changed; refresh the operation");
    }
    private static String key(File root) throws IOException {
        // Working roots are app-private UUID directories. The full canonical path prevents cross-library reuse.
        return "source." + SelectStorage.hash(root.getCanonicalPath().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
