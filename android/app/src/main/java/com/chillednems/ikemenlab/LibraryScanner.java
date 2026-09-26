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
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Reads library metadata directly from its source tree; only select.def is private working state. */
public final class LibraryScanner {
    public static final class Item {
        public final String kind, reference, name, author, file, previewFile, warning;
        public final Boolean enabled;
        public final int previewGroup, previewImage;
        final LibraryFiles.Node defNode, previewNode;

        Item(String kind, String reference, String name, String author, String file, Boolean enabled) {
            this(kind, reference, name, author, file, enabled, null, 0, 0, null);
        }
        Item(String kind, String reference, String name, String author, String file, Boolean enabled,
             String previewFile, int previewGroup, int previewImage) {
            this(kind, reference, name, author, file, enabled, previewFile, previewGroup, previewImage, null);
        }
        Item(String kind, String reference, String name, String author, String file, Boolean enabled,
             String previewFile, int previewGroup, int previewImage, String warning) {
            this(kind, reference, name, author, file, enabled, previewFile, previewGroup, previewImage,
                    warning, null, null);
        }
        Item(String kind, String reference, String name, String author, Boolean enabled,
             LibraryFiles.Node defNode, LibraryFiles.Node previewNode,
             int previewGroup, int previewImage, String warning) {
            this(kind, reference, name, author, defNode == null ? null : defNode.displayPath(), enabled,
                    previewNode == null ? null : previewNode.displayPath(), previewGroup, previewImage,
                    warning, defNode, previewNode);
        }
        private Item(String kind, String reference, String name, String author, String file, Boolean enabled,
                     String previewFile, int previewGroup, int previewImage, String warning,
                     LibraryFiles.Node defNode, LibraryFiles.Node previewNode) {
            this.kind = kind; this.reference = reference; this.name = name; this.author = author;
            this.file = file; this.enabled = enabled; this.previewFile = previewFile;
            this.previewGroup = previewGroup; this.previewImage = previewImage; this.warning = warning;
            this.defNode = defNode; this.previewNode = previewNode;
        }
    }

    public static final class Catalog {
        public final List<Item> characters = new ArrayList<>();
        public final List<Item> stages = new ArrayList<>();
    }

    private LibraryScanner() {}

    public static String readText(File file) throws IOException {
        if (file.length() > 2 * 1024 * 1024) throw new IOException("Metadata file is too large: " + file.getName());
        return readTextBytes(Files.readAllBytes(file.toPath()), file.getName());
    }
    static String readText(LibraryFiles.Node file) throws IOException {
        return readTextBytes(LibraryFiles.readLimited(file, 2 * 1024 * 1024), file.name());
    }
    private static String readTextBytes(byte[] bytes, String name) throws IOException {
        if (bytes.length > 2 * 1024 * 1024) throw new IOException("Metadata file is too large: " + name);
        String utf8 = decode(bytes, StandardCharsets.UTF_8);
        if (utf8 != null) return utf8.startsWith("\ufeff") ? utf8.substring(1) : utf8;
        String shiftJis = decode(bytes, Charset.forName("Shift_JIS"));
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

    private static LibraryFiles.Node artwork(LibraryFiles.Node root, LibraryFiles.Node defFile,
                                              String declared) throws IOException {
        if (declared != null) {
            String path = declared.trim().replace('\\', '/').replace("\"", "");
            if (!path.isEmpty()) {
                for (LibraryFiles.Node base : new LibraryFiles.Node[]{defFile.parent(), root}) {
                    try {
                        LibraryFiles.Node resolved = LibraryFiles.resolve(base, path);
                        if (resolved != null && !resolved.directory()) return resolved;
                    } catch (IOException unsafe) {
                        if (unsafe.getMessage().startsWith("Invalid relative")) break;
                        throw unsafe;
                    }
                }
            }
        }
        String baseName = defFile.name().replaceFirst("(?i)\\.def$", "");
        for (String fallback : new String[]{baseName + ".sff", "portrait.png", "preview.png"}) {
            LibraryFiles.Node file = LibraryFiles.child(defFile.parent(), fallback);
            if (file != null && !file.directory()) return file;
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
                if (group >= 0 && group <= 65535 && image >= 0 && image <= 65535) return new int[]{group, image};
            } catch (NumberFormatException ignored) { }
        }
        return new int[]{0, 0};
    }

