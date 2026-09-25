package com.chillednems.ikemenlab;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** A deliberately small reader for MUGEN/IKEMEN INI-like DEF metadata. */
public final class DefParser {
    private DefParser() {}

    public static Map<String, Map<String, String>> parse(String text) {
        Map<String, Map<String, String>> sections = new LinkedHashMap<>();
        String section = "";
        sections.put(section, new LinkedHashMap<>());
        for (String raw : text.split("\\r?\\n|\\r", -1)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith(";")) continue;
            if (line.startsWith("[") && line.indexOf(']') > 1) {
                section = line.substring(1, line.indexOf(']')).trim().toLowerCase(Locale.ROOT);
                sections.computeIfAbsent(section, ignored -> new LinkedHashMap<>());
                continue;
            }
            int equal = line.indexOf('=');
            if (equal < 1) continue;
            String key = line.substring(0, equal).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(equal + 1);
            int comment = -1;
            boolean quoted = false;
            for (int i = 0; i < value.length(); i++) {
                if (value.charAt(i) == '"') quoted = !quoted;
                else if (value.charAt(i) == ';' && !quoted) { comment = i; break; }
            }
            if (comment >= 0) value = value.substring(0, comment);
            value = value.trim();
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\""))
                value = value.substring(1, value.length() - 1);
            sections.get(section).put(key, value);
        }
        return sections;
    }

    public static String value(Map<String, Map<String, String>> sections, String section, String key, String fallback) {
        Map<String, String> values = sections.get(section);
        return values == null ? fallback : values.getOrDefault(key, fallback);
    }
}
