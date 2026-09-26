package com.chillednems.ikemenlab;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;

/** Byte-preserving roster edits committed through the managed version store. */
public final class RosterStore {
    private RosterStore() {}

    public static File selectFile(File root) {
        File data = new File(root, "data");
        File[] top = root.listFiles();
        if (top != null) for (File child : top) if (child.isDirectory() && child.getName().equalsIgnoreCase("data")) { data = child; break; }
        File select = new File(data, "select.def");
        File[] files = data.listFiles();
        if (files != null) for (File child : files) if (child.isFile() && child.getName().equalsIgnoreCase("select.def")) return child;
        return select;
    }

    public static synchronized void setEnabled(File root, LibraryScanner.Item item, boolean enabled) throws IOException {
        setEnabled(root, item, enabled, null);
    }

    public static synchronized void setEnabled(File root, LibraryScanner.Item item, boolean enabled,
                                               Integer retainedVersions) throws IOException {
        if (enabled && item.warning != null) throw new IOException("Referenced content is missing: " + item.reference);
        SelectStorage storage = new SelectStorage(root);
        File select = selectFile(root);
        File data = select.getParentFile();
        if (!data.isDirectory() && !data.mkdirs()) throw new IOException("Could not create data directory");
        if (select.isFile() && select.length() > 2 * 1024 * 1024) throw new IOException("select.def exceeds 2 MB editing limit");
        byte[] previous = storage.readWorking().bytes;
        boolean bom = previous.length >= 3 && (previous[0] & 255) == 239 && (previous[1] & 255) == 187 && (previous[2] & 255) == 191;
        int offset = bom ? 3 : 0;
        Charset encoding = StandardCharsets.UTF_8;
        String content;
        try {
            content = encoding.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(previous, offset, previous.length - offset)).toString();
        } catch (CharacterCodingException invalidUtf8) {
            // ISO-8859-1 maps every byte 1:1. Editing an ASCII roster reference
            // then encoding with it preserves unrelated Shift_JIS/CP1252 bytes.
            // The legacy charset is unknown, so non-ASCII references cannot be added safely.
            if (!StandardCharsets.US_ASCII.newEncoder().canEncode(item.reference))
                throw new IOException("Cannot edit a non-ASCII reference in a legacy-encoded select.def");
            encoding = StandardCharsets.ISO_8859_1;
            content = new String(previous, encoding);
        }
        SelectDefEditor editor = new SelectDefEditor(content);
        if (item.warning != null) editor.setEnabledExact(item.kind, item.reference, enabled);
        else editor.setEnabled(item.kind, item.reference, enabled);
        byte[] changed = editor.content().getBytes(encoding);
        if (bom) {
            byte[] withBom = new byte[changed.length + 3];
            withBom[0] = (byte) 239; withBom[1] = (byte) 187; withBom[2] = (byte) 191;
            System.arraycopy(changed, 0, withBom, 3, changed.length);
            changed = withBom;
        }
        storage.commitWorking(SelectStorage.hash(previous), changed, "toggle:" + item.reference, retainedVersions);
    }
}