    public static Catalog scan(File root) throws IOException {
        File select = RosterStore.selectFile(root);
        if (select.isFile() && select.length() > SelectStorage.MAX_BYTES)
            throw new IOException("select.def exceeds 2 MB");
        byte[] roster = select.isFile() ? Files.readAllBytes(select.toPath()) : new byte[0];
        return scan(LibraryFiles.local(root), roster);
    }
    public static Catalog scan(LibraryFiles.Node root, byte[] workingRoster) throws IOException {
        LibraryFiles.Node chars = LibraryFiles.child(root, "chars");
        LibraryFiles.Node stages = LibraryFiles.child(root, "stages");
        if (chars == null || stages == null || !chars.directory() || !stages.directory())
            throw new IOException("Choose a folder with chars and stages directories");
        SelectDefEditor roster = new SelectDefEditor(readTextBytes(workingRoster, "select.def"));
        Catalog result = new Catalog();
        for (LibraryFiles.Node character : LibraryFiles.sorted(chars)) {
            if (!character.directory()) continue;
            LibraryFiles.Node chosen = null;
            for (LibraryFiles.Node file : LibraryFiles.sorted(character)) {
                if (file.directory() || !file.name().toLowerCase(Locale.ROOT).endsWith(".def")) continue;
                if (chosen == null || file.name().equalsIgnoreCase(character.name() + ".def")) chosen = file;
            }
            if (chosen == null) continue;
            Map<String, Map<String, String>> def = DefParser.parse(readText(chosen));
            String ref = character.name() + "/" + chosen.name();
            String name = DefParser.value(def, "info", "displayname",
                    DefParser.value(def, "info", "name", character.name()));
            String sprite = DefParser.value(def, "files", "sprite",
                    DefParser.value(def, "files", "spr", null));
            result.characters.add(new Item("characters", ref, name,
                    DefParser.value(def, "info", "author", "Unknown"), roster.isEnabled("characters", ref),
                    chosen, artwork(root, chosen, sprite), 9000, 1, null));
        }
        scanStages(root, stages, stages, roster, result.stages, "", 0);
        for (RosterDiagnostics.Entry entry : RosterDiagnostics.entries(root, workingRoster)) {
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
                    && !entry.resolvedFile.directory() && entry.resolvedFile.name().toLowerCase(Locale.ROOT).endsWith(".def")) {
                LibraryFiles.Node defFile = entry.resolvedFile;
                Map<String, Map<String, String>> def = DefParser.parse(readText(defFile));
                String name = DefParser.value(def, "info", "displayname",
                        DefParser.value(def, "info", "name", defFile.name()));
                String sprite = DefParser.value(def, "files", "sprite",
                        DefParser.value(def, "files", "spr", null));
                destination.add(new Item("characters", entry.rawReference, name,
                        DefParser.value(def, "info", "author", "Unknown"), entry.active,
                        defFile, artwork(root, defFile, sprite), 9000, 1, null));
            }
        }
        return result;
    }

    private static void scanStages(LibraryFiles.Node libraryRoot, LibraryFiles.Node root,
                                   LibraryFiles.Node folder, SelectDefEditor roster, List<Item> items,
                                   String prefix, int depth) throws IOException {
        if (depth > ImportLimits.MAX_DEPTH) return;
        for (LibraryFiles.Node file : LibraryFiles.sorted(folder)) {
            if (file.directory()) {
                scanStages(libraryRoot, root, file, roster, items, prefix + file.name() + "/", depth + 1);
                continue;
            }
            if (!file.name().toLowerCase(Locale.ROOT).endsWith(".def")) continue;
            String ref = "stages/" + prefix + file.name();
            Map<String, Map<String, String>> def = DefParser.parse(readText(file));
            String name = DefParser.value(def, "info", "name", file.name().substring(0, file.name().length() - 4));
            int[] sprite = stageSprite(def);
            String spritePath = DefParser.value(def, "bgdef", "spr", DefParser.value(def, "files", "spr", null));
            items.add(new Item("extrastages", ref, name, DefParser.value(def, "info", "author", "Unknown"),
                    roster.isEnabled("extrastages", ref), file, artwork(libraryRoot, file, spritePath),
                    sprite[0], sprite[1], null));
        }
    }
}
