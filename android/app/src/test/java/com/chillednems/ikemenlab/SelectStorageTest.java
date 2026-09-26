package com.chillednems.ikemenlab;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

import static org.junit.Assert.*;

public final class SelectStorageTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private SelectStorage storage(String initial) throws IOException {
        File root = folder.newFolder();
        File data = new File(root, "data");
        assertTrue(data.mkdir());
        Files.write(new File(data, "select.def").toPath(), bytes(initial));
        return new SelectStorage(root);
    }
    private static final class Fake implements SelectStorage.External {
        byte[] contents, backup;
        int writes, backupCalls;
        boolean partialFailure, rollbackFailure, badReadback, outsideEdit, changeAfterBackup, backupFailure;
        Fake(String value) { contents = bytes(value); }
        @Override public String identity() { return "fake://data/select.def"; }
        @Override public byte[] read() {
            if (outsideEdit) { outsideEdit = false; contents = bytes("outside"); }
            return contents.clone();
        }
        @Override public void replace(byte[] value) throws IOException {
            writes++;
            if (rollbackFailure && writes > 1) throw new IOException("rollback rejected");
            if (partialFailure && writes == 1) {
                contents = Arrays.copyOf(value, Math.max(1, value.length / 2));
                throw new IOException("provider stopped halfway");
            }
            contents = value.clone();
            if (badReadback && writes == 1) contents = bytes("wrong");
        }
        @Override public String backup(byte[] value, String id) throws IOException {
            if (backupFailure) throw new IOException("backup close failed");
            backupCalls++; backup = value.clone();
            if (changeAfterBackup) outsideEdit = true;
            return "fake://data/select-backups/" + id;
        }
    }
    @Test public void noOpDoesNotCreateVersionAndFiniteRetentionPrunesOnlyManaged() throws Exception {
        SelectStorage store = storage("one");
        String hash = store.readWorking().sha256;
        assertFalse(store.commitWorking(hash, bytes("one"), "edit", null).changed);
        assertEquals(0, store.listVersions().size());
        File unmanaged = new File(RosterStore.selectFile(storeRoot(store)).getParentFile(), "select-backups/my-own.bak");
        Files.write(unmanaged.toPath(), bytes("keep"));
        hash = store.commitWorking(hash, bytes("two"), "edit", 1).sha256;
        store.commitWorking(hash, bytes("three"), "edit", 1);
        assertEquals(1, store.listVersions().size());
        assertEquals("two", new String(store.readVersion(store.listVersions().get(0).id), StandardCharsets.UTF_8));
        assertEquals("keep", new String(Files.readAllBytes(unmanaged.toPath()), StandardCharsets.UTF_8));
    }
    @Test public void restoreIsByteExactAndLeavesVersionImmutable() throws Exception {
        byte[] first = new byte[] {(byte)0xef,(byte)0xbb,(byte)0xbf,'[','C',']','\r','\n',(byte)0x80,'\r'};
        SelectStorage store = storage("placeholder");
        String start = store.readWorking().sha256;
        store.commitWorking(start, first, "seed", null);
        SelectStorage.CommitResult second = store.commitWorking(store.readWorking().sha256, bytes("later"), "edit", null);
        String versionId = second.previousVersion.id;
        store.restoreLoadOnly(versionId, store.readWorking().sha256, null);
        assertArrayEquals(first, store.readWorking().bytes);
        assertArrayEquals(first, store.readVersion(versionId));
        assertEquals(3, store.listVersions().size());
    }
    @Test public void migratesLegacyWithoutDeletingOriginal() throws Exception {
        SelectStorage store = storage("now");
        File legacy = new File(RosterStore.selectFile(storeRoot(store)).getParentFile(), "select.def.backup.old.bak");
        Files.write(legacy.toPath(), bytes("before"));
        assertEquals(1, store.listVersions().size());
        assertEquals(1, store.listVersions().size());
        assertArrayEquals(bytes("before"), Files.readAllBytes(legacy.toPath()));
        store.commitWorking(store.readWorking().sha256, bytes("next"), "edit", 1);
        assertEquals(1, store.listVersions().size()); // Pruned legacy version is not re-imported.
        assertArrayEquals(bytes("before"), Files.readAllBytes(legacy.toPath()));
    }
    @Test public void externalEditRaceNeverWritesDestination() throws Exception {
        SelectStorage store = storage("working");
        Fake target = new Fake("source");
        SelectStorage.ExportPlan plan = store.planExport(target, store.readWorking().sha256);
        target.contents = bytes("outside");
        try { store.executeExport(target, plan); fail(); } catch (IOException expected) { }
        assertEquals(0, target.writes);
        assertArrayEquals(bytes("outside"), target.contents);
    }
    @Test public void externalEditAfterBackupNeverRollsBackOutsideEdit() throws Exception {
        SelectStorage store = storage("working");
        Fake target = new Fake("source");
        SelectStorage.ExportPlan plan = store.planExport(target, store.readWorking().sha256);
        target.changeAfterBackup = true;
        try { store.executeExport(target, plan); fail(); } catch (IOException expected) { }
        assertEquals(0, target.writes);
        assertArrayEquals(bytes("outside"), target.contents);
    }
    @Test public void sourceBackupFailureLeavesOriginalUntouched() throws Exception {
        SelectStorage store = storage("working");
        Fake target = new Fake("source");
        SelectStorage.ExportPlan plan = store.planExport(target, store.readWorking().sha256);
        target.backupFailure = true;
        try { store.executeExport(target, plan); fail(); } catch (IOException expected) { }
        assertEquals(0, target.writes);
        assertArrayEquals(bytes("source"), target.contents);
        assertEquals("NONE", store.inspectPending(target));
    }
    @Test public void interruptedProviderWriteRollsBackVerifiedPreimage() throws Exception {
        SelectStorage store = storage("working");
        Fake target = new Fake("source");
        target.partialFailure = true;
        SelectStorage.ExportPlan plan = store.planExport(target, store.readWorking().sha256);
        try { store.executeExport(target, plan); fail(); } catch (IOException expected) { }
        assertArrayEquals(bytes("source"), target.contents);
        assertArrayEquals(bytes("source"), target.backup);
        assertEquals("NONE", store.inspectPending(target));
    }
    @Test public void failedRollbackRetainsRecoverableJournal() throws Exception {
        SelectStorage store = storage("working");
        Fake target = new Fake("source");
        target.partialFailure = true; target.rollbackFailure = true;
        SelectStorage.ExportPlan plan = store.planExport(target, store.readWorking().sha256);
        try { store.executeExport(target, plan); fail(); }
        catch (SelectStorage.RecoveryRequiredException expected) { }
        assertEquals("RECOVERY_REQUIRED", store.inspectPending(target));
        target.rollbackFailure = false;
        store.restorePendingPreimage(target);
        assertArrayEquals(bytes("source"), target.contents);
        assertEquals("NONE", store.inspectPending(target));
    }
    @Test public void sourceRestoreUsesImmutableVersionAndBacksUpPreviousSource() throws Exception {
        SelectStorage store = storage("first");
        store.commitWorking(store.readWorking().sha256, bytes("chosen"), "edit", null);
        SelectStorage.CommitResult changed = store.commitWorking(store.readWorking().sha256, bytes("working"), "edit", null);
        String selected = changed.previousVersion.id;
        Fake target = new Fake("source-current");
        SelectStorage.ExportPlan plan = store.planRestoreSource(target, selected);
        SelectStorage.RestoreResult result = store.restoreSourceAndWorking(target, selected,
                store.readWorking().sha256, plan, null);
        assertTrue(result.source.changed);
        assertArrayEquals(bytes("source-current"), target.backup);
        assertArrayEquals(bytes("chosen"), target.contents);
        assertArrayEquals(bytes("chosen"), store.readWorking().bytes);
        assertArrayEquals(bytes("chosen"), store.readVersion(selected));
        assertEquals("NONE", store.inspectPendingRestore(target));
    }
    private static File storeRoot(SelectStorage store) throws Exception {
        java.lang.reflect.Field field = SelectStorage.class.getDeclaredField("root");
        field.setAccessible(true);
        return (File) field.get(store);
    }
}
