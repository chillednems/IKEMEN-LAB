package com.chillednems.ikemenlab;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Reads a copied IKEMEN directory without changing its content. */
public final class LibraryScanner {
    public static final class Item {
        public final String kind;
        public final String reference;
        public final String name;
        public final String author;
        public final String file;
        public final Boolean enabled;
        Item(String kind, String reference, String name, String author, String file, Boolean enabled) {
            this.kind = kind; this.reference = reference; this.name = name; this.author = author; this.file = file; this.enabled = enabled;
        }
    }

    public static final class Catalog {
        public final List<Item> characters = new ArrayList<>();
        public final List<Item> stages = new ArrayList<>();
    }

    private LibraryScanner() {}

    public static String readText(File file) throws IOException {
        if (file.length() > 2 * 1024 * 1024) throw new IOException("Metadata file is too large: " + file.getName());
        byte[] bytes = Files.readAllBytes(file.toPath());
        for (String encoding : new String[] {"UTF-8", "windows-1252", "Shift_JIS", "ISO-8859-1"}) {
            try {
                String decoded = Charset.forName(encoding).newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
                return decoded.startsWith("\ufeff") ? decoded.substring(1) : decoded;
            } catch (CharacterCodingException ignored) { }
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static File[] sorted(File folder) {
        File[] files = folder.listFiles();
        if (files == null) return new File[0];
        java.util.Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        return files;
    }

    private static File childIgnoreCase(File folder, String name) {
        for (File child : sorted(folder)) if (child.getName().equalsIgnoreCase(name)) return child;
        return new File(folder, name);
    }

    public static Catalog scan(File root) throws IOException {
        File chars = childIgnoreCase(root, "chars");
        File stages = childIgnoreCase(root, "stages");
        if (!chars.isDirectory() || !stages.isDirectory())
            throw new IOException("Choose a folder with chars and stages directories");
        File selectFile = childIgnoreCase(childIgnoreCase(root, "data"), "select.def");
        SelectDefEditor roster = new SelectDefEditor(selectFile.isFile() ? readText(selectFile) : "");
        Catalog result = new Catalog();
        for (File character : sorted(chars)) {
            if (!character.isDirectory()) continue;
            File chosen = null;
            for (File file : sorted(character)) {
                if (!file.isFile() || !file.getName().toLowerCase(Locale.ROOT).endsWith(".def")) continue;
                if (chosen == null || file.getName().equalsIgnoreCase(character.getName() + ".def")) chosen = file;
            }
            if (chosen == null) continue;
            Map<String, Map<String, String>> def = DefParser.parse(readText(chosen));
            String ref = character.getName() + "/" + chosen.getName();
            String name = DefParser.value(def, "info", "displayname", DefParser.value(def, "info", "name", character.getName()));
            result.characters.add(new Item("characters", ref, name, DefParser.value(def, "info", "author", "Unknown"), chosen.getAbsolutePath(), roster.isEnabled("characters", ref)));
        }
        scanStages(stages, stages, roster, result.stages, 0);
        return result;
    }

    private static void scanStages(File root, File folder, SelectDefEditor roster, List<Item> items, int depth) throws IOException {
        if (depth > ImportLimits.MAX_DEPTH) return;
        for (File file : sorted(folder)) {
            if (file.isDirectory()) { scanStages(root, file, roster, items, depth + 1); continue; }
            if (!file.isFile() || !file.getName().toLowerCase(Locale.ROOT).endsWith(".def")) continue;
            String ref = "stages/" + root.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/');
            Map<String, Map<String, String>> def = DefParser.parse(readText(file));
            String name = DefParser.value(def, "info", "name", file.getName().substring(0, file.getName().length() - 4));
            items.add(new Item("extrastages", ref, name, DefParser.value(def, "info", "author", "Unknown"), file.getAbsolutePath(), roster.isEnabled("extrastages", ref)));
        }
    }
}
