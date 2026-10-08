package com.chillednems.ikemenlab;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Ordered active slots in [Characters], retaining all unrelated select.def bytes. */
final class RosterArrangement {
    private static final int MAX_LINES = 30000;
    static final class Slot {
        final int line;
        final String title;
        Slot(int line, String title) { this.line = line; this.title = title; }
    }
    private static final class Line {
        String text;
        final String ending;
        Line(String text, String ending) { this.text = text; this.ending = ending; }
    }
    private final List<Line> lines = new ArrayList<>();
    private final List<Slot> slots = new ArrayList<>();
    private final Charset encoding;
    private final boolean bom;

    RosterArrangement(byte[] bytes) throws IOException {
        bom = bytes.length >= 3 && (bytes[0] & 255) == 239 && (bytes[1] & 255) == 187 && (bytes[2] & 255) == 191;
        int offset = bom ? 3 : 0;
        String content;
        Charset charset = StandardCharsets.UTF_8;
        try {
            content = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString();
        } catch (CharacterCodingException invalid) {
            charset = StandardCharsets.ISO_8859_1;
            content = new String(bytes, offset, bytes.length - offset, charset);
        }
        encoding = charset;
        String section = "";
        int start = 0;
        for (int i = 0; i <= content.length(); i++) {
            if (i < content.length() && content.charAt(i) != '\n' && content.charAt(i) != '\r') continue;
            int end = i;
            if (i < content.length()) {
                end = content.charAt(i) == '\r' && i + 1 < content.length() && content.charAt(i + 1) == '\n' ? i + 2 : i + 1;
            }
            if (i == content.length() && i == start) break;
            String value = content.substring(start, i);
            if (lines.size() >= MAX_LINES) throw new IOException("Roster arrangement exceeds 30,000 line preview limit");
            lines.add(new Line(value, content.substring(i, end)));
            String next = RosterLineClassifier.sectionOf(value);
            if (next != null) section = next;
            else if (section.equals("characters")) {
                String trimmed = value.trim();
                if (!trimmed.startsWith(";") && !trimmed.isEmpty()) {
                    String token = trimmed.split("[,;]", 2)[0].trim();
                    String lower = token.toLowerCase(Locale.ROOT);
                    if (lower.equals("empty")) slots.add(new Slot(lines.size() - 1, "Empty slot"));
                    else if (lower.equals("randomselect")) slots.add(new Slot(lines.size() - 1, "Random select"));
                    else if (!token.contains("=") && !RosterLineClassifier.isUnsafe(token.replace('\\', '/')))
                        slots.add(new Slot(lines.size() - 1, token));
                }
            }
            i = end - 1;
            start = end;
        }
    }

    List<Slot> slots() { return new ArrayList<>(slots); }

    byte[] moved(int position, int direction) {
        int next = position + direction;
        if (position < 0 || next < 0 || next >= slots.size() || Math.abs(direction) != 1)
            throw new IllegalArgumentException("Cannot move beyond roster bounds");
        Line first = lines.get(slots.get(position).line);
        Line second = lines.get(slots.get(next).line);
        String old = first.text; first.text = second.text; second.text = old;
        StringBuilder content = new StringBuilder();
        for (Line line : lines) content.append(line.text).append(line.ending);
        byte[] result = content.toString().getBytes(encoding);
        if (!bom) return result;
        byte[] withBom = new byte[result.length + 3];
        withBom[0] = (byte) 239; withBom[1] = (byte) 187; withBom[2] = (byte) 191;
        System.arraycopy(result, 0, withBom, 3, result.length);
        return withBom;
    }

    static void moveWorking(File root, int position, int direction, Integer retention) throws IOException {
        SelectStorage storage = new SelectStorage(root);
        SelectStorage.Snapshot snapshot = storage.readWorking();
        byte[] replacement = new RosterArrangement(snapshot.bytes).moved(position, direction);
        storage.commitWorking(snapshot.sha256, replacement, "reorder:slot:" + position, retention);
    }
}
