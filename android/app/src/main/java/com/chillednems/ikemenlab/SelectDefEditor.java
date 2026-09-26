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
        newline = content.contains("\r\n") ? "\r\n" : content.contains("\r") ? "\r" : "\n";
        int start = 0;
        for (int i = 0; i < content.length(); i++) {
            char current = content.charAt(i);
            if (current == '\n' || current == '\r') {
                int end = current == '\r' && i + 1 < content.length() && content.charAt(i + 1) == '\n' ? i + 2 : i + 1;
                lines.add(new Line(content.substring(start, i), content.substring(i, end)));
                i = end - 1;
                start = end;
            }
        }
        if (start < content.length()) lines.add(new Line(content.substring(start), ""));
    }

    private static boolean matches(String section, String actual, String wanted) {
        if (actual == null) return false;
        if (RosterLineClassifier.isUnsafe(actual)) return false;
        actual = actual.toLowerCase(Locale.ROOT);
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
        RosterLineClassifier classifier = new RosterLineClassifier();
        boolean found = false;
        boolean active = false;
        for (Line line : lines) {
            RosterLineClassifier.Candidate candidate = classifier.accept(line.text);
            if (candidate != null && candidate.section.equals(section.toLowerCase(Locale.ROOT))
                    && matches(candidate.section, candidate.reference, relativeRef)) {
                found = true;
                active |= candidate.active;
            }
        }
        return found ? active : null;
    }

    /** Enables an unlisted item by adding a relative reference within the requested section. */
    public void setEnabled(String section, String relativeRef, boolean enabled) {
        setEnabled(section, relativeRef, enabled, false);
    }

    /** Exact mode is for a missing reference: do not alter another DEF in the same character folder. */
    public void setEnabledExact(String section, String relativeRef, boolean enabled) {
        setEnabled(section, relativeRef, enabled, true);
    }

    private void setEnabled(String section, String relativeRef, boolean enabled, boolean exact) {
        String wanted = section.toLowerCase(Locale.ROOT);
        if (!wanted.equals("characters") && !wanted.equals("extrastages")) throw new IllegalArgumentException("Invalid roster section");
        if (relativeRef.trim().isEmpty() || relativeRef.startsWith("/") || relativeRef.contains("..") || relativeRef.contains("\\"))
            throw new IllegalArgumentException("Invalid relative reference");
        RosterLineClassifier classifier = new RosterLineClassifier();
        int insert = -1;
        boolean found = false;
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            String before = classifier.section();
            RosterLineClassifier.Candidate candidate = classifier.accept(line.text);
            String current = classifier.section();
            if (!before.equals(current)) {
                if (before.equals(wanted) && insert < 0) insert = i;
            } else if (current.equals(wanted) && candidate != null) {
                String actual = candidate.reference;
                if (exact ? actual.equalsIgnoreCase(relativeRef.replace('\\', '/'))
                        : matches(current, actual, relativeRef)) {
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
        if (insert < 0 && classifier.section().equals(wanted)) insert = lines.size();
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
