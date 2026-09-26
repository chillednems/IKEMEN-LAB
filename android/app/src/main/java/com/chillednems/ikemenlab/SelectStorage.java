package com.chillednems.ikemenlab;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Byte-exact, managed select.def versions and guarded writes for one imported library. */
public final class SelectStorage {
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    private final File root, select, versions;
    private final LibraryFiles.Node diagnosticRoot;
    public SelectStorage(File root) { this(root, LibraryFiles.local(root)); }
    public SelectStorage(File root, LibraryFiles.Node diagnosticRoot) {
        this.root = root;
        this.diagnosticRoot = diagnosticRoot;
        this.select = RosterStore.selectFile(root);
        this.versions = new File(select.getParentFile(), "select-backups");
    }

    public static final class Snapshot {
        public final byte[] bytes;
        public final String sha256;
        Snapshot(byte[] bytes) { this.bytes = bytes.clone(); this.sha256 = hash(bytes); }
    }
    public static final class Version {
        public final String id, sha256, reason, origin;
        public final long createdAt, byteCount;
        Version(String id, Properties p, long byteCount) {
            this.id = id; this.sha256 = p.getProperty("sha256"); this.reason = p.getProperty("reason");
            this.origin = p.getProperty("origin"); this.createdAt = Long.parseLong(p.getProperty("createdAt"));
            this.byteCount = byteCount;
        }
    }
    public static final class CommitResult {
        public final boolean changed;
        public final String sha256;
        public final Version previousVersion;
        CommitResult(boolean changed, String sha256, Version previousVersion) {
            this.changed = changed; this.sha256 = sha256; this.previousVersion = previousVersion;
        }
    }
    /** replace must address the exact existing document. backup must read back and verify its preimage. */
    public interface External {
        String identity() throws IOException;
        byte[] read() throws IOException;
        void replace(byte[] bytes) throws IOException;
        String backup(byte[] preimage, String transactionId) throws IOException;
        List<Version> listBackups() throws IOException;
        byte[] readBackup(String versionId) throws IOException;
        void pruneBackups(Integer retention, String protectedVersionId) throws IOException;
        default String backupIdentity() throws IOException { return "source:" + identity(); }
        default String backupLabel() throws IOException { return "data/select-backups beside " + identity(); }
        default List<BackupRef> listExternalBackups() throws IOException {
            List<BackupRef> result = new ArrayList<>();
            for (Version version : listBackups())
                result.add(new BackupRef(BackupRef.Origin.SOURCE, version, backupIdentity(), backupLabel()));
            return result;
        }
        default byte[] readBackup(BackupRef ref) throws IOException {
            return readBackup(ref.id, ref.storeIdentity);
        }
        default byte[] readBackup(String versionId, String storeIdentity) throws IOException {
            if (!backupIdentity().equals(storeIdentity)) throw new IOException("Backup location changed; select it again");
            return readBackup(versionId);
        }
    }
    public static final class BackupRef {
        public enum Origin { WORKING, SOURCE }
        public final Origin origin;
        public final String id, sha256, storeIdentity, storeLabel;
        public final long createdAt, byteCount;
        BackupRef(Origin origin, Version version) { this(origin, version, "working", "App working undo and recovery"); }
        BackupRef(Origin origin, Version version, String storeIdentity, String storeLabel) {
            this.origin = origin; this.id = version.id; this.sha256 = version.sha256;
            this.createdAt = version.createdAt; this.byteCount = version.byteCount;
            this.storeIdentity = storeIdentity; this.storeLabel = storeLabel;
        }
    }
    public static final class ExportPlan {
        public final String targetIdentity, workingHash, sourceHash, backupDestination;
        public final byte[] replacement;
        public final BackupRef selectedBackup;
        public final List<RosterDiagnostics.Warning> missingWarnings;
        public final String backupIdentity;
        ExportPlan(String targetIdentity, byte[] replacement, byte[] source, LibraryFiles.Node root,
                   String backupIdentity, String backupDestination) throws IOException {
            this(targetIdentity, replacement, source, root, null, backupIdentity, backupDestination);
        }
        ExportPlan(String targetIdentity, byte[] replacement, byte[] source, LibraryFiles.Node root,
                   BackupRef selectedBackup, String backupIdentity, String backupDestination) throws IOException {
            this.targetIdentity = targetIdentity; this.replacement = replacement.clone();
            this.workingHash = hash(replacement); this.sourceHash = hash(source);
            this.selectedBackup = selectedBackup;
            this.backupIdentity = backupIdentity; this.backupDestination = backupDestination;
            this.missingWarnings = RosterDiagnostics.scan(root, replacement);
        }
    }
    public static final class ExportResult {
        public final boolean changed;
        public final String targetIdentity, sha256, backupLocation;
        public final boolean cleanupPending;
        ExportResult(boolean changed, String targetIdentity, String sha256, String backupLocation) {
            this(changed, targetIdentity, sha256, backupLocation, false);
        }
        ExportResult(boolean changed, String targetIdentity, String sha256, String backupLocation, boolean cleanupPending) {
            this.changed = changed; this.targetIdentity = targetIdentity;
            this.sha256 = sha256; this.backupLocation = backupLocation; this.cleanupPending = cleanupPending;
        }
    }
    public static final class RecoveryRequiredException extends IOException {
        RecoveryRequiredException(String message, Throwable cause) { super(message, cause); }
    }

