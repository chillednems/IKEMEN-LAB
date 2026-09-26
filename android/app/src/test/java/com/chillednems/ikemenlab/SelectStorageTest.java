package com.chillednems.ikemenlab;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Properties;

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
        final Map<String, byte[]> versions = new LinkedHashMap<>();
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
            backupCalls++; backup = value.clone(); versions.put(id, value.clone());
            if (changeAfterBackup) outsideEdit = true;
            return "fake://data/select-backups/" + id;
        }
        @Override public List<SelectStorage.Version> listBackups() {
            List<SelectStorage.Version> result = new ArrayList<>();
            long time = 1;
            for (Map.Entry<String, byte[]> entry : versions.entrySet()) {
                Properties p = new Properties();
                p.setProperty("sha256", SelectStorage.hash(entry.getValue()));
                p.setProperty("createdAt", Long.toString(time++));
                p.setProperty("reason", "source export"); p.setProperty("origin", "source");
                result.add(new SelectStorage.Version(entry.getKey(), p, entry.getValue().length));
            }
            return result;
        }
        @Override public byte[] readBackup(String id) throws IOException {
            byte[] value = versions.get(id);
            if (value == null) throw new IOException("missing source backup");
            return value.clone();
        }
        @Override public void pruneBackups(Integer retention, String protectedVersionId) {
            if (retention == null) return;
            List<String> ids = new ArrayList<>(versions.keySet());
            for (int i = 0; i < ids.size() - retention; i++)
                if (!ids.get(i).equals(protectedVersionId)) versions.remove(ids.get(i));
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
    @Test public void finiteRetentionLeavesValidHashForeignFilePairUntouched() throws Exception {
        SelectStorage store = storage("one");
        File directory = new File(RosterStore.selectFile(storeRoot(store)).getParentFile(), "select-backups");
        assertTrue(directory.mkdir());
        String id = "1111111111111-11111111-1111-1111-1111-111111111111";
        byte[] foreign = bytes("foreign");
        File body = new File(directory, id + ".bin");
        File metadata = new File(directory, id + ".properties");
        Files.write(body.toPath(), foreign);
        Properties fields = new Properties();
        fields.setProperty("sha256", SelectStorage.hash(foreign));
        fields.setProperty("bytes", Integer.toString(foreign.length));
        fields.setProperty("createdAt", "1");
        fields.setProperty("origin", "working");
        try (java.io.OutputStream output = Files.newOutputStream(metadata.toPath())) { fields.store(output, "user data"); }
        store.commitWorking(store.readWorking().sha256, bytes("two"), "edit", 1);
        store.commitWorking(store.readWorking().sha256, bytes("three"), "edit", 1);
        assertArrayEquals(foreign, Files.readAllBytes(body.toPath()));
        assertTrue(metadata.isFile());
        assertEquals(1, store.listVersions().size());
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
    @Test public void finiteRetentionDoesNotConsumeSelectedRestoreVersionOrCurrentPreimage() throws Exception {
        SelectStorage store = storage("first");
        String selected = store.commitWorking(store.readWorking().sha256, bytes("second"), "edit", null).previousVersion.id;
        store.commitWorking(store.readWorking().sha256, bytes("third"), "edit", null);
        store.restoreLoadOnly(selected, store.readWorking().sha256, 1);
        assertArrayEquals(bytes("first"), store.readVersion(selected));
        assertEquals("first", new String(store.readWorking().bytes, StandardCharsets.UTF_8));
        assertTrue(store.listVersions().size() >= 2);
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
    @Test public void normalExportPlanRejectsWorkingChangeEvenWhenOldHashIsBackedUp() throws Exception {
        SelectStorage store = storage("working");
        Fake target = new Fake("source");
        SelectStorage.ExportPlan plan = store.planExport(target, store.readWorking().sha256);
        store.commitWorking(store.readWorking().sha256, bytes("new working"), "edit", null);
        assertEquals(plan.workingHash, store.listVersions().get(0).sha256);
        try { store.executeExport(target, plan); fail(); } catch (IOException expected) { }
        assertEquals(0, target.backupCalls);
        assertEquals(0, target.writes);
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
    @Test public void sourceVersionsCanLoadLocallyAndFiniteRetentionPrunesAfterVerifiedExport() throws Exception {
        SelectStorage store = storage("working-one");
        Fake target = new Fake("source-old");
        SelectStorage.ExportPlan first = store.planExport(target, store.readWorking().sha256);
        store.executeExport(target, first, 1);
        assertEquals(1, target.versions.size());
        SelectStorage.BackupRef source = null;
        for (SelectStorage.BackupRef ref : store.listAllBackups(target))
            if (ref.origin == SelectStorage.BackupRef.Origin.SOURCE) source = ref;
        assertNotNull(source);
        store.restoreLoadOnly(target, source, store.readWorking().sha256, null);
        assertArrayEquals(bytes("source-old"), store.readWorking().bytes);
        assertArrayEquals(bytes("source-old"), target.readBackup(source.id));
        store.commitWorking(store.readWorking().sha256, bytes("working-two"), "edit", null);
        target.contents = bytes("source-new");
        SelectStorage.ExportPlan second = store.planExport(target, store.readWorking().sha256);
        store.executeExport(target, second, 1);
        assertEquals(1, target.versions.size());
    }
    @Test public void sourceBackupRestoreProtectsSelectionAndCurrentPreimage() throws Exception {
        SelectStorage store = storage("working");
        Fake target = new Fake("source-old");
        store.executeExport(target, store.planExport(target, store.readWorking().sha256), null);
        SelectStorage.BackupRef selected = null;
        for (SelectStorage.BackupRef ref : store.listAllBackups(target))
            if (ref.origin == SelectStorage.BackupRef.Origin.SOURCE) selected = ref;
        assertNotNull(selected);
        target.contents = bytes("source-current");
        SelectStorage.ExportPlan plan = store.planRestoreSource(target, selected);
        store.restoreSourceAndWorking(target, selected, store.readWorking().sha256, plan, 1);
        assertArrayEquals(bytes("source-old"), target.contents);
        assertArrayEquals(bytes("source-current"), target.backup);
        assertArrayEquals(bytes("source-old"), target.readBackup(selected.id));
        assertEquals(2, target.versions.size()); // Chosen version and immediate preimage are protected.
    }
    @Test public void pendingSourceRestoreBlocksToggleAndLoadOnlyWithoutLosingCompletion() throws Exception {
        SelectStorage store = storage("[Characters]\nKFM/KFM.def\n");
        String selectedText = "[Characters]\n;KFM/KFM.def\n";
        store.commitWorking(store.readWorking().sha256, bytes(selectedText), "edit", null);
        SelectStorage.CommitResult result = store.commitWorking(store.readWorking().sha256,
                bytes("[Characters]\nKFM/KFM.def\n"), "edit", null);
        String selectedId = result.previousVersion.id;
        Fake target = new Fake(selectedText);
        Properties pending = new Properties();
        pending.setProperty("version", selectedId);
        pending.setProperty("origin", "WORKING");
        pending.setProperty("target", target.identity());
        pending.setProperty("beforeLocal", store.readWorking().sha256);
        pending.setProperty("after", SelectStorage.hash(bytes(selectedText)));
        pending.setProperty("beforeSource", SelectStorage.hash(bytes("source-before")));
        pending.setProperty("retention", "unlimited");
        File journal = new File(RosterStore.selectFile(storeRoot(store)).getParentFile(), "select-backups/pending-restore.properties");
        try (java.io.OutputStream output = Files.newOutputStream(journal.toPath())) { pending.store(output, "fixture"); }
        assertEquals("LOCAL_RESTORE_REQUIRED", store.inspectPendingRestore(target));
        LibraryScanner.Item item = new LibraryScanner.Item("characters", "KFM/KFM.def", "KFM", "", "", true);
        try { RosterStore.setEnabled(storeRoot(store), item, false); fail(); } catch (IOException expected) { }
        try { store.restoreLoadOnly(selectedId, store.readWorking().sha256, null); fail(); } catch (IOException expected) { }
        assertEquals("LOCAL_RESTORE_REQUIRED", store.inspectPendingRestore(target));
        store.completePendingRestore(target);
        assertArrayEquals(bytes(selectedText), store.readWorking().bytes);
        assertEquals("NONE", store.inspectPendingRestore(target));
    }
    @Test public void pendingExportBlocksLocalEditUntilRecoveryIsInspected() throws Exception {
        SelectStorage store = storage("before");
        File journal = new File(RosterStore.selectFile(storeRoot(store)).getParentFile(),
                "select-backups/pending-export.properties");
        assertTrue(journal.getParentFile().mkdir());
        Files.write(journal.toPath(), bytes("pending"));
        try { store.commitWorking(store.readWorking().sha256, bytes("after"), "edit", null); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("pending export")); }
        assertArrayEquals(bytes("before"), store.readWorking().bytes);
    }
    private static File storeRoot(SelectStorage store) throws Exception {
        java.lang.reflect.Field field = SelectStorage.class.getDeclaredField("root");
        field.setAccessible(true);
        return (File) field.get(store);
    }
}
