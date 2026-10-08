package com.chillednems.ikemenlab;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Read-only description of the source game's active select screen. */
final class ScreenpackStatus {
    final String motif, select, warning;
    final int rows, columns;
    final boolean globalRoster;
    final boolean knownAlternate;

    private ScreenpackStatus(String motif, String select, String warning, int rows, int columns, boolean globalRoster) {
        this.motif = motif; this.select = select; this.warning = warning;
        this.rows = rows; this.columns = columns; this.globalRoster = globalRoster;
        this.knownAlternate = !globalRoster && select != null && !select.equals("Unknown")
                && !select.equalsIgnoreCase("data/select.def");
    }

    int capacity() { return rows > 0 && columns > 0 ? rows * columns : 0; }

    static ScreenpackStatus inspect(LibraryFiles.Node root) throws IOException {
        LibraryFiles.Node config = safeResolve(root, "save/config.ini");
        String motif = "data/system.def";
        if (config != null) {
            String configured = motifValue(new String(LibraryFiles.readLimited(config, 1024 * 1024), StandardCharsets.ISO_8859_1));
            if (!configured.isEmpty()) motif = configured;
        }
        List<String> motifParts = normalize(motif, new ArrayList<>());
        if (motifParts == null) return new ScreenpackStatus(motif, "Unknown", "Motif path is unsafe or unsupported.", 0, 0, false);
        LibraryFiles.Node system = safeResolve(root, join(motifParts));
        if (system == null) return new ScreenpackStatus(motif, "Unknown", "Active system.def is missing; roster target and capacity are unknown.", 0, 0, false);
        Map<String, Map<String, String>> parsed = parse(system);
        int rows = dimension(DefParser.value(parsed, "select info", "rows", ""));
        int cols = dimension(DefParser.value(parsed, "select info", "columns", ""));
        String selected = DefParser.value(parsed, "files", "select", "").trim();
        if (selected.isEmpty()) selected = "select.def";
        List<String> folder = selected.replace('\\', '/').toLowerCase(Locale.ROOT).startsWith("data/")
                ? new ArrayList<>() : new ArrayList<>(motifParts.subList(0, motifParts.size() - 1));
        List<String> targetParts = normalize(selected, folder);
        if (targetParts == null) return new ScreenpackStatus(motif, "Unknown", "Screenpack select path is unsafe or unsupported.", rows, cols, false);
        String target = join(targetParts);
        LibraryFiles.Node select = safeResolve(root, target);
        if (select == null) return new ScreenpackStatus(motif, target, "Screenpack select.def is missing. This working roster may not be used by the game.", rows, cols, false);
        boolean global = target.equalsIgnoreCase("data/select.def");
        return new ScreenpackStatus(motif, target, global ? null : "Active screenpack uses another select.def. Roster arrangement and linked export are unavailable for this source.", rows, cols, global);
    }

    private static Map<String, Map<String, String>> parse(LibraryFiles.Node file) throws IOException {
        return DefParser.parse(new String(LibraryFiles.readLimited(file, 1024 * 1024), StandardCharsets.ISO_8859_1));
    }

    private static String motifValue(String ini) {
        Map<String, Map<String, String>> sections = DefParser.parse(ini);
        String configured = DefParser.value(sections, "config", "motif", "").trim();
        if (!configured.isEmpty()) return configured;
        configured = DefParser.value(sections, "", "motif", "").trim();
        return configured;
    }

    private static LibraryFiles.Node safeResolve(LibraryFiles.Node root, String path) throws IOException {
        return LibraryFiles.resolve(root, path);
    }

    private static int dimension(String raw) {
        try {
            int value = Integer.parseInt(raw.trim());
            return value >= 1 && value <= 1000 ? value : 0;
        } catch (NumberFormatException invalid) { return 0; }
    }

    private static List<String> normalize(String raw, List<String> base) {
        if (raw.startsWith("/") || raw.matches("(?i)^[a-z]:.*") || raw.indexOf(':') >= 0 || raw.indexOf('\0') >= 0) return null;
        List<String> parts = new ArrayList<>(base);
        for (String part : raw.replace('\\', '/').split("/", -1)) {
            if (part.isEmpty()) return null;
            if (part.equals(".")) continue;
            if (part.equals("..")) { if (parts.isEmpty()) return null; parts.remove(parts.size() - 1); }
            else parts.add(part);
        }
        return parts.isEmpty() ? null : parts;
    }

    private static String join(List<String> parts) { return String.join("/", parts); }
}
