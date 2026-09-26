package com.chillednems.ikemenlab;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.provider.DocumentsContract;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Landscape-friendly touch and hardware-controller library browser. */
public final class MainActivity extends Activity {
    private static final int PICK_TREE = 100;
    private static final int EXPORT_SELECT = 101;
    private static final int RECONNECT_TREE = 102;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final ExecutorService PREVIEW = Executors.newSingleThreadExecutor();
    private static final String PREFS = "library";
    private static final String KEY_PATH = "active_path";
    private static final String KEY_ORIENTATION = "orientation";
    private static final String KEY_RETENTION = "backup_retention";
    private LinearLayout root;
    private FrameLayout screenFrame;
    private LinearLayout activeSheet;
    private View sheetPreviousFocus;
    private LinearLayout list;
    private LinearLayout detail;
    private Button rosterButton;
    private TextView status;
    private boolean compactLayout;
    private boolean forceCompactLayout;
    private EditText search;
    private File library;
    private LibraryBinding binding;
    private File reconnectLibrary;
    private String recoveryIssue;
    private LibraryScanner.Catalog catalog;
    private LibraryScanner.Item selected;
    private String restoreSelection;
    private String searchText = "";
    private boolean busy;
    private long lastStickMove;
    private volatile String previewKey;
    private Bitmap previewBitmap;
    private String previewReason;
    private boolean previewLoading;
    private android.window.OnBackInvokedCallback backCallback;
    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener libraryChanged = (prefs, key) -> {
        if (KEY_PATH.equals(key)) runOnUiThread(() -> {
            if (!isDestroyed()) syncActiveLibrary(prefs.getString(KEY_PATH, null));
        });
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        applyOrientation();
        String path = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_PATH, null);
        library = managedLibrary(path);
        if (state != null) {
            searchText = state.getString("search", "");
            restoreSelection = state.getString("selection");
            reconnectLibrary = managedLibrary(state.getString("reconnect"));
        }
        if (Build.VERSION.SDK_INT >= 33) {
            backCallback = this::handleBack;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
        }
        buildScreen();
        getSharedPreferences(PREFS, MODE_PRIVATE).registerOnSharedPreferenceChangeListener(libraryChanged);
        syncActiveLibrary(getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_PATH, null));
    }

    @Override protected void onDestroy() {
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null)
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        getSharedPreferences(PREFS, MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(libraryChanged);
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("search", search == null ? searchText : search.getText().toString());
        if (selected != null) state.putString("selection", selectionKey(selected));
        if (reconnectLibrary != null) state.putString("reconnect", reconnectLibrary.getAbsolutePath());
        super.onSaveInstanceState(state);
    }

    private int dp(float value) { return (int) (getResources().getDisplayMetrics().density * value + .5f); }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private TextView label(String value, int size, boolean bold) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextColor(Color.rgb(235, 241, 246));
        text.setTextSize(size);
        if (bold) text.setTypeface(null, Typeface.BOLD);
        text.setPadding(dp(8), dp(6), dp(8), dp(6));
        return text;
    }

    private void focusStyle(View view, boolean selectedRow) {
        GradientDrawable normal = new GradientDrawable();
        normal.setColor(selectedRow ? 0xff24536d : 0xff243440);
        normal.setCornerRadius(dp(9));
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(0xff31566d);
        focused.setCornerRadius(dp(9));
        focused.setStroke(dp(3), 0xffffc857);
        view.setBackground(normal);
        view.setOnFocusChangeListener((v, hasFocus) -> v.setBackground(hasFocus ? focused : normal));
    }

    private Button button(String title, Runnable action) {
        Button button = new Button(this);
        button.setText(title);
        button.setTextColor(Color.WHITE);
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        button.setOnClickListener(v -> action.run());
        focusStyle(button, false);
        return button;
    }

    private void buildScreen() {
        activeSheet = null;
        sheetPreviousFocus = null;
        boolean landscape = getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        compactLayout = landscape && (forceCompactLayout || getResources().getConfiguration().screenHeightDp < 320);
        root = column();
        if (landscape && !compactLayout) root.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (bottom - top < dp(320) && !forceCompactLayout) {
                forceCompactLayout = true;
                searchText = search.getText().toString();
                view.post(this::buildScreen);
            }
        });
        root.setBackgroundColor(0xff111d27);
        root.setPadding(dp(12), dp(compactLayout ? 2 : 8), dp(12), dp(compactLayout ? 2 : 8));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int left, top, right, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                left = bars.left; top = bars.top; right = bars.right; bottom = bars.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft(); top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight(); bottom = insets.getSystemWindowInsetBottom();
            }
            int vertical = dp(compactLayout ? 2 : 8);
            view.setPadding(dp(12) + left, vertical + top, dp(12) + right, vertical + bottom);
            return insets;
        });
        screenFrame = new FrameLayout(this);
        screenFrame.addView(root, new FrameLayout.LayoutParams(-1, -1));
        setContentView(screenFrame);

        search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("Search characters or stages");
        search.setTextColor(Color.WHITE);
        search.setHintTextColor(0xffa8b9c7);
        search.setText(searchText);
        search.setPadding(dp(12), dp(8), dp(12), dp(8));
        search.setBackgroundColor(0xff243440);
        if (compactLayout) {
            LinearLayout toolbar = new LinearLayout(this);
            toolbar.setOrientation(LinearLayout.HORIZONTAL);
            root.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(48)));
            Button more = button("More", () -> {});
            more.setOnClickListener(this::showCompactMenu);
            toolbar.addView(more, new LinearLayout.LayoutParams(dp(88), dp(48)));
            toolbar.addView(search, new LinearLayout.LayoutParams(0, dp(48), 1));
            toolbar.addView(button("Portrait", () -> chooseOrientation("portrait")), new LinearLayout.LayoutParams(dp(108), dp(48)));
            status = null;
        } else {
            root.addView(label("IKEMEN Lab · Android library", 24, true));
            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            root.addView(actions);
            actions.addView(button("Switch folder", this::pickFolder), new LinearLayout.LayoutParams(0, dp(58), 1));
            actions.addView(button("Roster actions", this::showRosterActions), new LinearLayout.LayoutParams(0, dp(58), 1));
            actions.addView(button("Settings", this::showSettings), new LinearLayout.LayoutParams(0, dp(58), 1));
            status = label("Choose an IKEMEN folder with chars and stages.", 14, false);
            status.setSingleLine(true);
            status.setEllipsize(android.text.TextUtils.TruncateAt.END);
            root.addView(status);
            root.addView(search, new LinearLayout.LayoutParams(-1, dp(52)));
        }
        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { searchText = s.toString(); renderList(); }
            public void afterTextChanged(android.text.Editable e) {}
        });

        LinearLayout panels = new LinearLayout(this);
        panels.setOrientation(landscape ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        root.addView(panels, new LinearLayout.LayoutParams(-1, 0, 1));
        list = column();
        detail = column();
        ScrollView listScroll = new ScrollView(this);
        ScrollView detailScroll = new ScrollView(this);
        listScroll.setFillViewport(true);
        detailScroll.setFillViewport(true);
        listScroll.addView(list);
        detailScroll.addView(detail);
        if (landscape) {
            panels.addView(listScroll, new LinearLayout.LayoutParams(0, -1, 1.15f));
            panels.addView(detailScroll, new LinearLayout.LayoutParams(0, -1, 1));
        } else {
            panels.addView(listScroll, new LinearLayout.LayoutParams(-1, 0, 1));
            panels.addView(detailScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        }
        renderList();
    }

    private void applyOrientation() {
        String choice = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_ORIENTATION, "landscape");
        setRequestedOrientation("portrait".equals(choice)
                ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
    }

    private void showSettings() {
        if (busy) return;
        showActionSheet("Settings", new String[]{"Screen orientation", "Backups to keep", "Reconnect source folder"},
                new Runnable[]{this::showOrientationSetting, this::showRetentionSetting, this::reconnectSource});
    }

    private void showRetentionSetting() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setHint("Empty means unlimited");
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(0xffa8b9c7);
        input.setText(getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_RETENTION, ""));
        LinearLayout panel = column();
        panel.addView(sheetTitle("Backups to keep"), new LinearLayout.LayoutParams(-1, dp(compactLayout ? 26 : 48)));
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(true);
        LinearLayout content = column();
        content.addView(label("Leave empty for unlimited (default), or enter a positive number. Only verified app-managed backups are pruned after a successful write.", 15, false));
        content.addView(input, new LinearLayout.LayoutParams(-1, dp(52)));
        scroll.addView(content);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout choices = new LinearLayout(this);
        choices.setOrientation(LinearLayout.HORIZONTAL);
        panel.addView(choices, new LinearLayout.LayoutParams(-1, dp(48)));
        choices.addView(button("Save", () -> {
                    String value = input.getText().toString().trim();
                    try {
                        if (!value.isEmpty() && Integer.parseInt(value) < 1) throw new NumberFormatException();
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_RETENTION, value).apply();
                        dismissSheet();
                        showStatus(value.isEmpty() ? "Backup retention: unlimited." : "Keep " + value + " verified backups after future writes.");
                    } catch (NumberFormatException invalid) { showStatus("Enter a positive whole number, or leave empty for unlimited."); }
                }), new LinearLayout.LayoutParams(0, -1, 1));
        choices.addView(button("Cancel", this::dismissSheet), new LinearLayout.LayoutParams(0, -1, 1));
        presentSheet(panel, scroll);
    }

    private Integer retention() throws IOException {
        String value = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_RETENTION, "").trim();
        if (value.isEmpty()) return null;
        try {
            int count = Integer.parseInt(value);
            if (count > 0) return count;
        } catch (NumberFormatException ignored) { }
        throw new IOException("Backup retention must be a positive whole number or unlimited");
    }

    private void showRosterActions() {
        if (busy) return;
        showActionSheet("Roster actions", new String[]{"Review export to linked source", "Backups and restore",
                        "Save a copy elsewhere", "Reconnect source folder", "Recovery"},
                new Runnable[]{this::reviewSourceExport, this::showBackups, this::pickExport,
                        this::reconnectSource, this::showRecovery});
    }

    private void showActionSheet(String title, String[] labels, Runnable[] actions) {
        LinearLayout panel = column();
        panel.addView(sheetTitle(title), new LinearLayout.LayoutParams(-1, dp(compactLayout ? 26 : 48)));
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(true);
        LinearLayout content = column();
        scroll.addView(content);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        for (int i = 0; i < labels.length; i++) {
            Runnable action = actions[i];
            content.addView(button(labels[i], () -> { dismissSheet(); action.run(); }),
                    new LinearLayout.LayoutParams(-1, dp(48)));
        }
        panel.addView(button("Close", this::dismissSheet), new LinearLayout.LayoutParams(-1, dp(48)));
        presentSheet(panel, scroll);
    }

    private void showDecisionSheet(String title, String message, String actionLabel, Runnable action) {
        LinearLayout panel = column();
        panel.addView(sheetTitle(title), new LinearLayout.LayoutParams(-1, dp(compactLayout ? 26 : 48)));
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(true);
        TextView body = label(message, compactLayout ? 14 : 15, false);
        body.setPadding(dp(4), dp(2), dp(4), dp(2));
        scroll.addView(body);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout choices = new LinearLayout(this);
        choices.setOrientation(LinearLayout.HORIZONTAL);
        panel.addView(choices, new LinearLayout.LayoutParams(-1, dp(48)));
        if (action != null) choices.addView(button(actionLabel, () -> { dismissSheet(); action.run(); }),
                new LinearLayout.LayoutParams(0, -1, 1));
        choices.addView(button("Close", this::dismissSheet), new LinearLayout.LayoutParams(0, -1, 1));
        presentSheet(panel, scroll);
    }

    private TextView sheetTitle(String title) {
        TextView heading = label(title, compactLayout ? 17 : 20, true);
        heading.setSingleLine(true);
        heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
        heading.setPadding(dp(4), dp(2), dp(4), dp(2));
        return heading;
    }

    private void presentSheet(LinearLayout panel, ScrollView scroll) {
        dismissSheet();
        sheetPreviousFocus = getCurrentFocus();
        activeSheet = panel;
        panel.setBackgroundColor(0xff111d27);
        panel.setPadding(dp(4), root.getPaddingTop(), dp(4), dp(2));
        panel.setClickable(true);
        screenFrame.addView(panel, new FrameLayout.LayoutParams(-1, -1));
        root.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        scroll.setFocusableInTouchMode(true);
        scroll.requestFocus();
        scroll.post(() -> scroll.scrollTo(0, 0));
    }

    private void dismissSheet() {
        if (activeSheet == null) return;
        screenFrame.removeView(activeSheet);
        activeSheet = null;
        root.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        View previous = sheetPreviousFocus;
        sheetPreviousFocus = null;
        if (previous != null && previous.isAttachedToWindow()) previous.requestFocus();
    }

    private static String shortTargetName(String identity) {
        try {
            String document = DocumentsContract.getDocumentId(Uri.parse(identity));
            int separator = Math.max(document.lastIndexOf('/'), document.lastIndexOf(':'));
            if (separator >= 0 && separator + 1 < document.length()) return document.substring(separator + 1);
        } catch (IllegalArgumentException ignored) { }
        return "select.def";
    }

    private static String changeCounts(RosterChangeSummary changes) {
        return "Enabled " + changes.enabled + " · Disabled " + changes.disabled
                + "\nAdded " + changes.added + " · Removed " + changes.removed;
    }

    private void reviewSourceExport() {
        if (busy || library == null) { showStatus("Import a library first."); return; }
        File root = library;
        LibraryBinding current = binding;
        busy = true;
        showStatus("Checking exact source destination…");
        IO.execute(() -> {
            try {
                SafSelectTarget target = requireReady(root, current, true);
                SelectStorage storage = new SelectStorage(root);
                SelectStorage.Snapshot working = storage.readWorking();
                SelectStorage.ExportPlan plan = storage.planExport(target, working.sha256);
                byte[] source = target.read();
                if (!SelectStorage.hash(source).equals(plan.sourceHash)) throw new IOException("Destination changed during preview; review again");
                RosterChangeSummary changes = RosterChangeSummary.compare(root, source, plan.replacement);
                Integer keep = retention();
                runOnUiThread(() -> { if (isDestroyed() || !root.equals(library)) return;
                    busy = false;
                    boolean changed = !plan.sourceHash.equals(plan.workingHash);
                    String message = (changed ? "Destination: linked " : "No changes · linked ")
                            + shortTargetName(plan.targetIdentity) + "\n" + changeCounts(changes)
                            + "\nBackup before overwrite: " + (changed ? "yes" : "not needed")
                            + "\nMissing references: " + plan.missingWarnings.size()
                            + "\n\nExact document: " + plan.targetIdentity
                            + "\nBackup folder: " + plan.backupDestination
                            + "\nVerified backups to keep: " + (keep == null ? "unlimited" : keep);
                    showDecisionSheet("Review source export", message,
                            "Back up and overwrite", changed ? () -> executeSourceExport(root, current, plan, keep) : null);
                });
            } catch (Exception error) { runOnUiThread(() -> { if (!isDestroyed()) {
                busy = false; showStatus("Export preview unavailable: " + error.getMessage());
            } }); }
        });
    }

    private void executeSourceExport(File root, LibraryBinding current, SelectStorage.ExportPlan plan, Integer keep) {
        if (busy) return;
        busy = true;
        IO.execute(() -> {
            try {
                SafSelectTarget target = requireReady(root, current, true);
                SelectStorage.ExportResult result = new SelectStorage(root).executeExport(target, plan, keep);
                runOnUiThread(() -> { if (!isDestroyed() && root.equals(library)) {
                    busy = false;
                    showDecisionSheet("Source export complete", result.changed
                                    ? "The existing source select.def was updated and read back.\nVerified pre-write backup: "
                                            + result.backupLocation + (result.cleanupPending ? "\nBackup cleanup remains pending." : "")
                                    : "No changes to source.", null, null);
                } });
            } catch (Exception error) { runOnUiThread(() -> { if (!isDestroyed()) {
                busy = false; showStatus("Source export stopped: " + error.getMessage());
                checkRecoveryAtStartup(root, current);
            } }); }
        });
    }

    private void showBackups() {
        if (busy || library == null) { showStatus("Import a library first."); return; }
        File root = library;
        LibraryBinding current = binding;
        busy = true;
        IO.execute(() -> {
            try {
                SafSelectTarget target = null;
                String warning = null;
                if (current != null && current.sourceTree != null) {
                    try { current.requireCurrent(this); target = new SafSelectTarget(getContentResolver(), current.sourceTree); }
                    catch (IOException unavailable) { warning = "Source backups unavailable. Reconnect source to view them."; }
                }
                List<SelectStorage.BackupRef> backups = new SelectStorage(root).listAllBackups(target);
                String unavailable = warning;
                runOnUiThread(() -> { if (isDestroyed() || !root.equals(library)) return;
                    busy = false;
                    if (backups.isEmpty()) { showStatus(unavailable == null ? "No verified backups yet." : unavailable); return; }
                    String[] labels = new String[backups.size()];
                    for (int i = 0; i < backups.size(); i++) {
                        SelectStorage.BackupRef ref = backups.get(i);
                        labels[i] = (ref.origin == SelectStorage.BackupRef.Origin.SOURCE ? "Source" : "Private")
                                + " · " + java.text.DateFormat.getDateTimeInstance().format(new Date(ref.createdAt))
                                + " · " + ref.byteCount + " bytes";
                    }
                    Runnable[] actions = new Runnable[backups.size()];
                    for (int i = 0; i < backups.size(); i++) {
                        SelectStorage.BackupRef ref = backups.get(i);
                        actions[i] = () -> chooseRestore(ref);
                    }
                    showActionSheet(unavailable == null ? "Verified backups" : "Private backups; reconnect source", labels, actions);
                });
            } catch (Exception error) { runOnUiThread(() -> { if (!isDestroyed()) {
                busy = false; showStatus("Backups unavailable: " + error.getMessage());
            } }); }
        });
    }

    private void chooseRestore(SelectStorage.BackupRef ref) {
        showActionSheet("Restore " + ref.origin + " backup", new String[]{"Load local only (source unchanged)",
                "Review source and local restore"}, new Runnable[]{() -> restoreLocal(ref), () -> reviewSourceRestore(ref)});
    }

    private void restoreLocal(SelectStorage.BackupRef ref) {
        if (busy || library == null) return;
        File root = library;
        LibraryBinding current = binding;
        busy = true;
        IO.execute(() -> {
            try {
                SafSelectTarget target = requireReady(root, current, ref.origin == SelectStorage.BackupRef.Origin.SOURCE);
                SelectStorage storage = new SelectStorage(root);
                SelectStorage.CommitResult result = storage.restoreLoadOnly(target, ref, storage.readWorking().sha256, retention());
                LibraryScanner.Catalog scanned = LibraryScanner.scan(root);
                runOnUiThread(() -> { if (!isDestroyed() && root.equals(library)) {
                    busy = false; catalog = scanned; selected = selected == null ? null : find(scanned, selected);
                    renderList(); showStatus(result.changed ? "Backup loaded into private copy. Source unchanged." : "Private copy already matches backup; source unchanged.");
                } });
            } catch (Exception error) { runOnUiThread(() -> { if (!isDestroyed()) {
                busy = false; showStatus("Local restore stopped: " + error.getMessage());
            } }); }
        });
    }

    private void reviewSourceRestore(SelectStorage.BackupRef ref) {
        if (busy || library == null) return;
        File root = library;
        LibraryBinding current = binding;
        busy = true;
        IO.execute(() -> {
            try {
                SafSelectTarget target = requireReady(root, current, true);
                SelectStorage storage = new SelectStorage(root);
                String workingHash = storage.readWorking().sha256;
                SelectStorage.ExportPlan plan = storage.planRestoreSource(target, ref);
                byte[] source = target.read();
                if (!SelectStorage.hash(source).equals(plan.sourceHash)) throw new IOException("Destination changed during preview; review again");
                RosterChangeSummary changes = RosterChangeSummary.compare(root, source, plan.replacement);
                Integer keep = retention();
                runOnUiThread(() -> { if (isDestroyed() || !root.equals(library)) return;
                    busy = false;
                    showDecisionSheet("Review source and local restore",
                            "Destination: linked " + shortTargetName(plan.targetIdentity)
                                    + "\n" + changeCounts(changes)
                                    + "\nBackup before overwrite: yes"
                                    + "\nPrivate copy loads selected backup"
                                    + "\n\nExact document: " + plan.targetIdentity
                                    + "\nBackup folder: " + plan.backupDestination
                                    + "\nVerified backups to keep: "
                                    + (keep == null ? "unlimited" : keep),
                            "Back up and restore", () -> executeSourceRestore(root, current, ref, workingHash, plan, keep));
                });
            } catch (Exception error) { runOnUiThread(() -> { if (!isDestroyed()) {
                busy = false; showStatus("Restore preview unavailable: " + error.getMessage());
            } }); }
        });
    }

    private void executeSourceRestore(File root, LibraryBinding current, SelectStorage.BackupRef ref,
                                      String workingHash, SelectStorage.ExportPlan plan, Integer keep) {
        if (busy) return;
        busy = true;
        IO.execute(() -> {
            try {
                SafSelectTarget target = requireReady(root, current, true);
                SelectStorage.RestoreResult result = new SelectStorage(root).restoreSourceAndWorking(
                        target, ref, workingHash, plan, keep);
                LibraryScanner.Catalog scanned = LibraryScanner.scan(root);
                runOnUiThread(() -> { if (!isDestroyed() && root.equals(library)) {
                    busy = false; catalog = scanned; selected = selected == null ? null : find(scanned, selected);
                    renderList();
                    showDecisionSheet("Restore complete", "Source and private roster now contain the selected backup.\n"
                            + (result.source.changed ? "Source pre-write backup: " + result.source.backupLocation
                            : "Source was already identical."), null, null);
                } });
            } catch (Exception error) { runOnUiThread(() -> { if (!isDestroyed()) {
                busy = false; showStatus("Restore stopped: " + error.getMessage());
                checkRecoveryAtStartup(root, current);
            } }); }
        });
    }

    private void showOrientationSetting() {
        showActionSheet("Screen orientation", new String[]{"Landscape", "Portrait"},
                new Runnable[]{() -> chooseOrientation("landscape"), () -> chooseOrientation("portrait")});
    }

    private void chooseOrientation(String choice) {
        if (busy) return;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_ORIENTATION, choice).apply();
        applyOrientation();
    }

    private void showCompactMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("Switch folder").setOnMenuItemClickListener(item -> { pickFolder(); return true; });
        menu.getMenu().add("Manage roster & settings").setOnMenuItemClickListener(item -> {
            anchor.post(() -> showActionSheet("Manage", new String[]{"Roster actions", "Settings"},
                    new Runnable[]{this::showRosterActions, this::showSettings}));
            return true;
        });
        menu.show();
    }

    private void showStatus(String message) {
        if (status != null) status.setText(message);
        else if (!message.equals(summary()) && !message.startsWith("Choose an IKEMEN folder"))
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private static boolean hasPending(File root) {
        File folder = new File(RosterStore.selectFile(root).getParentFile(), "select-backups");
        return new File(folder, "pending-export.properties").isFile()
                || new File(folder, "pending-restore.properties").isFile();
    }

    private SafSelectTarget requireReady(File root, LibraryBinding current, boolean needSource) throws IOException {
        SafSelectTarget target = null;
        if (current != null && current.sourceTree != null) {
            current.requireCurrent(this);
            try { target = new SafSelectTarget(getContentResolver(), current.sourceTree); }
            catch (IOException inaccessible) { if (needSource || hasPending(root)) throw new IOException(
                    "Source unavailable. Reconnect its folder; your local copy is retained. " + inaccessible.getMessage(), inaccessible); }
        }
        if (needSource && target == null) throw new IOException("No writable source linked. Reconnect source folder first.");
        if (hasPending(root)) {
            if (target == null) throw new IOException("Pending source operation: reconnect source folder and open Recovery.");
            SelectStorage storage = new SelectStorage(root);
            String export = storage.inspectPending(target);
            String restore = export.equals("RECOVERY_REQUIRED") || export.equals("OTHER_TARGET")
                    ? "DEFERRED" : storage.inspectPendingRestore(target);
            if (export.equals("RECOVERY_REQUIRED") || export.equals("OTHER_TARGET")
                    || restore.equals("RECOVERY_REQUIRED") || restore.equals("OTHER_TARGET")
                    || restore.equals("LOCAL_RESTORE_REQUIRED"))
                throw new IOException("Recovery required (" + export + "/" + restore + "). Open Roster actions > Recovery.");
        }
        return target;
    }

    private void checkRecoveryAtStartup(File root, LibraryBinding current) {
        if (!hasPending(root)) { recoveryIssue = null; return; }
        IO.execute(() -> {
            String issue = null;
            try { requireReady(root, current, false); }
            catch (IOException error) { issue = error.getMessage(); }
            String result = issue;
            runOnUiThread(() -> { if (!isDestroyed() && root.equals(library)) {
                recoveryIssue = result;
                if (result != null) showStatus(result);
            } });
        });
    }

    private void showRecovery() {
        if (busy || library == null) return;
        File root = library;
        LibraryBinding current = binding;
        busy = true;
        IO.execute(() -> {
            try {
                if (current == null || current.sourceTree == null) throw new IOException("Reconnect the original source folder first.");
                current.requireCurrent(this);
                SafSelectTarget target = new SafSelectTarget(getContentResolver(), current.sourceTree);
                SelectStorage storage = new SelectStorage(root);
                String export = storage.inspectPending(target);
                String restore = export.equals("RECOVERY_REQUIRED") || export.equals("OTHER_TARGET")
                        ? "DEFERRED" : storage.inspectPendingRestore(target);
                runOnUiThread(() -> { if (isDestroyed() || !root.equals(library)) return;
                    busy = false;
                    if (export.equals("RECOVERY_REQUIRED")) {
                        showDecisionSheet("Repair interrupted export",
                                "Destination: " + target.identity() + "\nRestore its exact pre-write bytes from the private recovery copy? This overwrites the current destination.",
                                "Restore preimage", () -> executeRecovery(root, current, false));
                    } else if (export.equals("OTHER_TARGET"))
                        showStatus("Pending export belongs to a different source folder. Reconnect the original folder first.");
                    else if (restore.equals("LOCAL_RESTORE_REQUIRED")) {
                        showDecisionSheet("Finish interrupted restore",
                                "The source was restored, but the private working roster still needs the selected backup. Complete that local step?",
                                "Complete local copy", () -> executeRecovery(root, current, true));
                    } else if (restore.equals("OTHER_TARGET"))
                        showStatus("Recovery is bound to a different source folder. Reconnect the original folder.");
                    else if (restore.equals("RECOVERY_REQUIRED")) {
                        showDecisionSheet("Stop interrupted restore safely",
                                "The source and private roster no longer match this pending restore. Preserve and verify both current versions as separate backups, then stop this restore? No roster will be overwritten. Destination: " + target.identity(),
                                "Preserve both and stop", () -> abandonRecovery(root, current));
                    }
                    else { recoveryIssue = null; showStatus("No interrupted source operation remains."); }
                });
            } catch (Exception error) { runOnUiThread(() -> { if (!isDestroyed()) {
                busy = false; showStatus("Recovery unavailable: " + error.getMessage());
            } }); }
        });
    }

    private void executeRecovery(File root, LibraryBinding current, boolean finishLocal) {
        if (busy) return;
        busy = true;
        IO.execute(() -> {
            try {
                current.requireCurrent(this);
                SafSelectTarget target = new SafSelectTarget(getContentResolver(), current.sourceTree);
                SelectStorage storage = new SelectStorage(root);
                if (finishLocal) storage.completePendingRestore(target);
                else storage.restorePendingPreimage(target);
                LibraryScanner.Catalog scanned = LibraryScanner.scan(root);
                runOnUiThread(() -> { if (!isDestroyed() && root.equals(library)) {
                    busy = false; recoveryIssue = null; catalog = scanned;
                    selected = selected == null ? null : find(scanned, selected);
                    renderList(); showStatus("Recovery completed and roster refreshed.");
                } });
            } catch (Exception error) { runOnUiThread(() -> { if (!isDestroyed()) {
                busy = false; showStatus("Recovery failed: " + error.getMessage());
            } }); }
        });
    }

    private void abandonRecovery(File root, LibraryBinding current) {
        if (busy) return;
        busy = true;
        IO.execute(() -> {
            try {
                current.requireCurrent(this);
                SafSelectTarget target = new SafSelectTarget(getContentResolver(), current.sourceTree);
                SelectStorage.AbandonResult result = new SelectStorage(root).abandonPendingRestore(target);
                runOnUiThread(() -> { if (!isDestroyed() && root.equals(library)) {
                    busy = false; recoveryIssue = null;
                    showDecisionSheet("Current versions preserved",
                            "The interrupted restore was stopped. Source backup: " + result.sourceBackupLocation
                                    + "\nPrivate backup ID: " + result.workingVersionId
                                    + "\nReview a fresh operation before making changes.",
                            "Done", () -> { });
                } });
            } catch (Exception error) { runOnUiThread(() -> { if (!isDestroyed()) {
                busy = false; showStatus("Recovery failed; pending restore kept: " + error.getMessage());
            } }); }
        });
    }

    private File managedLibrary(String path) {
        if (path == null) return null;
        File saved = new File(path);
        File parent = new File(getFilesDir(), "libraries");
        return saved.isDirectory() && parent.equals(saved.getParentFile()) ? saved : null;
    }

    private void syncActiveLibrary(String path) {
        File active = managedLibrary(path);
        LibraryBinding previousBinding = binding;
        try { binding = active == null ? null : LibraryBinding.load(this, active); }
        catch (IOException error) { binding = null; showStatus("Source link unavailable: " + error.getMessage()); }
        if (activeSheet != null && ((active == null ? library != null : !active.equals(library))
                || (previousBinding != null && binding != null
                && previousBinding.generation != binding.generation))) dismissSheet();
        if (active == null ? library == null : active.equals(library)) {
            if (active != null && catalog == null) refreshCatalog();
            if (active != null) checkRecoveryAtStartup(active, binding);
            return;
        }
        library = active;
        catalog = null;
        selected = null;
        restoreSelection = null;
        previewKey = null;
        previewBitmap = null;
        previewReason = null;
        previewLoading = false;
        renderList();
        showStatus(summary());
        refreshCatalog();
        if (active != null) checkRecoveryAtStartup(active, binding);
    }

    private void pickFolder() {
        if (busy) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, PICK_TREE);
    }

    private void reconnectSource() {
        if (busy || library == null) { showStatus("Import a library before reconnecting its source folder."); return; }
        reconnectLibrary = library;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, RECONNECT_TREE);
    }

    private int persistGrant(Uri uri, Intent data) {
        int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if (flags == 0) return 0;
        try {
            if (flags == (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            else if (flags == Intent.FLAG_GRANT_READ_URI_PERMISSION)
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            else getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            return flags;
        }
        catch (SecurityException denied) { return 0; }
    }

    private void pickExport() {
        if (busy || library == null || !RosterStore.selectFile(library).isFile()) {
            showStatus("Import a library with data/select.def or enable an item first.");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        // text/plain causes some document providers to append .txt to select.def.
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_TITLE, "select.def");
        startActivityForResult(intent, EXPORT_SELECT);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) {
            if (request == RECONNECT_TREE) reconnectLibrary = null;
            return;
        }
        Uri uri = data.getData();
        if (request == PICK_TREE) {
            int flags = persistGrant(uri, data);
            busy = true;
            showStatus("Importing folder…");
            IO.execute(() -> {
                try {
                    File copied = SafImporter.importTree(getContentResolver(), uri, new File(getFilesDir(), "libraries"));
                    LibraryScanner.Catalog scanned = LibraryScanner.scan(copied);
                    boolean linked = false;
                    if ((flags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0) {
                        try {
                            new SafSelectTarget(getContentResolver(), uri);
                            LibraryBinding.bindSource(this, copied, uri, flags);
                            linked = true;
                        } catch (IOException unavailable) { LibraryBinding.localOnly(this, copied); }
                    } else LibraryBinding.localOnly(this, copied);
                    if (!getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_PATH, copied.getAbsolutePath()).commit())
                        throw new IOException("Could not remember imported library");
                    boolean sourceLinked = linked;
                    runOnUiThread(() -> { if (isDestroyed()) return; library = copied; catalog = scanned; selected = null; busy = false; syncActiveLibrary(copied.getAbsolutePath()); renderList(); showStatus(sourceLinked ? "Imported library; source folder linked." : "Imported local copy. Reconnect source for guarded export."); });
                } catch (Exception error) { runOnUiThread(() -> { if (!isDestroyed()) { busy = false; showStatus("Import failed: " + error.getMessage()); } }); }
            });
        } else if (request == RECONNECT_TREE) {
            File expected = reconnectLibrary;
            reconnectLibrary = null;
            if (expected == null || !expected.equals(library)) return;
            try {
                LibraryBinding remembered = LibraryBinding.load(this, expected);
                if (!LibraryBinding.acceptsReconnect(
                        remembered.sourceTree == null ? null : remembered.sourceTree.toString(), uri.toString())) {
                    showStatus("This is a different source folder. Reconnect the original folder, or use Switch folder to import another library.");
                    return;
                }
            } catch (IOException error) { showStatus("Could not check the remembered source: " + error.getMessage()); return; }
            int flags = persistGrant(uri, data);
            busy = true;
            IO.execute(() -> {
                try {
                    if ((flags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0)
                        throw new IOException("Choose a folder that grants write access");
                    new SafSelectTarget(getContentResolver(), uri);
                    LibraryBinding restored = LibraryBinding.bindSource(this, expected, uri, flags);
                    runOnUiThread(() -> { if (!isDestroyed() && expected.equals(library)) {
                        binding = restored; busy = false; showStatus("Source folder reconnected.");
                        checkRecoveryAtStartup(expected, restored);
                    } });
                } catch (Exception error) { runOnUiThread(() -> { if (!isDestroyed()) { busy = false; showStatus("Reconnect failed; local copy preserved: " + error.getMessage()); } }); }
            });
        } else if (request == EXPORT_SELECT) {
            busy = true;
            showStatus("Exporting select.def…");
            File source = RosterStore.selectFile(library);
            IO.execute(() -> {
                try (InputStream input = Files.newInputStream(source.toPath()); OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                    if (output == null) throw new java.io.IOException("Could not open export destination");
                    byte[] buffer = new byte[65536];
                    int read;
                    while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                    runOnUiThread(() -> { if (!isDestroyed()) { busy = false; showStatus("Exported select.def"); } });
                } catch (Exception error) { runOnUiThread(() -> { if (!isDestroyed()) { busy = false; showStatus("Export failed: " + error.getMessage()); } }); }
            });
        }
    }

    private String summary() {
        if (library == null || catalog == null) return "Choose an IKEMEN folder with chars and stages.";
        return catalog.characters.size() + " characters · " + catalog.stages.size() + " stages · "
                + (binding != null && binding.sourceTree != null ? "source linked" : "local copy; reconnect source for export");
    }

    private void refreshCatalog() {
        if (library == null) return;
        File current = library;
        IO.execute(() -> {
            try {
                LibraryScanner.Catalog scanned = LibraryScanner.scan(current);
                runOnUiThread(() -> { if (!isDestroyed() && current.equals(library)) {
                    catalog = scanned;
                    if (restoreSelection != null) {
                        selected = findByKey(scanned, restoreSelection);
                        restoreSelection = null;
                    }
                    renderList(); showStatus(summary());
                } });
            } catch (Exception error) { runOnUiThread(() -> { if (!isDestroyed()) showStatus("Scan failed: " + error.getMessage()); }); }
        });
    }

    private void renderList() {
        if (list == null) return;
        list.removeAllViews();
        if (catalog == null) { list.addView(label("Import a folder to browse your library.", 17, false)); renderDetail(); return; }
        String query = searchText.trim().toLowerCase(Locale.ROOT);
        List<LibraryScanner.Item> items = new ArrayList<>();
        items.addAll(catalog.characters);
        items.addAll(catalog.stages);
        int shown = 0;
        for (LibraryScanner.Item item : items) {
            if (!query.isEmpty() && !(item.name + " " + item.author + " " + item.reference).toLowerCase(Locale.ROOT).contains(query)) continue;
            String state = item.enabled == null ? "Unlisted" : item.enabled ? "Enabled" : "Disabled";
            TextView row = label((item.kind.equals("characters") ? "Character" : "Stage") + " · " + item.name + " · " + state
                    + (item.warning == null ? "" : " · MISSING: " + item.warning), 16, false);
            if (item.warning != null) row.setTextColor(0xffff6b6b);
            row.setMinHeight(dp(52));
            row.setFocusable(true);
            row.setClickable(true);
            row.setTag(selectionKey(item));
            row.setOnClickListener(v -> {
                RowActivation.Action action = RowActivation.decide(
                        selected == null ? null : selectionKey(selected), selectionKey(item), busy);
                if (action == RowActivation.Action.IGNORE) return;
                if (action == RowActivation.Action.TOGGLE) {
                    toggleSelected(item.enabled == null || !item.enabled);
                    return;
                }
                selected = item;
                renderList();
                View replacement = list.findViewWithTag(selectionKey(item));
                if (replacement != null) replacement.requestFocus();
            });
            focusStyle(row, selected != null && selected.reference.equals(item.reference) && selected.kind.equals(item.kind));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.setMargins(dp(2), dp(2), dp(8), dp(2));
            list.addView(row, params);
            shown++;
        }
        if (shown == 0) list.addView(label("No matching content.", 16, false));
        renderDetail();
    }

    private void renderDetail() {
        if (detail == null) return;
        detail.removeAllViews();
        detail.addView(label("Details", 20, true));
        if (selected == null) { rosterButton = null; detail.addView(label("Select a character or stage. Use touch, D-pad, or left stick; A selects and B goes back.", 15, false)); return; }
        detail.addView(label(selected.name, 21, true));
        detail.addView(label("Author: " + selected.author, 16, false));
        detail.addView(label("Reference: " + selected.reference, 14, false));
        detail.addView(label(selected.kind.equals("characters") ? "Character portrait" : "Stage artwork sprite", 16, true));
        String key = selected.kind + "|" + selected.reference + "|" + selected.previewFile;
        if (!key.equals(previewKey)) {
            previewKey = key; previewBitmap = null; previewReason = null; previewLoading = false;
        }
        if (previewBitmap != null) {
            ImageView image = new ImageView(this);
            image.setImageBitmap(previewBitmap);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setContentDescription(selected.name + " artwork preview");
            detail.addView(image, new LinearLayout.LayoutParams(-1, dp(210)));
        } else {
            detail.addView(label(previewReason == null ? "Loading artwork preview…" : "Preview unavailable: " + previewReason, 14, false));
            if (!previewLoading && previewReason == null) loadPreview(selected, key);
        }
        if (selected.warning != null) {
            TextView warning = label("MISSING: " + selected.warning + ". Enable is blocked until the referenced file is restored.", 16, true);
            warning.setTextColor(0xffff6b6b);
            detail.addView(warning);
        }
        detail.addView(label("DEF: " + selected.file, 13, false));
        detail.addView(label("Roster: " + (selected.enabled == null ? "Not listed" : selected.enabled ? "Enabled" : "Disabled"), 16, false));
        rosterButton = button(selected.warning != null && !Boolean.TRUE.equals(selected.enabled) ? "Cannot enable missing file"
                : selected.enabled != null && selected.enabled ? "Disable in roster" : "Enable in roster",
                () -> toggleSelected(selected.enabled == null || !selected.enabled));
        if (selected.warning != null && !Boolean.TRUE.equals(selected.enabled)) rosterButton.setEnabled(false);
        detail.addView(rosterButton);
    }

    private void loadPreview(LibraryScanner.Item item, String key) {
        previewLoading = true;
        PREVIEW.execute(() -> {
            if (!key.equals(previewKey)) return;
            Bitmap image = null;
            String reason = null;
            try {
                if (item.previewFile == null) reason = "No artwork file declared or found";
                else {
                    File file = new File(item.previewFile);
                    if (file.getName().toLowerCase(Locale.ROOT).endsWith(".png")) {
                        if (file.length() <= 64 * 1024 * 1024) image = decodeBoundedPng(Files.readAllBytes(file.toPath()));
                        if (image == null) reason = "PNG is malformed or exceeds preview limit";
                    } else {
                        SffPreview.Result preview = SffPreview.extract(file, item.kind.equals("characters"), item.previewGroup, item.previewImage);
                        if (!preview.available()) reason = preview.unavailable;
                        else if (preview.argb != null) image = Bitmap.createBitmap(preview.argb, preview.width, preview.height, Bitmap.Config.ARGB_8888);
                        else image = decodeBoundedPng(preview.png);
                        if (reason == null && image == null) reason = "PNG sprite is malformed or exceeds preview limit";
                    }
                }
            } catch (Exception error) { reason = "Could not decode artwork"; }
            Bitmap result = image;
            String message = reason;
            runOnUiThread(() -> {
                if (isDestroyed() || !key.equals(previewKey)) return;
                previewBitmap = result; previewReason = message; previewLoading = false;
                boolean restoreRosterFocus = rosterButton != null && rosterButton.hasFocus();
                renderDetail();
                if (restoreRosterFocus && rosterButton != null) rosterButton.requestFocus();
            });
        });
    }

    private static Bitmap decodeBoundedPng(byte[] bytes) {
        if (bytes == null || bytes.length > 64 * 1024 * 1024) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || (long) bounds.outWidth * bounds.outHeight > 4_000_000) return null;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
    }

    private void toggleSelected(boolean enabled) {
        if (busy || library == null || selected == null) return;
        if (enabled && selected.warning != null) {
            showStatus("Cannot enable missing reference: " + selected.reference);
            return;
        }
        LibraryScanner.Item item = selected;
        File current = library;
        LibraryBinding source = binding;
        busy = true;
        showStatus("Updating roster…");
        IO.execute(() -> {
            try {
                requireReady(current, source, false);
                RosterStore.setEnabled(current, item, enabled, retention());
                LibraryScanner.Catalog scanned = LibraryScanner.scan(current);
                runOnUiThread(() -> {
                    if (isDestroyed() || !current.equals(library)) return;
                    catalog = scanned;
                    selected = find(scanned, item);
                    busy = false;
                    renderList();
                    if (selected != null) {
                        View row = list.findViewWithTag(selectionKey(selected));
                        if (row != null) row.requestFocus();
                    }
                    showStatus("Private roster updated. Review source export to apply it to the linked folder.");
                });
            } catch (Exception error) { runOnUiThread(() -> { if (!isDestroyed()) { busy = false; showStatus("Roster update failed: " + error.getMessage()); } }); }
        });
    }

    private static LibraryScanner.Item find(LibraryScanner.Catalog catalog, LibraryScanner.Item old) {
        for (LibraryScanner.Item item : catalog.characters) if (item.reference.equals(old.reference) && item.kind.equals(old.kind)) return item;
        for (LibraryScanner.Item item : catalog.stages) if (item.reference.equals(old.reference) && item.kind.equals(old.kind)) return item;
        return null;
    }

    private static String selectionKey(LibraryScanner.Item item) { return item.kind + "|" + item.reference; }

    private static LibraryScanner.Item findByKey(LibraryScanner.Catalog catalog, String key) {
        for (LibraryScanner.Item item : catalog.characters) if (selectionKey(item).equals(key)) return item;
        for (LibraryScanner.Item item : catalog.stages) if (selectionKey(item).equals(key)) return item;
        return null;
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if ((event.getSource() & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                && (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_A || event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_B)
                && (event.getAction() == KeyEvent.ACTION_UP || event.getRepeatCount() > 0)) return true;
        if (event.getAction() == KeyEvent.ACTION_DOWN && (event.getSource() & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_A) {
                View focused = getCurrentFocus();
                if (focused != null) focused.performClick();
                return true;
            }
            if (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_B) { handleBack(); return true; }
        }
        return super.dispatchKeyEvent(event);
    }

    private void handleBack() {
        if (activeSheet != null) { dismissSheet(); return; }
        if (selected != null) { selected = null; renderList(); return; }
        finish();
    }

    @SuppressWarnings("deprecation")
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() { handleBack(); }

    @Override public boolean onGenericMotionEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_MOVE && (event.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
            long now = android.os.SystemClock.uptimeMillis();
            if (now - lastStickMove > 180) {
                float x = event.getAxisValue(MotionEvent.AXIS_X);
                float y = event.getAxisValue(MotionEvent.AXIS_Y);
                int direction = ControllerPolicy.stickDirection(x, y);
                if (direction != 0) {
                    View focused = getCurrentFocus();
                    View origin = focused == null ? root : focused;
                    View next = switch (direction) {
                        case ControllerPolicy.LEFT -> origin.focusSearch(View.FOCUS_LEFT);
                        case ControllerPolicy.RIGHT -> origin.focusSearch(View.FOCUS_RIGHT);
                        case ControllerPolicy.UP -> origin.focusSearch(View.FOCUS_UP);
                        case ControllerPolicy.DOWN -> origin.focusSearch(View.FOCUS_DOWN);
                        default -> null;
                    };
                    if (next != null) next.requestFocus();
                    lastStickMove = now;
                    return true;
                }
            }
        }
        return super.onGenericMotionEvent(event);
    }
}
