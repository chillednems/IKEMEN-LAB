package com.chillednems.ikemenlab;

import android.content.Context;
import android.net.Uri;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Source document stays fixed; only verified preimage storage follows the selected policy. */
final class BackupTargetRouter implements SelectStorage.External {
    private final Context context;
    private final LibraryBinding binding;
    private final BackupPolicy policy;
    private final boolean allowOffline;
    private final SafSelectTarget source;
    private final SelectStorage.External active;
    private final Map<String, SelectStorage.External> stores = new LinkedHashMap<>();
    private final Map<String, String> storeLabels = new LinkedHashMap<>();
    private int unavailableHistory;

    BackupTargetRouter(Context context, LibraryBinding binding, BackupPolicy policy) throws IOException {
        this(context, binding, policy, false);
    }
    BackupTargetRouter(Context context, LibraryBinding binding, BackupPolicy policy,
                       boolean allowOffline) throws IOException {
        this.context = context;
        this.binding = binding;
        this.policy = policy;
        this.allowOffline = allowOffline;
        binding.requireCurrent(context);
        policy.requireCurrent(context);
        SafSelectTarget linked;
        try { linked = new SafSelectTarget(context.getContentResolver(), binding.sourceTree); }
        catch (IOException unavailable) {
            if (!allowOffline) throw unavailable;
            linked = null;
        }
        source = linked;
        if (source != null) add(source);
        SelectStorage.External app = new AppSelectTarget(source, context.getFilesDir(),
                binding.workingRoot.getCanonicalPath(), binding.sourceTree.toString());
        add(app);
        for (Uri tree : policy.knownCustomTrees) {
            if (source == null) break;
            try { add(new SafSelectTarget(context.getContentResolver(), binding.sourceTree, tree),
                    "Custom folder " + policy.displayNameFor(tree)); }
            catch (IOException unavailable) { unavailableHistory++; }
        }
        switch (policy.kind) {
            case APP: active = app; break;
            case CUSTOM:
                if (source == null) {
                    if (!allowOffline) throw new IOException("Source unavailable");
                    active = app; break;
                }
                if (policy.customTree == null) throw new IOException("Custom backup folder is not selected");
                SelectStorage.External chosen;
                try { chosen = new SafSelectTarget(context.getContentResolver(), binding.sourceTree, policy.customTree); }
                catch (IOException unavailable) {
                    if (!allowOffline) throw unavailable;
                    unavailableHistory++;
                    chosen = app;
                }
                active = chosen;
                if (chosen != app) add(chosen, "Custom folder " + policy.customName);
                break;
            default: active = source == null ? app : source;
        }
    }
    private void add(SelectStorage.External store) throws IOException {
        add(store, store.backupLabel());
    }
    private void add(SelectStorage.External store, String label) throws IOException {
        stores.put(store.backupIdentity(), store);
        storeLabels.put(store.backupIdentity(), label);
    }
    private void current() throws IOException {
        binding.requireCurrent(context);
        policy.requireCurrent(context);
    }
    int unavailableHistoryCount() { return unavailableHistory; }
    boolean sourceAvailable() { return source != null; }
    @Override public String identity() throws IOException { current(); return requiredSource().identity(); }
    @Override public byte[] read() throws IOException { current(); return requiredSource().read(); }
    @Override public void replace(byte[] bytes) throws IOException { current(); requiredSource().replace(bytes); }
    private SafSelectTarget requiredSource() throws IOException {
        if (source == null) throw new IOException("Source folder unavailable; reconnect it in Settings");
        return source;
    }
    @Override public String backupIdentity() throws IOException {
        current();
        return "policy:" + policy.generation + ":" + active.backupIdentity();
    }
    @Override public String backupLabel() throws IOException { current(); return policy.displayName(); }
    @Override public String backup(byte[] preimage, String transactionId) throws IOException {
        current(); return active.backup(preimage, transactionId);
    }
    @Override public List<SelectStorage.Version> listBackups() throws IOException {
        current(); return active.listBackups();
    }
    @Override public byte[] readBackup(String versionId) throws IOException {
        current(); return active.readBackup(versionId);
    }
    @Override public byte[] readBackup(String versionId, String storeIdentity) throws IOException {
        current();
        if (storeIdentity.equals(backupIdentity())) return active.readBackup(versionId);
        SelectStorage.External store = stores.get(storeIdentity);
        if (store == null) throw new IOException("The selected backup folder is unavailable; reconnect it in Settings");
        return store.readBackup(versionId);
    }
    @Override public List<SelectStorage.BackupRef> listExternalBackups() throws IOException {
        current();
        List<SelectStorage.BackupRef> all = new ArrayList<>();
        for (SelectStorage.External store : stores.values()) {
            try {
                for (SelectStorage.Version version : store.listBackups())
                    all.add(new SelectStorage.BackupRef(SelectStorage.BackupRef.Origin.SOURCE,
                            version, store.backupIdentity(), storeLabels.get(store.backupIdentity())));
            } catch (IOException unavailable) {
                if (store == active && !allowOffline) throw unavailable;
                unavailableHistory++;
            }
        }
        return all;
    }
    @Override public void pruneBackups(Integer retention, String protectedVersionId) throws IOException {
        current(); active.pruneBackups(retention, protectedVersionId);
    }
}
