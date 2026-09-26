package com.chillednems.ikemenlab;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Conservative roster-reference validation; prose comments and random slots are not content warnings. */
public final class RosterDiagnostics {
    public static final class Entry {
        public final String section, rawReference, warning;
        public final int lineNumber;
        public final boolean active;
        public final LibraryFiles.Node resolvedFile;
        Entry(String section, String rawReference, String warning, int lineNumber, boolean active, LibraryFiles.Node resolvedFile) {
            this.section = section; this.rawReference = rawReference; this.warning = warning;
            this.lineNumber = lineNumber; this.active = active; this.resolvedFile = resolvedFile;
        }
    }
    public static final class Warning {
        public final String section, rawReference, reason;
        public final int lineNumber;
        public final boolean active;
        Warning(String section, String rawReference, String reason, int lineNumber, boolean active) {
            this.section = section; this.rawReference = rawReference; this.reason = reason;
            this.lineNumber = lineNumber; this.active = active;
        }
    }
    private RosterDiagnostics() {}

    public static List<Warning> scan(File root, byte[] bytes) throws IOException {
        return scan(LibraryFiles.local(root), bytes);
    }
    public static List<Warning> scan(LibraryFiles.Node root, byte[] bytes) throws IOException {
        List<Warning> warnings = new ArrayList<>();
        for (Entry entry : entries(root, bytes)) if (entry.warning != null)
            warnings.add(new Warning(entry.section, entry.rawReference, entry.warning, entry.lineNumber, entry.active));
        return Collections.unmodifiableList(warnings);
    }
    public static List<Entry> entries(File root, byte[] bytes) throws IOException {
        return entries(LibraryFiles.local(root), bytes);
    }
    public static List<Entry> entries(LibraryFiles.Node root, byte[] bytes) throws IOException {
        if (bytes.length > SelectStorage.MAX_BYTES) throw new IOException("select.def exceeds 2 MB");
        String text;
        int offset = bytes.length >= 3 && (bytes[0] & 255) == 239 && (bytes[1] & 255) == 187 && (bytes[2] & 255) == 191 ? 3 : 0;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString();
        } catch (CharacterCodingException legacy) {
            text = new String(bytes, offset, bytes.length - offset, StandardCharsets.ISO_8859_1);
        }
        List<Entry> entries = new ArrayList<>();
        RosterLineClassifier classifier = new RosterLineClassifier();
        String[] lines = text.split("\r\n|\r|\n", -1);
        for (int i = 0; i < lines.length; i++) {
            RosterLineClassifier.Candidate candidateLine = classifier.accept(lines[i]);
            if (candidateLine == null) continue;
            String section = candidateLine.section;
            String raw = candidateLine.reference;
            boolean active = candidateLine.active;
            String ref = raw;
            String lower = ref.toLowerCase(Locale.ROOT);
            if (RosterLineClassifier.isUnsafe(ref))
                { entries.add(new Entry(section, raw, "Invalid relative reference", i + 1, active, null)); continue; }
            LibraryFiles.Node base = LibraryFiles.child(root, section.equals("characters") ? "chars" : "stages");
            String relative = section.equals("extrastages") && lower.startsWith("stages/") ? ref.substring(7) : ref;
            LibraryFiles.Node candidate = base == null ? null : LibraryFiles.resolve(base, relative);
            boolean present = section.equals("characters") ? characterPresent(candidate) : candidate != null && !candidate.directory();
            entries.add(new Entry(section, raw, present ? null : "Referenced file is missing", i + 1, active,
                    present ? candidate : null));
        }
        return Collections.unmodifiableList(entries);
    }
    private static boolean characterPresent(LibraryFiles.Node candidate) throws IOException {
        if (candidate != null && !candidate.directory() && candidate.name().toLowerCase(Locale.ROOT).endsWith(".def")) return true;
        if (candidate != null && candidate.directory())
            for (LibraryFiles.Node file : candidate.children())
                if (!file.directory() && file.name().toLowerCase(Locale.ROOT).endsWith(".def")) return true;
        // References such as KFM/KFM.def must resolve the exact requested alternate DEF.
        return false;
    }
}
