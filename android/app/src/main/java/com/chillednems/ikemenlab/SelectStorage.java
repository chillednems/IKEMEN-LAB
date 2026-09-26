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
    public SelectStorage(File root) {
        this.root = root;
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
    }
    public static final class ExportPlan {
        public final String targetIdentity, workingHash, sourceHash, backupDestination;
        public final byte[] replacement;
        public final List<RosterDiagnostics.Warning> missingWarnings;
        ExportPlan(String targetIdentity, byte[] replacement, byte[] source, File root) throws IOException {
            this.targetIdentity = targetIdentity; this.replacement = replacement.clone();
            this.workingHash = hash(replacement); this.sourceHash = hash(source);
            this.backupDestination = "data/select-backups beside " + targetIdentity;
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
        checked(replacement); validateRetention(retention);
        try (Locked ignored = lock()) {
            byte[] old = readLimited(select);
            if (!hash(old).equals(expectedSha256)) throw new IOException("Working select.def changed; reload");
            if (MessageDigest.isEqual(old, replacement)) return new CommitResult(false, expectedSha256, null);
            migrateLegacy();
            Version before = select.isFile() ? saveVersion(old, reason, "working") : null;
            replaceLocal(select, replacement);
            if (!MessageDigest.isEqual(readLimited(select), replacement)) throw new IOException("Working select.def readback failed");
            prune(retention);
            return new CommitResult(true, hash(replacement), before);
        }
    }
    public synchronized CommitResult restoreLoadOnly(String id, String expectedHash, Integer retention) throws IOException {
        return commitWorking(expectedHash, readVersion(id), "restore:" + id, retention);
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
    public ExportPlan planExport(External target, String expectedWorkingHash) throws IOException {
        Snapshot working = readWorking();
        if (!working.sha256.equals(expectedWorkingHash)) throw new IOException("Working select.def changed; refresh preview");
        return new ExportPlan(target.identity(), working.bytes, checked(target.read()), root);
    }
    /** Restore preview uses an immutable version as source; the working copy is handled separately. */
    public ExportPlan planRestoreSource(External target, String versionId) throws IOException {
        return new ExportPlan(target.identity(), readVersion(versionId), checked(target.read()), root);
    }
    public static final class RestoreResult {
        public final ExportResult source;
        public final CommitResult working;
        RestoreResult(ExportResult source, CommitResult working) { this.source = source; this.working = working; }
    }
    /** A source restore backs up its current bytes, then applies the immutable version locally. */
    public RestoreResult restoreSourceAndWorking(External target, String versionId, String expectedWorkingHash,
                                                ExportPlan plan, Integer retention) throws IOException {
        validateRetention(retention);
        byte[] chosen = readVersion(versionId);
        if (!hash(chosen).equals(plan.workingHash) || !target.identity().equals(plan.targetIdentity))
            throw new IOException("Restore selection changed; refresh preview");
        if (!readWorking().sha256.equals(expectedWorkingHash)) throw new IOException("Working select.def changed");
        Properties pending = new Properties();
        pending.setProperty("version", versionId); pending.setProperty("target", plan.targetIdentity);
        pending.setProperty("beforeLocal", expectedWorkingHash); pending.setProperty("after", plan.workingHash);
        pending.setProperty("beforeSource", plan.sourceHash);
        pending.setProperty("retention", retention == null ? "unlimited" : Integer.toString(retention));
        try (Locked ignored = lock()) {
            if (pendingRestore().exists() || pendingJournal().exists()) throw new IOException("Resolve pending restore/export first");
            writeProperties(pendingRestore(), pending);
        }
        try {
            ExportResult source = executeExport(target, plan, true);
            CommitResult working = commitWorking(expectedWorkingHash, chosen, "restore-source:" + versionId, retention);
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
    public CommitResult completePendingRestore(External target) throws IOException {
        if (!"LOCAL_RESTORE_REQUIRED".equals(inspectPendingRestore(target)))
            throw new IOException("Pending source restore is not ready to complete");
        Properties pending = readProperties(pendingRestore());
        Integer retention = pending.getProperty("retention").equals("unlimited") ? null
                : Integer.parseInt(pending.getProperty("retention"));
        byte[] chosen = readVersion(pending.getProperty("version"));
        if (!hash(chosen).equals(pending.getProperty("after"))) throw new IOException("Restore backup changed");
        CommitResult result = commitWorking(pending.getProperty("beforeLocal"), chosen,
                "restore-source:" + pending.getProperty("version"), retention);
        Files.deleteIfExists(pendingRestore().toPath());
        return result;
    }
    /** Generic document providers cannot promise crash-atomic replacement. */
    public synchronized ExportResult executeExport(External target, ExportPlan plan) throws IOException {
        return executeExport(target, plan, false);
    }
    private synchronized ExportResult executeExport(External target, ExportPlan plan, boolean restoring) throws IOException {
        if (!target.identity().equals(plan.targetIdentity)) throw new IOException("Export destination changed");
        // A normal export must still match the working copy. A restore plan can use an older immutable version.
        if (!readWorking().sha256.equals(plan.workingHash) && !hasVerifiedVersion(plan.workingHash))
            throw new IOException("Restore version or working select.def changed; refresh preview");
        byte[] old = checked(target.read());
        if (!hash(old).equals(plan.sourceHash)) throw new IOException("Destination changed; refresh preview");
        if (MessageDigest.isEqual(old, plan.replacement))
            return new ExportResult(false, plan.targetIdentity, plan.workingHash, null);
        try (Locked ignored = lock()) {
            if (pendingJournal().exists()) throw new IOException("Resolve pending export first");
            if (pendingRestore().exists() && !restoring) throw new IOException("Resolve pending restore first");
            if (!readWorking().sha256.equals(plan.workingHash) && !hasVerifiedVersion(plan.workingHash))
                throw new IOException("Restore version or working select.def changed; refresh preview");
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
        p.setProperty("reason", reason == null ? "edit" : reason); p.setProperty("origin", origin);
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
        if (!hash(bytes).equals(p.getProperty("sha256")) || !Integer.toString(bytes.length).equals(p.getProperty("bytes"))) return null;
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
    private void prune(Integer retention) throws IOException {
        if (retention == null) return;
        List<Version> all = verifiedVersions();
        for (int i = retention; i < all.size(); i++) {
            Version v = all.get(i);
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
    private boolean hasVerifiedVersion(String hash) throws IOException {
        for (Version version : verifiedVersions()) if (version.sha256.equals(hash)) return true;
        return false;
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
