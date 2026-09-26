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
        public final String warning;
        public final String previewFile;
        public final int previewGroup, previewImage;
        Item(String kind, String reference, String name, String author, String file, Boolean enabled) {
            this(kind, reference, name, author, file, enabled, null, 0, 0);
        }
        Item(String kind, String reference, String name, String author, String file, Boolean enabled,
             String previewFile, int previewGroup, int previewImage) {
            this(kind, reference, name, author, file, enabled, previewFile, previewGroup, previewImage, null);
        }
        Item(String kind, String reference, String name, String author, String file, Boolean enabled,
             String previewFile, int previewGroup, int previewImage, String warning) {
            this.kind = kind; this.reference = reference; this.name = name; this.author = author; this.file = file; this.enabled = enabled;
            this.previewFile = previewFile; this.previewGroup = previewGroup; this.previewImage = previewImage;
            this.warning = warning;
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
        String utf8 = decode(bytes, StandardCharsets.UTF_8);
        if (utf8 != null) return utf8.startsWith("\ufeff") ? utf8.substring(1) : utf8;
        String shiftJis = decode(bytes, Charset.forName("Shift_JIS"));
        // CP1252 accepts almost every byte sequence, including Shift_JIS bytes.
        // Prefer strict Shift_JIS only with multiple Japanese script characters.
        if (shiftJis != null && japaneseCharacters(shiftJis) >= 2) return shiftJis;
        String western = decode(bytes, Charset.forName("windows-1252"));
        return western != null ? western : new String(bytes, StandardCharsets.ISO_8859_1);
    }

    private static String decode(byte[] bytes, Charset charset) {
        try {
            return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalid) { return null; }
    }

    private static int japaneseCharacters(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            if ((value >= '\u3040' && value <= '\u30ff') || (value >= '\u4e00' && value <= '\u9fff')) count++;
        }
        return count;
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

    private static String artwork(File root, File defFile, String declared) throws IOException {
        if (declared != null) {
            String path = declared.trim().replace('\\', '/').replace("\"", "");
            if (!path.isEmpty() && !path.startsWith("/") && !path.matches("(?i)^[a-z]:.*")) {
                boolean safe = true;
                for (String segment : path.split("/")) if (segment.equals("..") || segment.equals(".")) safe = false;
                if (safe) {
                    for (File base : new File[] {defFile.getParentFile(), root}) {
                        File resolved = base;
                        for (String segment : path.split("/")) resolved = childIgnoreCase(resolved, segment);
                        if (resolved.isFile() && resolved.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator))
                            return resolved.getAbsolutePath();
                    }
                }
            }
        }
        String baseName = defFile.getName().replaceFirst("(?i)\\.def$", "");
        for (String fallback : new String[] {baseName + ".sff", "portrait.png", "preview.png"}) {
            File file = childIgnoreCase(defFile.getParentFile(), fallback);
            if (file.isFile()) return file.getAbsolutePath();
        }
        return null;
    }

    private static int[] stageSprite(Map<String, Map<String, String>> def) {
        for (Map.Entry<String, Map<String, String>> section : def.entrySet()) {
            if (!section.getKey().startsWith("bg") || section.getKey().equals("bgdef")) continue;
            String value = section.getValue().get("spriteno");
            if (value == null) continue;
            String[] parts = value.split(",");
            if (parts.length < 2) continue;
            try {
                int group = Integer.parseInt(parts[0].trim()), image = Integer.parseInt(parts[1].trim());
                if (group >= 0 && group <= 65535 && image >= 0 && image <= 65535) return new int[] {group, image};
            } catch (NumberFormatException ignored) { }
        }
        return new int[] {0, 0};
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
            String sprite = DefParser.value(def, "files", "sprite", DefParser.value(def, "files", "spr", null));
            result.characters.add(new Item("characters", ref, name, DefParser.value(def, "info", "author", "Unknown"), chosen.getAbsolutePath(), roster.isEnabled("characters", ref),
                    artwork(root, chosen, sprite), 9000, 1));
        }
        scanStages(root, stages, stages, roster, result.stages, 0);
        if (selectFile.isFile()) {
            for (RosterDiagnostics.Entry entry : RosterDiagnostics.entries(root, Files.readAllBytes(selectFile.toPath()))) {
                List<Item> destination = entry.section.equals("characters") ? result.characters : result.stages;
                boolean listed = false;
                for (Item item : destination) if (item.reference.equalsIgnoreCase(entry.rawReference)
                        || (entry.section.equals("extrastages") && item.reference.equalsIgnoreCase("stages/" + entry.rawReference))) {
                    listed = true; break;
                }
                if (listed) continue;
                if (entry.warning != null) {
                    destination.add(new Item(entry.section, entry.rawReference, entry.rawReference, "Unavailable",
                            null, entry.active, null, 0, 0, entry.warning));
                } else if (entry.section.equals("characters") && entry.resolvedFile != null
                        && entry.resolvedFile.isFile() && entry.resolvedFile.getName().toLowerCase(Locale.ROOT).endsWith(".def")) {
                    File defFile = entry.resolvedFile;
                    Map<String, Map<String, String>> def = DefParser.parse(readText(defFile));
                    String name = DefParser.value(def, "info", "displayname",
                            DefParser.value(def, "info", "name", defFile.getName()));
                    String sprite = DefParser.value(def, "files", "sprite", DefParser.value(def, "files", "spr", null));
                    destination.add(new Item("characters", entry.rawReference, name,
                            DefParser.value(def, "info", "author", "Unknown"), defFile.getAbsolutePath(), entry.active,
                            artwork(root, defFile, sprite), 9000, 1));
                }
            }
        }
        return result;
    }

    private static void scanStages(File libraryRoot, File root, File folder, SelectDefEditor roster, List<Item> items, int depth) throws IOException {
        if (depth > ImportLimits.MAX_DEPTH) return;
        for (File file : sorted(folder)) {
            if (file.isDirectory()) { scanStages(libraryRoot, root, file, roster, items, depth + 1); continue; }
            if (!file.isFile() || !file.getName().toLowerCase(Locale.ROOT).endsWith(".def")) continue;
            String ref = "stages/" + root.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/');
            Map<String, Map<String, String>> def = DefParser.parse(readText(file));
            String name = DefParser.value(def, "info", "name", file.getName().substring(0, file.getName().length() - 4));
            int[] sprite = stageSprite(def);
            String spritePath = DefParser.value(def, "bgdef", "spr", DefParser.value(def, "files", "spr", null));
            items.add(new Item("extrastages", ref, name, DefParser.value(def, "info", "author", "Unknown"), file.getAbsolutePath(), roster.isEnabled("extrastages", ref),
                    artwork(libraryRoot, file, spritePath), sprite[0], sprite[1]));
        }
    }
}
