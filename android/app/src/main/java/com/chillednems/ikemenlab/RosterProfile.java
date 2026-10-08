package com.chillednems.ikemenlab;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Active roster lines only; all unrelated select.def bytes survive activation. */
final class RosterProfile {
    static final int MAX_CHARACTERS = 2000, MAX_STAGES = 1000;
    static final class Snapshot {
        final List<String> characters, stages;
        Snapshot(List<String> characters, List<String> stages) {
            this.characters = new ArrayList<>(characters); this.stages = new ArrayList<>(stages);
        }
    }
    private static final class Line {
        final byte[] body, ending;
        final String text;
        boolean character, stage;
        Line(byte[] body, byte[] ending, String text) {
            this.body = body; this.ending = ending; this.text = text;
        }
    }
    private static final class Builder {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final byte[] newline;
        final int bomLength;
        int last;
        Builder(byte[] bom, byte[] newline) {
            this.newline = newline; bomLength = bom.length;
            write(bom);
        }
        void write(byte[] data) {
            bytes.write(data, 0, data.length);
            if (data.length > 0) last = data[data.length - 1] & 255;
        }
        void line(byte[] body, byte[] ending) {
            if (bytes.size() > bomLength && last != '\n' && last != '\r') write(newline);
            write(body); write(ending);
        }
        byte[] result() { return bytes.toByteArray(); }
    }
    private final List<Line> lines = new ArrayList<>();
    private final byte[] original;
    private final Charset encoding;
    private final byte[] bom;
    private final byte[] newline;
    private int characterSection = -1, stageSection = -1;

