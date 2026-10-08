package com.chillednems.ikemenlab;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Bounded, read-only checks for file references declared in an add-on DEF. */
final class AddonHealth {
    static final class Report {
        final List<String> findings = new ArrayList<>();
        int checked, external;
        boolean blocked;
        String signature() {
            return checked + "\n" + external + "\n" + blocked + "\n" + String.join("\n", findings);
        }
        String summary() {
            return checked + " declared file references checked"
                    + (external == 0 ? "" : " · " + external + " external source dependencies")
                    + (findings.isEmpty() ? " · no missing supported references"
                    : "\n" + String.join("\n", findings.subList(0, Math.min(12, findings.size()))))
                    + (findings.size() > 12 ? "\n…additional findings omitted" : "");
        }
    }
    private interface Resolver { int resolve(String reference) throws IOException; }
    private static final int LOCAL = 1, EXTERNAL = 2, MISSING = 3, UNSUPPORTED = 4;

    static Report inspectStage(AddonPackage addon, LibraryFiles.Node source) throws IOException {
        String def = primaryDef(addon);
        File file = new File(addon.directory, def);
        if (file.length() > 1024 * 1024) throw new IOException("DEF exceeds 1 MB health-check limit");
        ByteArrayOutputStream limited = new ByteArrayOutputStream();
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] chunk = new byte[8192];
            int count;
            while ((count = input.read(chunk)) != -1) {
                if (limited.size() + count > 1024 * 1024)
                    throw new IOException("DEF exceeds 1 MB health-check limit");
                limited.write(chunk, 0, count);
            }
        }
        String text = boundedText(limited.toByteArray());
        String base = addon.kind + "/" + addon.name;
        return inspect(text, addon.kind, reference -> {
            String normalized = normalize(base, reference);
            if (normalized == null) return UNSUPPORTED;
            String localPrefix = base + "/";
            if (normalized.startsWith(localPrefix)) {
                File local = new File(addon.directory, normalized.substring(localPrefix.length()));
                if (local.isFile()) return LOCAL;
            }
            LibraryFiles.Node found = LibraryFiles.resolve(source, normalized);
            return found == null || found.directory() ? MISSING : EXTERNAL;
        });
    }

    static Report inspectLibrary(LibraryScanner.Item item, LibraryFiles.Node source) throws IOException {
        if (item == null || item.defNode == null || item.warning != null)
            throw new IOException("Choose an available character or stage");
        String text = boundedText(LibraryFiles.readLimited(item.defNode, 1024 * 1024));
        int slash = item.reference.lastIndexOf('/');
        String base = item.kind.equals("characters") ? "chars/" + item.reference.split("/", 2)[0]
                : slash < 0 ? "stages" : item.reference.substring(0, slash);
        return inspect(text, item.kind.equals("characters") ? "chars" : "stages", reference -> {
            String normalized = normalize(base, reference);
            if (normalized == null) return UNSUPPORTED;
            LibraryFiles.Node found = LibraryFiles.resolve(source, normalized);
            return found == null || found.directory() ? MISSING : LOCAL;
        });
    }

    private static Report inspect(String text, String kind, Resolver resolver) throws IOException {
        Map<String, Map<String, String>> parsed = DefParser.parse(text);
        Report report = new Report();
        if (kind.equals("chars")) {
            if (!parsed.containsKey("info")) {
                report.blocked = true; report.findings.add("Missing [Info] section in character DEF");
            }
            Map<String, String> files = parsed.get("files");
            if (files == null) {
                report.blocked = true; report.findings.add("Missing [Files] section in character DEF");
            } else for (Map.Entry<String, String> item : files.entrySet()) {
                String key = item.getKey().toLowerCase(Locale.ROOT);
                if (key.equals("sprite") || key.equals("spr") || key.equals("sound") || key.equals("snd")
                        || key.equals("cmd") || key.equals("cns") || key.equals("anim") || key.equals("air")
                        || key.equals("st") || key.equals("stcommon") || key.matches("st[0-9]+")
                        || key.matches("pal[0-9]+"))
                    check(report, key, item.getValue(), false, resolver);
            }
        } else {
            Map<String, String> bg = parsed.get("bgdef");
            if (!parsed.containsKey("info") || bg == null) {
                report.blocked = true; report.findings.add("Stage DEF needs supported [Info] and [BGDef] sections");
            }
            if (bg != null) for (Map.Entry<String, String> item : bg.entrySet())
                if (item.getKey().equals("spr") || item.getKey().equals("sprite"))
                    check(report, item.getKey(), item.getValue(), false, resolver);
            Map<String, String> music = parsed.get("music");
            if (music != null) for (Map.Entry<String, String> item : music.entrySet())
                if (item.getKey().startsWith("bgmusic"))
                    check(report, item.getKey(), item.getValue(), true, resolver);
        }
        return report;
    }

    private static void check(Report report, String key, String raw, boolean optional,
                              Resolver resolver) throws IOException {
        if (raw == null || raw.trim().isEmpty()) return;
        if (report.checked >= 200) throw new IOException("DEF declares too many file references");
        String path = raw.trim().replace("\"", "");
        int comma = path.indexOf(',');
        if (comma >= 0) path = path.substring(0, comma).trim();
        if (path.isEmpty()) return;
        report.checked++;
        int state = resolver.resolve(path);
        if (state == EXTERNAL) {
            report.external++;
            report.findings.add(key + ": uses existing source file " + path);
        } else if (state == MISSING) {
            report.findings.add(key + ": missing " + path + (optional ? " (optional music)" : ""));
            if (!optional) report.blocked = true;
        } else if (state == UNSUPPORTED) {
            report.findings.add(key + ": path cannot be verified " + path);
            if (!optional) report.blocked = true;
        }
    }

    private static String primaryDef(AddonPackage addon) throws IOException {
        for (AddonPackage.Entry entry : addon.files)
            if (entry.path.indexOf('/') < 0 && entry.path.toLowerCase(Locale.ROOT).endsWith(".def"))
                return entry.path;
        throw new IOException("Add-on has no root DEF file");
    }

    private static String boundedText(byte[] bytes) throws IOException {
        if (bytes.length > 1024 * 1024) throw new IOException("DEF exceeds 1 MB health-check limit");
        int lines = 0;
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == '\r') { lines++; if (i + 1 < bytes.length && bytes[i + 1] == '\n') i++; }
            else if (bytes[i] == '\n') lines++;
            if (lines > 10_000) throw new IOException("DEF exceeds 10,000 health-check lines");
        }
        return LibraryScanner.readTextBytes(bytes, "add-on DEF");
    }

    private static String normalize(String base, String reference) {
        String path = reference.replace('\\', '/');
        if (path.startsWith("/") || path.indexOf(':') >= 0 || path.indexOf('\0') >= 0) return null;
        List<String> parts = new ArrayList<>();
        if (!path.startsWith("data/") && !path.startsWith("sound/")
                && !path.startsWith("chars/") && !path.startsWith("stages/"))
            for (String part : base.split("/")) parts.add(part);
        for (String part : path.split("/", -1)) {
            if (part.isEmpty()) return null;
            if (part.equals(".")) continue;
            if (part.equals("..")) { if (parts.isEmpty()) return null; parts.remove(parts.size() - 1); }
            else parts.add(part);
        }
        return parts.isEmpty() ? null : String.join("/", parts);
    }
}