    public Snapshot readWorking() throws IOException { return new Snapshot(readLimited(select)); }
    /** null retention means unlimited; positive N keeps newest N managed versions. */
    public synchronized CommitResult commitWorking(String expectedSha256, byte[] replacement,
                                                    String reason, Integer retention) throws IOException {
        return commitWorking(expectedSha256, replacement, reason, retention, null, false);
    }
    private synchronized CommitResult commitWorking(String expectedSha256, byte[] replacement,
                                                    String reason, Integer retention, String protectedVersion,
                                                    boolean completingSourceRestore) throws IOException {
        checked(replacement); validateRetention(retention);
        try (Locked ignored = lock()) {
            if (pendingRestore().exists()) {
                if (!completingSourceRestore) throw new IOException("Resolve pending source restore before editing");
                Properties pending = readProperties(pendingRestore());
                if (!expectedSha256.equals(pending.getProperty("beforeLocal"))
                        || !hash(replacement).equals(pending.getProperty("after"))
                        || !reason.equals("restore-source:" + pending.getProperty("version")))
                    throw new IOException("Pending source restore does not match this edit");
            } else if (completingSourceRestore) throw new IOException("No pending source restore");
            if (pendingJournal().exists() && !completingSourceRestore)
                throw new IOException("Resolve pending export before editing");
            byte[] old = readLimited(select);
            if (!hash(old).equals(expectedSha256)) throw new IOException("Working select.def changed; reload");
            if (MessageDigest.isEqual(old, replacement)) return new CommitResult(false, expectedSha256, null);
            migrateLegacy();
            Version before = select.isFile() ? saveVersion(old, reason, "working") : null;
            replaceLocal(select, replacement);
            if (!MessageDigest.isEqual(readLimited(select), replacement)) throw new IOException("Working select.def readback failed");
            prune(retention, protectedVersion, before == null ? null : before.id);
            return new CommitResult(true, hash(replacement), before);
        }
    }
    public synchronized CommitResult restoreLoadOnly(String id, String expectedHash, Integer retention) throws IOException {
        return commitWorking(expectedHash, readVersion(id), "restore:" + id, retention, id, false);
    }
    public synchronized List<Version> listVersions() throws IOException {
        try (Locked ignored = lock()) { migrateLegacy(); return verifiedVersions(); }
    }
    public synchronized byte[] readVersion(String id) throws IOException {
        if (id == null || !id.matches("[a-zA-Z0-9_-]+")) throw new IOException("Invalid backup ID");
        try (Locked ignored = lock()) {
            if (loadVersion(id) == null) throw new IOException("Backup missing or failed verification");
            return readLimited(new File(versions, id + ".bin"));
        }
    }
    public List<BackupRef> listAllBackups(External target) throws IOException {
        List<BackupRef> result = new ArrayList<>();
        for (Version version : listVersions()) result.add(new BackupRef(BackupRef.Origin.WORKING, version));
        if (target != null) result.addAll(target.listExternalBackups());
        result.sort(Comparator.comparingLong((BackupRef ref) -> ref.createdAt).reversed());
        return result;
    }
    private byte[] readBackup(External target, BackupRef ref) throws IOException {
        byte[] selected = ref.origin == BackupRef.Origin.WORKING ? readVersion(ref.id) : target.readBackup(ref);
        if (!hash(selected).equals(ref.sha256)) throw new IOException("Selected backup failed verification");
        return selected;
    }
    public CommitResult restoreLoadOnly(External target, BackupRef ref, String expectedHash, Integer retention) throws IOException {
        byte[] selected = readBackup(target, ref);
        return commitWorking(expectedHash, selected, "restore:" + ref.origin + ":" + ref.id,
                retention, ref.origin == BackupRef.Origin.WORKING ? ref.id : null, false);
    }
    public ExportPlan planExport(External target, String expectedWorkingHash) throws IOException {
        Snapshot working = readWorking();
        if (!working.sha256.equals(expectedWorkingHash)) throw new IOException("Working select.def changed; refresh preview");
        return new ExportPlan(target.identity(), working.bytes, checked(target.read()), diagnosticRoot,
                target.backupIdentity(), target.backupLabel());
    }
    /** Restore preview uses an immutable version as source; the working copy is handled separately. */
    public ExportPlan planRestoreSource(External target, String versionId) throws IOException {
        for (Version version : listVersions()) if (version.id.equals(versionId))
            return planRestoreSource(target, new BackupRef(BackupRef.Origin.WORKING, version));
        throw new IOException("Selected backup is missing");
    }
    public ExportPlan planRestoreSource(External target, BackupRef ref) throws IOException {
        return new ExportPlan(target.identity(), readBackup(target, ref), checked(target.read()), diagnosticRoot,
                ref, target.backupIdentity(), target.backupLabel());
    }
    public static final class RestoreResult {
        public final ExportResult source;
        public final CommitResult working;
        RestoreResult(ExportResult source, CommitResult working) { this.source = source; this.working = working; }
    }
    /** A source restore backs up its current bytes, then applies the immutable version locally. */
    public RestoreResult restoreSourceAndWorking(External target, String versionId, String expectedWorkingHash,
                                                ExportPlan plan, Integer retention) throws IOException {
        for (Version version : listVersions()) if (version.id.equals(versionId))
            return restoreSourceAndWorking(target, new BackupRef(BackupRef.Origin.WORKING, version),
                    expectedWorkingHash, plan, retention);
        throw new IOException("Selected backup is missing");
    }
    public RestoreResult restoreSourceAndWorking(External target, BackupRef selectedBackup, String expectedWorkingHash,
                                                ExportPlan plan, Integer retention) throws IOException {
        validateRetention(retention);
        byte[] chosen = readBackup(target, selectedBackup);
        if (!hash(chosen).equals(plan.workingHash) || !target.identity().equals(plan.targetIdentity))
            throw new IOException("Restore selection changed; refresh preview");
        if (plan.selectedBackup == null || !plan.selectedBackup.id.equals(selectedBackup.id)
                || plan.selectedBackup.origin != selectedBackup.origin
                || !plan.selectedBackup.storeIdentity.equals(selectedBackup.storeIdentity))
            throw new IOException("Restore backup changed; refresh preview");
        if (!readWorking().sha256.equals(expectedWorkingHash)) throw new IOException("Working select.def changed");
        Properties pending = new Properties();
        pending.setProperty("version", selectedBackup.id); pending.setProperty("origin", selectedBackup.origin.name());
        pending.setProperty("storeIdentity", selectedBackup.storeIdentity);
        pending.setProperty("target", plan.targetIdentity);
        pending.setProperty("beforeLocal", expectedWorkingHash); pending.setProperty("after", plan.workingHash);
        pending.setProperty("beforeSource", plan.sourceHash);
        pending.setProperty("retention", retention == null ? "unlimited" : Integer.toString(retention));
        try (Locked ignored = lock()) {
            if (pendingRestore().exists() || pendingJournal().exists()) throw new IOException("Resolve pending restore/export first");
            writeProperties(pendingRestore(), pending);
        }
        try {
            ExportResult source = executeExport(target, plan, true, false, retention);
            CommitResult working = commitWorking(expectedWorkingHash, chosen, "restore-source:" + selectedBackup.id,
                    retention, selectedBackup.origin == BackupRef.Origin.WORKING ? selectedBackup.id : null, true);
            Files.deleteIfExists(pendingRestore().toPath());
            return new RestoreResult(source, working);
        } catch (IOException failure) {
            // If the provider still holds its old bytes, no cross-store recovery is needed.
            try {
                if (hash(checked(target.read())).equals(plan.sourceHash)
                        && !plan.sourceHash.equals(plan.workingHash))
                    Files.deleteIfExists(pendingRestore().toPath());
            } catch (IOException ignored) { }
            throw failure;
        }
    }
    /** A restart must surface this status before any new restore or export. */
    public String inspectPendingRestore(External target) throws IOException {
        if (!pendingRestore().isFile()) return "NONE";
        Properties pending = readProperties(pendingRestore());
        if (!target.identity().equals(pending.getProperty("target"))) return "OTHER_TARGET";
        String external = hash(checked(target.read()));
        String local = readWorking().sha256;
        if (external.equals(pending.getProperty("after")) && local.equals(pending.getProperty("after"))) {
            Files.deleteIfExists(pendingRestore().toPath()); return "COMPLETED";
        }
        if (external.equals(pending.getProperty("after")) && local.equals(pending.getProperty("beforeLocal")))
            return "LOCAL_RESTORE_REQUIRED";
        if (external.equals(pending.getProperty("beforeSource"))) {
            Files.deleteIfExists(pendingRestore().toPath()); return "NOT_APPLIED";
        }
        return "RECOVERY_REQUIRED";
    }
    /** Identifies the exact external version needed to finish an interrupted local restore. */
    public String pendingRestoreBackupStoreIdentity() throws IOException {
        if (!pendingRestore().isFile()) return null;
        Properties pending = readProperties(pendingRestore());
        if (!"SOURCE".equals(pending.getProperty("origin"))) return null;
        return pending.getProperty("storeIdentity", "source:" + pending.getProperty("target", ""));
    }
    public void verifyPendingRestoreBackup(External target) throws IOException {
        if (!pendingRestore().isFile()) throw new IOException("No pending source restore");
        Properties pending = readProperties(pendingRestore());
        if (!"SOURCE".equals(pending.getProperty("origin"))) return;
        String store = pendingRestoreBackupStoreIdentity();
        byte[] bytes = target.readBackup(pending.getProperty("version"), store);
        if (!hash(bytes).equals(pending.getProperty("after")))
            throw new IOException("The selected restore backup is missing or changed");
    }
    public CommitResult completePendingRestore(External target) throws IOException {
        if (!"LOCAL_RESTORE_REQUIRED".equals(inspectPendingRestore(target)))
            throw new IOException("Pending source restore is not ready to complete");
        Properties pending = readProperties(pendingRestore());
        Integer retention = pending.getProperty("retention").equals("unlimited") ? null
                : Integer.parseInt(pending.getProperty("retention"));
        BackupRef.Origin origin = BackupRef.Origin.valueOf(pending.getProperty("origin"));
        String id = pending.getProperty("version");
        byte[] chosen = origin == BackupRef.Origin.WORKING ? readVersion(id)
                : target.readBackup(id, pending.getProperty("storeIdentity", "source:" + target.identity()));
        if (!hash(chosen).equals(pending.getProperty("after"))) throw new IOException("Restore backup changed");
        CommitResult result = commitWorking(pending.getProperty("beforeLocal"), chosen,
                "restore-source:" + id, retention, origin == BackupRef.Origin.WORKING ? id : null, true);
        Files.deleteIfExists(pendingRestore().toPath());
        return result;
    }
    public static final class AbandonResult {
        public final String sourceBackupLocation, workingVersionId;
        AbandonResult(String sourceBackupLocation, String workingVersionId) {
            this.sourceBackupLocation = sourceBackupLocation;
            this.workingVersionId = workingVersionId;
        }
    }
    /** Preserve both current copies before discarding an irreconcilable restore journal. */
    public synchronized AbandonResult abandonPendingRestore(External target) throws IOException {
        try (Locked ignored = lock()) {
            if (pendingJournal().exists()) throw new IOException("Resolve pending export before abandoning restore");
            if (!pendingRestore().isFile()) throw new IOException("No pending source restore");
            Properties pending = readProperties(pendingRestore());
            if (!target.identity().equals(pending.getProperty("target")))
                throw new IOException("Recovery target differs from pending restore");
            byte[] source = checked(target.read());
            byte[] working = readLimited(select);
            String sourceHash = hash(source), workingHash = hash(working);
            if ((sourceHash.equals(pending.getProperty("after")) && workingHash.equals(pending.getProperty("after")))
                    || (sourceHash.equals(pending.getProperty("after")) && workingHash.equals(pending.getProperty("beforeLocal")))
                    || sourceHash.equals(pending.getProperty("beforeSource")))
                throw new IOException("Pending restore has a normal completion path; inspect Recovery again");
            String transaction = UUID.randomUUID().toString();
            String sourceLocation = target.backup(source, transaction);
            if (sourceLocation == null || sourceLocation.isEmpty()
                    || !MessageDigest.isEqual(checked(target.readBackup(transaction, target.backupIdentity())), source))
                throw new IOException("Current source backup failed verification");
            Version localVersion = saveVersion(working, "abandoned source restore", "working");
            if (localVersion == null || !workingHash.equals(localVersion.sha256))
                throw new IOException("Current working backup failed verification");
            if (!MessageDigest.isEqual(checked(target.read()), source)
                    || !MessageDigest.isEqual(readLimited(select), working))
                throw new IOException("Source or working roster changed during recovery; journal retained");
            Files.delete(pendingRestore().toPath());
            return new AbandonResult(sourceLocation, localVersion.id);
        }
    }
    /** Generic document providers cannot promise crash-atomic replacement. */
    public synchronized ExportResult executeExport(External target, ExportPlan plan) throws IOException {
        return executeExport(target, plan, false, false, null);
    }
    public synchronized ExportResult executeExport(External target, ExportPlan plan, Integer retention) throws IOException {
        validateRetention(retention);
        return executeExport(target, plan, false, false, retention);
    }
    /** Explicit force is accepted only for a reviewed no-change export. */
    public synchronized ExportResult executeExportAnyway(External target, ExportPlan plan, Integer retention) throws IOException {
        validateRetention(retention);
        if (!plan.sourceHash.equals(plan.workingHash)) throw new IOException("Export anyway is only for a reviewed no-change export");
        return executeExport(target, plan, false, true, retention);
    }
    private synchronized ExportResult executeExport(External target, ExportPlan plan, boolean restoring,
                                                    boolean forceNoChange,
                                                    Integer retention) throws IOException {
        if (!target.identity().equals(plan.targetIdentity)) throw new IOException("Export destination changed");
        if (!target.backupIdentity().equals(plan.backupIdentity))
            throw new IOException("Backup destination changed; review export again");
        // A normal export must still match the working copy. A restore plan can use an older immutable version.
        if (!restoring && !readWorking().sha256.equals(plan.workingHash))
            throw new IOException("Working select.def changed; refresh export preview");
        if (restoring && (plan.selectedBackup == null ||
                !hash(readBackup(target, plan.selectedBackup)).equals(plan.workingHash)))
            throw new IOException("Selected restore version changed; refresh restore preview");
        byte[] old = checked(target.read());
        if (!hash(old).equals(plan.sourceHash)) throw new IOException("Destination changed; refresh preview");
        if (!forceNoChange && MessageDigest.isEqual(old, plan.replacement))
            return new ExportResult(false, plan.targetIdentity, plan.workingHash, null);
        try (Locked ignored = lock()) {
            if (pendingJournal().exists()) throw new IOException("Resolve pending export first");
            if (pendingRestore().exists() && !restoring) throw new IOException("Resolve pending restore first");
            if (!restoring && !readWorking().sha256.equals(plan.workingHash))
                throw new IOException("Working select.def changed; refresh export preview");
            if (restoring && (plan.selectedBackup == null ||
                    !hash(readBackupUnderLock(target, plan.selectedBackup)).equals(plan.workingHash)))
                throw new IOException("Selected restore version changed; refresh restore preview");
            String tx = UUID.randomUUID().toString();
            File preimage = new File(versions, "recovery-" + tx + ".bin");
            writeSynced(preimage, old);
            Properties journal = new Properties();
            journal.setProperty("target", plan.targetIdentity);
            journal.setProperty("before", plan.sourceHash);
            journal.setProperty("after", plan.workingHash);
            journal.setProperty("preimage", preimage.getName());
            journal.setProperty("phase", "prepared");
            writeJournal(journal);
            try {
                String backup = target.backup(old, tx);
                if (backup == null || backup.isEmpty()) throw new IOException("External backup was not verified");
                journal.setProperty("backup", backup);
                journal.setProperty("phase", "backed_up");
                writeJournal(journal);
                if (!hash(checked(target.read())).equals(plan.sourceHash))
                    throw new IOException("Destination changed before write");
                journal.setProperty("phase", "writing");
                writeJournal(journal);
                target.replace(plan.replacement);
                if (!MessageDigest.isEqual(checked(target.read()), plan.replacement))
                    throw new IOException("Destination readback failed");
                boolean cleanupPending = false;
                try { clearJournal(preimage); } catch (IOException cleanup) { cleanupPending = true; }
                String protectedSource = restoring && plan.selectedBackup.origin == BackupRef.Origin.SOURCE
                        ? plan.selectedBackup.id : null;
                try { target.pruneBackups(retention, protectedSource); } catch (IOException cleanup) { cleanupPending = true; }
                return new ExportResult(true, plan.targetIdentity, plan.workingHash, backup, cleanupPending);
            } catch (IOException failure) {
                if ("writing".equals(journal.getProperty("phase"))) {
                    try {
                        if (!MessageDigest.isEqual(checked(target.read()), old)) {
                            target.replace(old);
                            if (!MessageDigest.isEqual(checked(target.read()), old)) throw new IOException("Rollback readback failed");
                        }
                        clearJournal(preimage);
                    } catch (IOException rollback) {
                        failure.addSuppressed(rollback);
                        throw new RecoveryRequiredException("Export may be partial; private recovery retained at " + preimage, failure);
                    }
                } else clearJournal(preimage);
                throw failure;
            }
        }
    }
    /** Read-only restart reconciliation; a partial or unrelated source change needs explicit recovery. */
    public synchronized String inspectPending(External target) throws IOException {
        if (!pendingJournal().isFile()) return "NONE";
        Properties journal = readJournal();
        if (!target.identity().equals(journal.getProperty("target"))) return "OTHER_TARGET";
        String actual = hash(checked(target.read()));
        File preimage = new File(versions, journal.getProperty("preimage", ""));
        if (actual.equals(journal.getProperty("before"))) { clearJournal(preimage); return "ROLLED_BACK"; }
        if (actual.equals(journal.getProperty("after"))) { clearJournal(preimage); return "COMMITTED"; }
        return "RECOVERY_REQUIRED";
    }
    /** Call only after showing the pending destination and recovery preimage to the user. */
    public synchronized void restorePendingPreimage(External target) throws IOException {
        Properties journal = readJournal();
        if (!target.identity().equals(journal.getProperty("target"))) throw new IOException("Recovery target differs");
        File preimage = new File(versions, journal.getProperty("preimage", ""));
        byte[] bytes = readLimited(preimage);
        if (!hash(bytes).equals(journal.getProperty("before"))) throw new IOException("Recovery preimage failed verification");
        target.replace(bytes);
        if (!MessageDigest.isEqual(checked(target.read()), bytes)) throw new IOException("Recovery readback failed");
        clearJournal(preimage);
    }