    RosterProfile(byte[] bytes) throws IOException {
        if (bytes.length > SelectStorage.MAX_BYTES) throw new IOException("Working roster exceeds 2 MB");
        original = bytes.clone();
        boolean utf8Bom = bytes.length >= 3 && (bytes[0] & 255) == 239 && (bytes[1] & 255) == 187 && (bytes[2] & 255) == 191;
        bom = utf8Bom ? Arrays.copyOf(bytes, 3) : new byte[0];
        int start = bom.length;
        Charset charset = StandardCharsets.UTF_8;
        try {
            charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, start, bytes.length - start));
        } catch (CharacterCodingException invalid) { charset = StandardCharsets.ISO_8859_1; }
        encoding = charset;
        byte[] foundNewline = null;
        RosterLineClassifier classifier = new RosterLineClassifier();
        for (int i = start; i < bytes.length;) {
            int end = i;
            while (end < bytes.length && bytes[end] != '\r' && bytes[end] != '\n') end++;
            int after = end;
            if (after < bytes.length) {
                after++;
                if (bytes[end] == '\r' && after < bytes.length && bytes[after] == '\n') after++;
            }
            byte[] ending = Arrays.copyOfRange(bytes, end, after);
            if (foundNewline == null && ending.length > 0) foundNewline = ending;
            byte[] body = Arrays.copyOfRange(bytes, i, end);
            String text = new String(body, encoding);
            if (lines.size() >= 30_000) throw new IOException("Roster exceeds 30,000 line collection limit");
            Line line = new Line(body, ending, text);
            lines.add(line);
            String heading = RosterLineClassifier.sectionOf(text);
            if (heading != null) {
                if (heading.equals("characters") && characterSection < 0) characterSection = lines.size() - 1;
                if (heading.equals("extrastages") && stageSection < 0) stageSection = lines.size() - 1;
            }
            RosterLineClassifier.Candidate candidate = classifier.accept(text);
            if (candidate != null && candidate.active) {
                line.character = candidate.section.equals("characters");
                line.stage = candidate.section.equals("extrastages");
            } else if (candidate == null && classifier.section().equals("characters")) {
                String token = token(text).toLowerCase(Locale.ROOT);
                line.character = !text.trim().startsWith(";") && (token.equals("empty") || token.equals("randomselect"));
            }
            i = after;
        }
        newline = foundNewline == null ? new byte[]{'\n'} : foundNewline;
    }

    Snapshot snapshot() {
        List<String> characters = new ArrayList<>(), stages = new ArrayList<>();
        for (Line line : lines) {
            if (line.character) characters.add(line.text);
            if (line.stage) stages.add(line.text);
        }
        return new Snapshot(characters, stages);
    }

    byte[] activate(Snapshot chosen) throws IOException {
        validate(chosen);
        Snapshot current = snapshot();
        boolean editCharacters = !current.characters.equals(chosen.characters);
        boolean editStages = !current.stages.equals(chosen.stages);
        if (!editCharacters && !editStages) return original.clone();
        int characterAt = editCharacters ? firstActive(true) : -1;
        int stageAt = editStages ? firstActive(false) : -1;
        if (editCharacters && characterAt < 0 && characterSection >= 0) characterAt = characterSection + 1;
        if (editStages && stageAt < 0 && stageSection >= 0) stageAt = stageSection + 1;
        Builder output = new Builder(bom, newline);
        for (int i = 0; i <= lines.size(); i++) {
            if (i == characterAt) appendEntries(output, chosen.characters);
            if (i == stageAt) appendEntries(output, chosen.stages);
            if (i == lines.size()) break;
            Line line = lines.get(i);
            if ((editCharacters && line.character) || (editStages && line.stage)) continue;
            output.line(line.body, line.ending);
        }
        if (editCharacters && characterAt < 0 && !chosen.characters.isEmpty()) {
            output.line("[Characters]".getBytes(StandardCharsets.US_ASCII), newline);
            appendEntries(output, chosen.characters);
        }
        if (editStages && stageAt < 0 && !chosen.stages.isEmpty()) {
            output.line("[ExtraStages]".getBytes(StandardCharsets.US_ASCII), newline);
            appendEntries(output, chosen.stages);
        }
        byte[] result = output.result();
        if (result.length > SelectStorage.MAX_BYTES) throw new IOException("Collection would exceed 2 MB working roster limit");
        return result;
    }

    private int firstActive(boolean character) {
        for (int i = 0; i < lines.size(); i++)
            if (character ? lines.get(i).character : lines.get(i).stage) return i;
        return -1;
    }

    private void appendEntries(Builder output, List<String> entries) throws IOException {
        for (String entry : entries) output.line(encode(entry), newline);
    }

    private byte[] encode(String text) throws IOException {
        try {
            ByteBuffer encoded = encoding.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(text));
            byte[] result = new byte[encoded.remaining()]; encoded.get(result); return result;
        } catch (CharacterCodingException incompatible) { throw new IOException("Collection text is not representable in this roster encoding"); }
    }

    static void validate(Snapshot snapshot) throws IOException {
        if (snapshot.characters.size() > MAX_CHARACTERS || snapshot.stages.size() > MAX_STAGES)
            throw new IOException("Collection exceeds entry limit");
        for (String entry : snapshot.characters) validateLine("characters", entry);
        for (String entry : snapshot.stages) validateLine("extrastages", entry);
    }

    static String reference(String section, String entry) throws IOException {
        validateLine(section, entry);
        return token(entry);
    }

    private static void validateLine(String section, String entry) throws IOException {
        if (entry == null || entry.isEmpty() || entry.length() > 512 || entry.indexOf('\r') >= 0 || entry.indexOf('\n') >= 0)
            throw new IOException("Collection entry is invalid or too long");
        RosterLineClassifier classifier = new RosterLineClassifier();
        classifier.accept("[" + section + "]");
        RosterLineClassifier.Candidate candidate = classifier.accept(entry);
        String token = token(entry);
        if (section.equals("characters") && !entry.trim().startsWith(";")
                && (token.equalsIgnoreCase("empty") || token.equalsIgnoreCase("randomselect"))) return;
        if (candidate == null || !candidate.active || !candidate.section.equals(section)
                || RosterLineClassifier.isUnsafe(candidate.reference))
            throw new IOException("Collection entry is not a safe active " + section + " reference");
    }

    private static String token(String text) {
        return text.trim().split("[,;]", 2)[0].trim().replace('\\', '/');
    }
}
