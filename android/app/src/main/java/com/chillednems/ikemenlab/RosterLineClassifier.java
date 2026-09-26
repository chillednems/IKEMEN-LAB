package com.chillednems.ikemenlab;

import java.util.Locale;
import java.util.regex.Pattern;

/** Shared distinction between roster entries and instructional select.def comments. */
final class RosterLineClassifier {
    private static final Pattern EXAMPLE = Pattern.compile("(?i)(?:\\b(?:examples?|syntax|format)\\b|\\be\\.g\\.)");
    private static final Pattern INSERT = Pattern.compile("(?i)\\binsert\\s+your\\b.*\\bbelow\\b");
    static final class Candidate {
        final String section, reference;
        final boolean active;
        Candidate(String section, String reference, boolean active) {
            this.section = section; this.reference = reference; this.active = active;
        }
    }
    private String section = "";
    private boolean exampleBlock;

    String section() { return section; }

    static String sectionOf(String line) {
        String value = line.trim();
        return value.startsWith("[") && value.indexOf(']') > 1
                ? value.substring(1, value.indexOf(']')).trim().toLowerCase(Locale.ROOT) : null;
    }

    Candidate accept(String original) {
        String next = sectionOf(original);
        if (next != null) { section = next; exampleBlock = false; return null; }
        if (!section.equals("characters") && !section.equals("extrastages")) return null;
        String line = original.trim();
        if (line.isEmpty()) { exampleBlock = false; return null; }
        boolean active = !line.startsWith(";");
        if (active) exampleBlock = false;
        else line = line.substring(1).trim();
        if (line.isEmpty() || line.startsWith(";") || line.startsWith("#") || line.startsWith("---")) {
            exampleBlock = false; return null;
        }
        if (!active) {
            if (INSERT.matcher(line).find()) { exampleBlock = false; return null; }
            if ((line.indexOf(' ') >= 0 || line.indexOf(':') >= 0) && EXAMPLE.matcher(line).find()) {
                exampleBlock = true; return null;
            }
            if (exampleBlock) return null;
        }
        String raw = line.split("[,;]", 2)[0].trim().replace('\\', '/');
        if (!looksLikeReference(raw, active)) return null;
        return new Candidate(section, raw, active);
    }

    private static boolean looksLikeReference(String reference, boolean active) {
        if (reference.isEmpty() || reference.indexOf('=') >= 0
                || reference.indexOf('[') >= 0 || reference.indexOf(']') >= 0) return false;
        String lower = reference.toLowerCase(Locale.ROOT);
        if (lower.equals("random") || lower.equals("randomselect") || lower.startsWith("randomselect/")) return false;
        if (!active && !reference.contains("/") && !lower.endsWith(".def") && !lower.endsWith(".zip")) return false;
        // A path-shaped prose comment such as "chars/ directory. The syntax is as" is not a roster entry.
        if (!active && reference.contains("/") && reference.contains(" ")
                && !lower.endsWith(".def") && !lower.endsWith(".zip")) return false;
        for (int i = 0; i < reference.length(); i++) {
            char c = reference.charAt(i);
            if (Character.isISOControl(c) || "\"<>|?*:#".indexOf(c) >= 0) return false;
        }
        return true;
    }

    static boolean isUnsafe(String reference) {
        if (reference.startsWith("/") || reference.contains("..")) return true;
        for (String segment : reference.split("/", -1))
            if (segment.trim().isEmpty() || segment.equals(".")) return true;
        return false;
    }
}
