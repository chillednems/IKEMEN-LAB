package com.chillednems.ikemenlab;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Reject ZIP features that ZipInputStream cannot safely describe, including links. */
final class ZipSafety {
    private ZipSafety() { }

    static void inspectCentralDirectory(File archive) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(archive, "r")) {
            long size = file.length();
            if (size < 22 || size > AddonPackage.MAX_ARCHIVE) throw new IOException("ZIP size is unsupported");
            int tailLength = (int) Math.min(size, 65557);
            byte[] tail = new byte[tailLength];
            file.seek(size - tailLength); file.readFully(tail);
            int eocd = -1;
            for (int i = tail.length - 22; i >= 0; i--)
                if (u32(tail, i) == 0x06054b50L && i + 22 + u16(tail, i + 20) == tail.length) {
                    eocd = i; break;
                }
            if (eocd < 0) throw new IOException("ZIP end record is missing");
            int count = u16(tail, eocd + 10);
            long centralSize = u32(tail, eocd + 12), centralOffset = u32(tail, eocd + 16);
            if (u16(tail, eocd + 4) != 0 || u16(tail, eocd + 6) != 0
                    || u16(tail, eocd + 8) != count || count == 65535
                    || centralSize == 0xffffffffL || centralOffset == 0xffffffffL
                    || centralOffset + centralSize > size - tailLength + eocd)
                throw new IOException("Split or ZIP64 archives are unsupported");
            if (count > AddonPackage.MAX_FILES * 2) throw new IOException("ZIP has too many entries");
            long cursor = centralOffset, end = centralOffset + centralSize;
            for (int i = 0; i < count; i++) {
                if (cursor + 46 > end) throw new IOException("Truncated ZIP central directory");
                byte[] fixed = new byte[46];
                file.seek(cursor); file.readFully(fixed);
                if (u32(fixed, 0) != 0x02014b50L) throw new IOException("Invalid ZIP central entry");
                int flags = u16(fixed, 8), method = u16(fixed, 10);
                long compressed = u32(fixed, 20), expanded = u32(fixed, 24);
                int nameLength = u16(fixed, 28), extraLength = u16(fixed, 30), commentLength = u16(fixed, 32);
                if ((flags & 1) != 0 || (flags & 0x40) != 0 || (method != 0 && method != 8)
                        || u16(fixed, 34) != 0 || nameLength == 0 || nameLength > 512
                        || compressed == 0xffffffffL || expanded == 0xffffffffL
                        || expanded > AddonPackage.MAX_FILE
                        || (expanded > 0 && (compressed == 0 || expanded > compressed * 100L))
                        || cursor + 46L + nameLength + extraLength + commentLength > end)
                    throw new IOException("ZIP entry uses unsupported or encrypted features");
                int mode = (int) (u32(fixed, 38) >>> 16);
                int type = mode & 0170000;
                if (type != 0 && type != 0100000 && type != 0040000)
                    throw new IOException("ZIP links and special files are unsupported");
                byte[] name = new byte[nameLength];
                file.readFully(name);
                try {
                    java.nio.CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(name));
                    AddonPackage.validSegment(decoded.toString().split("/", -1)[0]);
                } catch (CharacterCodingException invalid) { throw new IOException("ZIP filename is not UTF-8", invalid); }
                cursor += 46L + nameLength + extraLength + commentLength;
            }
            if (cursor != end) throw new IOException("ZIP central directory has trailing records");
        }
    }

    private static int u16(byte[] bytes, int offset) {
        return (bytes[offset] & 255) | ((bytes[offset + 1] & 255) << 8);
    }
    private static long u32(byte[] bytes, int offset) {
        return (u16(bytes, offset) & 65535L) | ((long) u16(bytes, offset + 2) << 16);
    }
}
