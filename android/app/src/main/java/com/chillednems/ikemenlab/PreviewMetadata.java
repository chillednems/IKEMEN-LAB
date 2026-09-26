package com.chillednems.ikemenlab;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Ordered DEF/AIR sections; duplicate BG names must remain distinct. */
final class PreviewMetadata {
    static final class Section {
        final String name;
        final List<String> lines = new ArrayList<>();
        Section(String name) { this.name = name; }
        String get(String key, String fallback) {
            String result = fallback;
            for (String raw : lines) {
                int eq = raw.indexOf('=');
                if (eq < 1 || !raw.substring(0, eq).trim().equalsIgnoreCase(key)) continue;
                result = clean(raw.substring(eq + 1));
            }
            return result;
        }
    }
    final List<Section> sections = new ArrayList<>();

    static PreviewMetadata parse(String text) throws IOException {
        if (text.length() > 2_000_000) throw new IOException("Preview metadata exceeds limit");
        PreviewMetadata result = new PreviewMetadata();
        Section current = new Section(""); result.sections.add(current);
        for (String raw : text.split("\\r?\\n|\\r", -1)) {
            String line = raw.trim();
            if (line.startsWith("[") && line.indexOf(']') > 1) {
                current = new Section(line.substring(1, line.indexOf(']')).trim().toLowerCase(Locale.ROOT));
                if (result.sections.size() >= 2000) throw new IOException("Too many preview metadata sections");
                result.sections.add(current);
            } else if (!line.isEmpty() && !line.startsWith(";")) current.lines.add(line);
        }
        return result;
    }
    Section first(String name) {
        for (Section section : sections) if (section.name.equalsIgnoreCase(name)) return section;
        return new Section(name);
    }
    static String clean(String value) {
        boolean quoted = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"') quoted = !quoted;
            if (c == ';' && !quoted) { value = value.substring(0, i); break; }
        }
        value = value.trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\""))
            value = value.substring(1, value.length() - 1);
        return value.trim();
    }
    static int[] pair(String text, int first, int second) {
        String[] fields = clean(text).split(",");
        try {
            return new int[] {Integer.parseInt(fields[0].trim()), Integer.parseInt(fields[1].trim())};
        } catch (RuntimeException invalid) { return new int[] {first, second}; }
    }
    static double[] decimalPair(String text, double first, double second) {
        String[] fields = clean(text).split(",");
        try {
            return new double[] {Double.parseDouble(fields[0].trim()), Double.parseDouble(fields[1].trim())};
        } catch (RuntimeException invalid) { return new double[] {first, second}; }
    }
    static int integer(String text, int fallback) {
        try { return Integer.parseInt(clean(text)); } catch (RuntimeException invalid) { return fallback; }
    }
    static File resolve(File def, String path) throws IOException {
        path = clean(path).replace('\\', '/');
        if (path.isEmpty() || path.startsWith("/") || path.matches("(?i)^[a-z]:.*"))
            throw new IOException("Invalid preview asset path");
        File root = def.getParentFile().getCanonicalFile(), current = root;
        for (String segment : path.split("/")) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals(".."))
                throw new IOException("Invalid preview asset path");
            File[] options = current.listFiles();
            File chosen = null;
            if (options != null) for (File option : options) if (option.getName().equalsIgnoreCase(segment)) {
                chosen = option; break;
            }
            if (chosen == null) throw new IOException("Preview asset is missing: " + segment);
            current = chosen.getCanonicalFile();
            if (!current.toPath().startsWith(root.toPath())) throw new IOException("Preview asset escapes folder");
        }
        if (!current.isFile()) throw new IOException("Preview asset is not a file");
        return current;
    }
    static int[] actionFirstFrame(PreviewMetadata metadata, int action) throws IOException {
        String wanted = "begin action " + action;
        for (Section section : metadata.sections) if (section.name.replaceAll("\\s+", " ").equals(wanted)) {
            for (String raw : section.lines) {
                String line = clean(raw);
                String[] parts = line.split(",");
                if (parts.length < 5) continue;
                try {
                    int group = Integer.parseInt(parts[0].trim()), image = Integer.parseInt(parts[1].trim());
                    int x = Integer.parseInt(parts[2].trim()), y = Integer.parseInt(parts[3].trim());
                    if (group >= 0 && image >= 0) return new int[] {group, image, x, y};
                } catch (NumberFormatException ignored) { }
            }
            break;
        }
        throw new IOException("AIR action " + action + " has no valid sprite frame");
    }
}
