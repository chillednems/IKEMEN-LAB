package com.chillednems.ikemenlab;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** Transactional local roster edits with a copy of the previous file. */
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
        File select = selectFile(root);
        File data = select.getParentFile();
        if (!data.isDirectory() && !data.mkdirs()) throw new IOException("Could not create data directory");
        if (select.isFile() && select.length() > 2 * 1024 * 1024) throw new IOException("select.def exceeds 2 MB editing limit");
        byte[] previous = select.isFile() ? Files.readAllBytes(select.toPath()) : new byte[0];
        boolean bom = previous.length >= 3 && (previous[0] & 255) == 239 && (previous[1] & 255) == 187 && (previous[2] & 255) == 191;
        int offset = bom ? 3 : 0;
        Charset encoding = StandardCharsets.UTF_8;
        String content;
        try {
            content = encoding.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(previous, offset, previous.length - offset)).toString();
        } catch (CharacterCodingException invalidUtf8) {
            encoding = Charset.forName("windows-1252");
            content = new String(previous, encoding);
        }
        SelectDefEditor editor = new SelectDefEditor(content);
        editor.setEnabled(item.kind, item.reference, enabled);
        if (select.isFile()) {
            File backup = Files.createTempFile(data.toPath(), "select.def.backup.", ".bak").toFile();
            Files.copy(select.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        File temp = File.createTempFile("select-", ".tmp", data);
        try {
            byte[] changed = editor.content().getBytes(encoding);
            if (bom) {
                byte[] withBom = new byte[changed.length + 3];
                withBom[0] = (byte) 239; withBom[1] = (byte) 187; withBom[2] = (byte) 191;
                System.arraycopy(changed, 0, withBom, 3, changed.length);
                changed = withBom;
            }
            Files.write(temp.toPath(), changed);
            Files.move(temp.toPath(), select.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(temp.toPath()); }
    }
}