    public static String hash(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder(64);
            for (byte b : digest) result.append(String.format(Locale.ROOT, "%02x", b & 255));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static byte[] checked(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length > MAX_BYTES) throw new IOException("select.def exceeds 2 MB editing limit");
        return bytes;
    }
    private static byte[] readLimited(File file) throws IOException {
        if (!file.isFile()) return new byte[0];
        if (file.length() > MAX_BYTES) throw new IOException("select.def exceeds 2 MB editing limit");
        return checked(Files.readAllBytes(file.toPath()));
    }
    private static void validateRetention(Integer retention) throws IOException {
        if (retention != null && retention < 1) throw new IOException("Retention must be a positive number");
    }
    private void ensureVersions() throws IOException {
        if (!versions.isDirectory() && !versions.mkdirs()) throw new IOException("Could not create select-backups");
    }
    private Locked lock() throws IOException {
        ensureVersions();
        FileChannel channel = FileChannel.open(new File(versions, ".lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try { return new Locked(channel, channel.lock()); }
        catch (IOException | RuntimeException failure) { channel.close(); throw failure; }
    }
    private static final class Locked implements AutoCloseable {
        final FileChannel channel; final FileLock lock;
        Locked(FileChannel channel, FileLock lock) { this.channel = channel; this.lock = lock; }
        @Override public void close() throws IOException { lock.release(); channel.close(); }
    }
    private static void writeSynced(File file, byte[] bytes) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(bytes); out.getFD().sync(); }
    }
    private static void replaceLocal(File target, byte[] bytes) throws IOException {
        Path temp = Files.createTempFile(target.getParentFile().toPath(), ".select-", ".tmp");
        try {
            writeSynced(temp.toFile(), bytes);
            Files.move(temp, target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (FileChannel directory = FileChannel.open(target.getParentFile().toPath(), StandardOpenOption.READ)) {
                directory.force(true);
            } catch (IOException ignored) { } // Some Android filesystems do not allow directory fsync.
        } finally { Files.deleteIfExists(temp); }
    }
    private Version saveVersion(byte[] bytes, String reason, String origin) throws IOException {
        ensureVersions();
        long createdAt = System.currentTimeMillis();
        for (Version existing : verifiedVersions()) createdAt = Math.max(createdAt, existing.createdAt + 1);
        String id = Long.toString(createdAt) + "-" + UUID.randomUUID();
        File body = new File(versions, id + ".bin"), meta = new File(versions, id + ".properties");
        File temp = new File(versions, "." + id + ".tmp");
        writeSynced(temp, bytes);
        if (!MessageDigest.isEqual(readLimited(temp), bytes)) throw new IOException("Backup readback failed");
        Files.move(temp.toPath(), body.toPath(), StandardCopyOption.ATOMIC_MOVE);
        Properties p = new Properties();
        p.setProperty("sha256", hash(bytes)); p.setProperty("bytes", Integer.toString(bytes.length));
        p.setProperty("reason", reason == null ? "edit" : reason);
        ManagedBackupFormat.mark(p, id, origin);
        p.setProperty("createdAt", Long.toString(createdAt));
        File tempMeta = new File(versions, "." + id + ".properties.tmp");
        try (FileOutputStream out = new FileOutputStream(tempMeta)) { p.store(out, "managed select.def version"); out.getFD().sync(); }
        Files.move(tempMeta.toPath(), meta.toPath(), StandardCopyOption.ATOMIC_MOVE);
        return loadVersion(id);
    }
    private Version loadVersion(String id) throws IOException {
        File body = new File(versions, id + ".bin"), meta = new File(versions, id + ".properties");
        if (!body.isFile() || !meta.isFile()) return null;
        Properties p = new Properties();
        try (InputStream input = Files.newInputStream(meta.toPath())) { p.load(input); }
        byte[] bytes = readLimited(body);
        if (!ManagedBackupFormat.validLocal(id, p, bytes)) return null;
        try { return new Version(id, p, bytes.length); } catch (NumberFormatException invalid) { return null; }
    }
    private List<Version> verifiedVersions() throws IOException {
        List<Version> result = new ArrayList<>();
        File[] files = versions.listFiles((dir, name) -> name.endsWith(".properties") && !name.equals("pending-export.properties"));
        if (files != null) for (File file : files) {
            String id = file.getName().substring(0, file.getName().length() - ".properties".length());
            Version v = loadVersion(id); if (v != null) result.add(v);
        }
        result.sort(Comparator.comparingLong((Version v) -> v.createdAt).reversed().thenComparing(v -> v.id));
        return result;
    }
    private void prune(Integer retention, String selected, String before) throws IOException {
        if (retention == null) return;
        List<Version> all = verifiedVersions();
        for (int i = retention; i < all.size(); i++) {
            Version v = all.get(i);
            // A restore keeps its selected immutable version and the immediate preimage,
            // even when that temporarily exceeds a finite retention target.
            if (selected != null && (v.id.equals(selected) || v.id.equals(before))) continue;
            Files.deleteIfExists(new File(versions, v.id + ".properties").toPath());
            Files.deleteIfExists(new File(versions, v.id + ".bin").toPath());
        }
    }
    private void migrateLegacy() throws IOException {
        File[] old = select.getParentFile().listFiles((dir, name) -> name.startsWith("select.def.backup.") && name.endsWith(".bak"));
        if (old == null) return;
        for (File file : old) {
            byte[] bytes = readLimited(file);
            String markerName = ".migrated-" + hash(file.getName().getBytes(java.nio.charset.StandardCharsets.UTF_8)) + ".properties";
            File marker = new File(versions, markerName);
            Properties oldMarker = new Properties();
            if (marker.isFile()) try (InputStream input = Files.newInputStream(marker.toPath())) { oldMarker.load(input); }
            if (hash(bytes).equals(oldMarker.getProperty("sha256"))) continue;
            Version migrated = saveVersion(bytes, "legacy migration", "legacy:" + file.getName());
            if (migrated == null) throw new IOException("Legacy backup migration failed verification");
            Properties next = new Properties();
            next.setProperty("sha256", hash(bytes));
            File temp = new File(versions, "." + markerName + ".tmp");
            try (FileOutputStream out = new FileOutputStream(temp)) { next.store(out, "legacy backup migrated"); out.getFD().sync(); }
            Files.move(temp.toPath(), marker.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
    }
    private byte[] readBackupUnderLock(External target, BackupRef ref) throws IOException {
        byte[] bytes;
        if (ref.origin == BackupRef.Origin.WORKING) {
            if (loadVersion(ref.id) == null) throw new IOException("Selected backup changed");
            bytes = readLimited(new File(versions, ref.id + ".bin"));
        } else bytes = target.readBackup(ref);
        if (!hash(bytes).equals(ref.sha256)) throw new IOException("Selected backup changed");
        return bytes;
    }
    private File pendingJournal() { return new File(versions, "pending-export.properties"); }
    private File pendingRestore() { return new File(versions, "pending-restore.properties"); }
    private void writeProperties(File destination, Properties properties) throws IOException {
        File temp = new File(versions, "." + destination.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) { properties.store(out, "recoverable select.def operation"); out.getFD().sync(); }
        Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
    private static Properties readProperties(File file) throws IOException {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file.toPath())) { properties.load(input); }
        return properties;
    }
    private void writeJournal(Properties journal) throws IOException {
        File temp = new File(versions, ".pending-export.tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) { journal.store(out, "recoverable external write"); out.getFD().sync(); }
        Files.move(temp.toPath(), pendingJournal().toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
    private Properties readJournal() throws IOException {
        Properties p = new Properties();
        try (InputStream input = Files.newInputStream(pendingJournal().toPath())) { p.load(input); }
        return p;
    }
    private void clearJournal(File preimage) throws IOException {
        Files.deleteIfExists(pendingJournal().toPath());
        Files.deleteIfExists(preimage.toPath());
    }
}
