package com.chillednems.ikemenlab;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public final class AppSelectTargetTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    private static final class Source implements SelectStorage.External {
        byte[] bytes = bytes("source");
        int writes;
        @Override public String identity() { return "source://data/select.def"; }
        @Override public byte[] read() { return bytes.clone(); }
        @Override public void replace(byte[] next) { writes++; bytes = next.clone(); }
        @Override public String backup(byte[] old, String id) { throw new AssertionError("source backup was used"); }
        @Override public List<SelectStorage.Version> listBackups() { return Collections.emptyList(); }
        @Override public byte[] readBackup(String id) { throw new AssertionError("source backup was used"); }
        @Override public void pruneBackups(Integer keep, String protectedId) { }
    }

    @Test public void appPreimageRemainsReadableOfflineAndSeparatedByLibraryAndSource() throws Exception {
        File app = temporary.newFolder("app");
        Source source = new Source();
        String version = "11111111-1111-1111-1111-111111111111";
        AppSelectTarget writer = new AppSelectTarget(source, app, "working-A", "tree-A");
        writer.backup(bytes("before"), version);
        assertArrayEquals(bytes("before"), writer.readBackup(version));
        AppSelectTarget offline = new AppSelectTarget(null, app, "working-A", "tree-A");
        assertEquals(1, offline.listBackups().size());
        assertArrayEquals(bytes("before"), offline.readBackup(version));
        assertTrue(new AppSelectTarget(null, app, "working-B", "tree-A").listBackups().isEmpty());
        assertTrue(new AppSelectTarget(null, app, "working-A", "tree-B").listBackups().isEmpty());
        assertEquals(0, source.writes);
    }

    @Test public void failedAppBackupPreventsAnySourceReplacement() throws Exception {
        File root = temporary.newFolder("working");
        File data = new File(root, "data");
        assertTrue(data.mkdir());
        Files.write(new File(data, "select.def").toPath(), bytes("working"));
        File app = temporary.newFolder("app");
        Files.write(new File(app, "source-preimage-backups").toPath(), bytes("blocking file"));
        Source source = new Source();
        AppSelectTarget target = new AppSelectTarget(source, app, "working-A", "tree-A");
        SelectStorage storage = new SelectStorage(root);
        SelectStorage.ExportPlan plan = storage.planExport(target, storage.readWorking().sha256);
        try { storage.executeExport(target, plan); fail(); } catch (IOException expected) { }
        assertEquals(0, source.writes);
        assertArrayEquals(bytes("source"), source.bytes);
    }
}
