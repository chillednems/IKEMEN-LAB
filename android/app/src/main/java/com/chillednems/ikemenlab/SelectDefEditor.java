package com.chillednems.ikemenlab;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Edits only roster lines, retaining every other line and its original line ending. */
public final class SelectDefEditor {
    private static final class Line {
        String text;
        final String ending;
        Line(String text, String ending) { this.text = text; this.ending = ending; }
    }

    private final List<Line> lines = new ArrayList<>();
    private final String newline;

    public SelectDefEditor(String content) {
        newline = content.contains("\r\n") ? "\r\n" : "\n";
        int start = 0;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                int end = i > start && content.charAt(i - 1) == '\r' ? i - 1 : i;
                lines.add(new Line(content.substring(start, end), content.substring(end, i + 1)));
                start = i + 1;
            }
        }
        if (start < content.length()) lines.add(new Line(content.substring(start), ""));
    }

    private static String sectionOf(String line) {
        String value = line.trim();
        return value.startsWith("[") && value.indexOf(']') > 1
                ? value.substring(1, value.indexOf(']')).trim().toLowerCase(Locale.ROOT) : null;
    }

    private static String candidate(String line) {
        String value = line.trim();
        if (value.startsWith(";")) value = value.substring(1).trim();
        if (value.isEmpty() || value.startsWith(";") || value.startsWith("[") || value.startsWith("#")) return null;
        int comma = value.indexOf(',');
        if (comma >= 0) value = value.substring(0, comma);
        int semicolon = value.indexOf(';');
        if (semicolon >= 0) value = value.substring(0, semicolon);
        return value.trim().replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    private static boolean matches(String section, String actual, String wanted) {
        if (actual == null) return false;
        if (actual.startsWith("/") || actual.equals("..") || actual.startsWith("../") || actual.contains("/../") || actual.endsWith("/..")) return false;
        wanted = wanted.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (actual.equals(wanted)) return true;
        if (section.equals("characters")) {
            String folder = wanted.contains("/") ? wanted.substring(0, wanted.indexOf('/')) : wanted;
            return actual.equals(folder);
        }
        if (section.equals("extrastages")) {
            String shortWanted = wanted.startsWith("stages/") ? wanted.substring(7) : wanted;
            String shortActual = actual.startsWith("stages/") ? actual.substring(7) : actual;
            return shortActual.equals(shortWanted);
        }
        return false;
    }

    /** null means not present; otherwise whether at least one matching entry is active. */
    public Boolean isEnabled(String section, String relativeRef) {
        String current = "";
        boolean found = false;
        boolean active = false;
        for (Line line : lines) {
            String next = sectionOf(line.text);
            if (next != null) current = next;
            else if (current.equals(section.toLowerCase(Locale.ROOT)) && matches(current, candidate(line.text), relativeRef)) {
                found = true;
                active |= !line.text.trim().startsWith(";");
            }
        }
        return found ? active : null;
    }

    /** Enables an unlisted item by adding a relative reference within the requested section. */
    public void setEnabled(String section, String relativeRef, boolean enabled) {
        String wanted = section.toLowerCase(Locale.ROOT);
        if (!wanted.equals("characters") && !wanted.equals("extrastages")) throw new IllegalArgumentException("Invalid roster section");
        if (relativeRef.trim().isEmpty() || relativeRef.startsWith("/") || relativeRef.contains("..") || relativeRef.contains("\\"))
            throw new IllegalArgumentException("Invalid relative reference");
        String current = "";
        int insert = -1;
        boolean found = false;
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            String next = sectionOf(line.text);
            if (next != null) {
                if (current.equals(wanted) && insert < 0) insert = i;
                current = next;
            } else if (current.equals(wanted)) {
                if (matches(current, candidate(line.text), relativeRef)) {
                    found = true;
                    if (enabled && line.text.trim().startsWith(";")) {
                        int semi = line.text.indexOf(';');
                        line.text = line.text.substring(0, semi) + line.text.substring(semi + 1);
                    } else if (!enabled && !line.text.trim().startsWith(";")) {
                        int prefix = 0;
                        while (prefix < line.text.length() && Character.isWhitespace(line.text.charAt(prefix))) prefix++;
                        line.text = line.text.substring(0, prefix) + ";" + line.text.substring(prefix);
                    }
                }
            }
        }
        if (found || !enabled) return;
        if (insert < 0 && current.equals(wanted)) insert = lines.size();
        if (insert < 0) {
            if (!lines.isEmpty() && lines.get(lines.size() - 1).ending.isEmpty()) lines.add(new Line("", newline));
            lines.add(new Line("[" + (wanted.equals("characters") ? "Characters" : "ExtraStages") + "]", newline));
            insert = lines.size();
        }
        if (insert > 0 && lines.get(insert - 1).ending.isEmpty()) {
            Line previous = lines.get(insert - 1);
            lines.set(insert - 1, new Line(previous.text, newline));
        }
        lines.add(insert, new Line(relativeRef, newline));
    }

    public String content() {
        StringBuilder result = new StringBuilder();
        for (Line line : lines) result.append(line.text).append(line.ending);
        return result.toString();
    }
}
