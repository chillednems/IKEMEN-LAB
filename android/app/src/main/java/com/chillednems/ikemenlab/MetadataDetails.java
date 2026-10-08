package com.chillednems.ikemenlab;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Bounded, read-only facts from a selected DEF and its declared CMD file. */
final class MetadataDetails {
    static final int MAX_FILE_BYTES = 512 * 1024;
    private static final int MAX_LINES = 10_000;
    private static final int MAX_COMMANDS = 200;
    final List<String> fields;
    final List<String> commands;
    final String commandNotice;

    private MetadataDetails(List<String> fields, List<String> commands, String commandNotice) {
        this.fields = Collections.unmodifiableList(fields);
        this.commands = Collections.unmodifiableList(commands);
        this.commandNotice = commandNotice;
    }

    static MetadataDetails read(LibraryScanner.Item item) throws IOException {
        if (item.defNode == null) throw new IOException("Referenced DEF is missing");
        String defText = boundedText(item.defNode);
        Map<String, Map<String, String>> def = DefParser.parse(defText);
        List<String> fields = new ArrayList<>();
        add(fields, def, "info", "name", "Name");
        add(fields, def, "info", "displayname", "Display name");
        add(fields, def, "info", "author", "Author");
        add(fields, def, "info", "versiondate", "Version date");
        add(fields, def, "info", "mugenversion", "MUGEN version");
        add(fields, def, "info", "ikemenversion", "IKEMEN version");
        add(fields, def, "info", "localcoord", "Local coordinates");
        add(fields, def, "files", "pal1", "Palette 1");
        add(fields, def, "files", "pal2", "Palette 2");
        add(fields, def, "files", "pal3", "Palette 3");
        add(fields, def, "files", "pal4", "Palette 4");
        if (!item.kind.equals("characters")) {
            add(fields, def, "camera", "boundleft", "Left bound");
            add(fields, def, "camera", "boundright", "Right bound");
            add(fields, def, "camera", "boundhigh", "High bound");
            add(fields, def, "camera", "boundlow", "Low bound");
            add(fields, def, "music", "bgmusic", "Music");
            add(fields, def, "music", "bgmvolume", "Music volume");
            return new MetadataDetails(fields, new ArrayList<>(), "Move inputs apply to characters only.");
        }
        String declared = DefParser.value(def, "files", "cmd", null);
        if (declared == null || declared.trim().isEmpty())
            return new MetadataDetails(fields, new ArrayList<>(), "No CMD file declared in this DEF.");
        LibraryFiles.Node cmd;
        try { cmd = LibraryFiles.resolve(item.defNode.parent(), declared.trim().replace('\\', '/').replace("\"", "")); }
        catch (IOException unsafe) { return new MetadataDetails(fields, new ArrayList<>(), "CMD path is unsafe or unsupported."); }
        if (cmd == null || cmd.directory())
            return new MetadataDetails(fields, new ArrayList<>(), "Declared CMD file is unavailable.");
        try {
            List<String> commands = parseCommands(boundedText(cmd));
            return new MetadataDetails(fields, commands, commands.isEmpty()
                    ? "No static [Command] input definitions found." : "Static CMD input definitions; actual moves are unverified.");
        } catch (IOException unreadable) {
            return new MetadataDetails(fields, new ArrayList<>(), "CMD file cannot be previewed: " + unreadable.getMessage());
        }
    }

    static String boundedText(LibraryFiles.Node node) throws IOException {
        String text = LibraryScanner.readTextBytes(LibraryFiles.readLimited(node, MAX_FILE_BYTES), node.name());
        int lines = 1;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n' && ++lines > MAX_LINES)
            throw new IOException("Metadata has too many lines");
        return text;
    }

    private static void add(List<String> fields, Map<String, Map<String, String>> def,
                            String section, String key, String label) {
        String value = DefParser.value(def, section, key, null);
        if (value != null && !value.trim().isEmpty()) fields.add(label + ": " + concise(value));
    }

    static List<String> parseCommands(String text) throws IOException {
        int lines = 1;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n' && ++lines > MAX_LINES)
            throw new IOException("CMD has too many lines");
        List<String> result = new ArrayList<>();
        String name = null, input = null;
        boolean command = false;
        for (String raw : text.split("\\r?\\n|\\r", -1)) {
            String line = raw.trim();
            if (line.startsWith("[") && line.indexOf(']') > 1) {
                if (command && name != null && input != null) result.add(concise(name) + " · " + concise(input));
                if (result.size() >= MAX_COMMANDS) break;
                command = line.substring(1, line.indexOf(']')).trim().equalsIgnoreCase("command");
                name = null; input = null;
            } else if (command && !line.startsWith(";") && !line.isEmpty()) {
                int equal = line.indexOf('=');
                if (equal < 1) continue;
                String key = line.substring(0, equal).trim().toLowerCase(Locale.ROOT);
                String value = line.substring(equal + 1).split(";", 2)[0].trim().replace("\"", "");
                if (key.equals("name")) name = value;
                else if (key.equals("command")) input = value;
            }
        }
        if (result.size() < MAX_COMMANDS && command && name != null && input != null)
            result.add(concise(name) + " · " + concise(input));
        return result;
    }

    private static String concise(String value) {
        String clean = value.replace('\r', ' ').replace('\n', ' ').trim();
        return clean.length() <= 120 ? clean : clean.substring(0, 119) + "…";
    }
}
