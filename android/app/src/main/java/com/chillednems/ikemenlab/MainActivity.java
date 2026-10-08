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
import android.database.Cursor;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.util.LruCache;
import android.widget.BaseAdapter;
import android.widget.GridView;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.io.FileOutputStream;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

/** Landscape-friendly touch and hardware-controller library browser. */
public final class MainActivity extends Activity {
    private static final int PICK_TREE = 100;
    private static final int EXPORT_SELECT = 101;
    private static final int RECONNECT_TREE = 102;
    private static final int PICK_BACKUP_TREE = 103;
    private static final int PICK_ADDON_ZIP = 104;
    private static final int PICK_ADDON_FOLDER = 105;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final ExecutorService PREVIEW = Executors.newSingleThreadExecutor();
    private static final ExecutorService DETAILS = Executors.newSingleThreadExecutor();
    private static final ThreadPoolExecutor THUMBNAILS = new ThreadPoolExecutor(2, 2, 0L,
            TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(48));
    private static final String PREFS = "library";
    private static final String KEY_PATH = "active_path";
    private static final String KEY_ORIENTATION = "orientation";
    private static final String KEY_RETENTION = "backup_retention";
    private static final String KEY_PREVIEW_MODE = "character_preview_mode";
    private static final String KEY_BROWSER_GRID = "browser_grid";
    private static final String KEY_NO_CHANGE_WARNING = "warn_unchanged_export";
    private LinearLayout root;
    private FrameLayout screenFrame;
    private LinearLayout activeSheet;
    private View sheetPreviousFocus;
    private GridView list;
    private BrowserAdapter browserAdapter;
    private final LruCache<String, Bitmap> thumbnailCache = new LruCache<String, Bitmap>(16 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };
    private final LruCache<String, Boolean> thumbnailUnavailable = new LruCache<>(256);
    private final Set<String> thumbnailsLoading = new HashSet<>();
    private boolean thumbnailRetryScheduled;
    private LinearLayout detail;
    private Button rosterButton;
    private Button browserViewButton;
    private Button exportButton;
    private TextView exportHint;
    private TextView status;
    private boolean compactLayout;
    private boolean forceCompactLayout;
    private EditText search;
    private File library;
    private LibraryBinding binding;
    private File reconnectLibrary;
    private File backupPickerLibrary;
    private LibraryBinding backupPickerBinding;
    private boolean backupPickerRecovery;
    private Uri backupPickerRequiredTree;
    private String recoveryIssue;
    private LibraryScanner.Catalog catalog;
    private boolean sourceSelectExists;
    private ScreenpackStatus screenpackStatus;
    private LibraryScanner.Item selected;
    private String restoreSelection;
    private String searchText = "";
    private String typeFilter = "All", statusFilter = "All", tagFilter;
    private boolean inferredTagFilter;
    private TagStore tagStore;
    private String tagSource;
    private String importKind;
    private File importLibrary;
    private LibraryBinding importBinding;
    private boolean busy;
    private long lastStickMove;
    private volatile String previewKey;
    private Bitmap previewBitmap;
    private String previewReason;
    private String previewSource;
    private String previewNotice;
    private boolean previewLoading;
    private long catalogEpoch;
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
            typeFilter = state.getString("typeFilter", "All");
            statusFilter = state.getString("statusFilter", "All");
            tagFilter = state.getString("tagFilter");
            inferredTagFilter = state.getBoolean("inferredTagFilter", false);
            restoreSelection = state.getString("selection");
            reconnectLibrary = managedLibrary(state.getString("reconnect"));
            backupPickerLibrary = managedLibrary(state.getString("backupPicker"));
            backupPickerRecovery = state.getBoolean("backupPickerRecovery", false);
            importKind = state.getString("importKind");
            importLibrary = managedLibrary(state.getString("importLibrary"));
            if (importLibrary != null) {
                try { importBinding = LibraryBinding.load(this, importLibrary); }
                catch (IOException ignored) { importLibrary = null; importKind = null; }
            }
            String requiredTree = state.getString("backupPickerRequiredTree");
            backupPickerRequiredTree = requiredTree == null ? null : Uri.parse(requiredTree);
            if (backupPickerLibrary != null) {
                try { backupPickerBinding = LibraryBinding.load(this, backupPickerLibrary); }
                catch (IOException ignored) { backupPickerLibrary = null; backupPickerRecovery = false; }
            }
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
        state.putString("typeFilter", typeFilter);
        state.putString("statusFilter", statusFilter);
        state.putString("tagFilter", tagFilter);
        state.putBoolean("inferredTagFilter", inferredTagFilter);
        if (selected != null) state.putString("selection", selectionKey(selected));
        if (reconnectLibrary != null) state.putString("reconnect", reconnectLibrary.getAbsolutePath());
        if (backupPickerLibrary != null) state.putString("backupPicker", backupPickerLibrary.getAbsolutePath());
        state.putBoolean("backupPickerRecovery", backupPickerRecovery);
        if (backupPickerRequiredTree != null) state.putString("backupPickerRequiredTree", backupPickerRequiredTree.toString());
        state.putString("importKind", importKind);
        if (importLibrary != null) state.putString("importLibrary", importLibrary.getAbsolutePath());
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
        browserViewButton = null;
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
            exportButton = button("Export", this::reviewSourceExport);
            toolbar.addView(exportButton, new LinearLayout.LayoutParams(dp(88), dp(48)));
            toolbar.addView(button("Settings", this::showSettings), new LinearLayout.LayoutParams(dp(94), dp(48)));
            exportHint = label("", 13, false);
            root.addView(exportHint);
            status = null;
        } else {
            root.addView(label("IKEMEN Lab · Android library", 24, true));
            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            root.addView(actions);
            exportButton = button("Export", this::reviewSourceExport);
            actions.addView(exportButton, new LinearLayout.LayoutParams(0, dp(58), 1));
            actions.addView(button("Roster actions", this::showRosterActions), new LinearLayout.LayoutParams(0, dp(58), 1));
            browserViewButton = button(browserGrid() ? "View: Grid" : "View: List", this::toggleBrowserView);
            actions.addView(browserViewButton,
                    new LinearLayout.LayoutParams(0, dp(58), .75f));
            actions.addView(button("Filters", this::showFilters), new LinearLayout.LayoutParams(0, dp(58), .7f));
            actions.addView(button("Settings", this::showSettings), new LinearLayout.LayoutParams(0, dp(58), 1));
            exportHint = label("", 13, false);
            root.addView(exportHint);
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
        list = new GridView(this);
        list.setClipToPadding(false);
        list.setPadding(dp(2), dp(2), dp(8), dp(2));
        list.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        GradientDrawable browserSelector = new GradientDrawable();
        browserSelector.setColor(Color.TRANSPARENT);
        browserSelector.setCornerRadius(dp(9));
        browserSelector.setStroke(dp(3), 0xffffc857);
        list.setSelector(browserSelector);
        list.setDrawSelectorOnTop(true);
        browserAdapter = new BrowserAdapter();
        list.setAdapter(browserAdapter);
        list.setOnItemClickListener((parent, view, position, id) -> activateBrowserItem(position));
        configureBrowserLayout();
        detail = column();
        ScrollView detailScroll = new ScrollView(this);
        detailScroll.setFillViewport(true);
        detailScroll.addView(detail);
        if (landscape) {
            panels.addView(list, new LinearLayout.LayoutParams(0, -1, 1.15f));
            panels.addView(detailScroll, new LinearLayout.LayoutParams(0, -1, 1));
        } else {
            panels.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
            panels.addView(detailScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        }
        renderList();
        updateExportAvailability();
    }

    private void applyOrientation() {
        String choice = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_ORIENTATION, "landscape");
        setRequestedOrientation("portrait".equals(choice)
                ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
    }

    private boolean browserGrid() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_BROWSER_GRID, false);
    }

    private void toggleBrowserView() {
        if (busy) return;
        boolean grid = !browserGrid();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_BROWSER_GRID, grid).apply();
        if (browserViewButton != null) browserViewButton.setText(grid ? "View: Grid" : "View: List");
        int first = list == null ? 0 : list.getFirstVisiblePosition();
        int focused = list == null ? -1 : list.getSelectedItemPosition();
        configureBrowserLayout();
        if (browserAdapter != null) browserAdapter.notifyDataSetChanged();
        if (list != null) list.setSelection(focused >= 0 ? focused : Math.max(0, first));
        showStatus(grid ? "Grid browser selected." : "List browser selected.");
    }

    private void configureBrowserLayout() {
        if (list == null) return;
        int columns = !browserGrid() || browserAdapter != null && browserAdapter.isMessage() ? 1
                : getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE ? 3 : 2;
        list.setNumColumns(columns);
        list.setHorizontalSpacing(dp(4));
        list.setVerticalSpacing(dp(4));
        list.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
    }

    private TagStore tags() throws IOException {
        if (library == null) throw new IOException("Select a source folder first");
        String source = currentSourceIdentity();
        if (!source.equals(tagSource)) {
            tagStore = new TagStore(new File(getFilesDir(), "manual-tags"), source);
            tagSource = source;
        }
        return tagStore;
    }

    private String currentSourceIdentity() throws IOException {
        if (library == null) throw new IOException("Select a source folder first");
        return binding != null && binding.sourceTree != null
                ? binding.sourceTree.toString() : library.getCanonicalPath();
    }

    private CollectionStore collections(String identity) throws IOException {
        return new CollectionStore(new File(getFilesDir(), "collections"), identity);
    }

    private String collectionSourceIdentity() throws IOException {
        if (binding == null || binding.sourceTree == null)
            throw new IOException("Reconnect the linked source to access its saved collections");
        return binding.sourceTree.toString();
    }

    private void showFilters() {
        showActionSheet("Browser filters", new String[]{
                "Type · " + typeFilter, "Status · " + statusFilter,
                "Tag · " + (tagFilter == null ? "All" : (inferredTagFilter ? "Inferred: " : "Manual: ") + tagFilter),
                "Clear filters"}, new Runnable[]{this::showTypeFilter, this::showStatusFilter,
                this::showTagFilter, () -> {
                    typeFilter = "All"; statusFilter = "All"; tagFilter = null; renderList();
                }});
    }

    private void showTypeFilter() {
        String[] choices = {"All", "Characters", "Stages"};
        Runnable[] actions = new Runnable[choices.length];
        for (int i = 0; i < choices.length; i++) {
            String choice = choices[i];
            actions[i] = () -> { typeFilter = choice; renderList(); };
            choices[i] = choiceLabel(choice.equals(typeFilter), choice);
        }
        showActionSheet("Filter by type", choices, actions);
    }

    private void showStatusFilter() {
        String[] choices = {"All", "Enabled", "Disabled", "Unlisted", "Missing"};
        Runnable[] actions = new Runnable[choices.length];
        for (int i = 0; i < choices.length; i++) {
            String choice = choices[i];
            actions[i] = () -> { statusFilter = choice; renderList(); };
            choices[i] = choiceLabel(choice.equals(statusFilter), choice);
        }
        showActionSheet("Filter by status", choices, actions);
    }

    private void showTagFilter() {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add(choiceLabel(tagFilter == null, "All tags"));
        actions.add(() -> { tagFilter = null; renderList(); });
        try {
            int count = 0;
            for (String tag : tags().allTags()) {
                if (count++ >= 40) break;
                labels.add(choiceLabel(tag.equalsIgnoreCase(tagFilter) && !inferredTagFilter, "Manual · " + tag));
                actions.add(() -> { tagFilter = tag; inferredTagFilter = false; renderList(); });
            }
        } catch (IOException unavailable) { showStatus("Manual tags unavailable: " + unavailable.getMessage()); }
        TreeSet<String> inferred = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        if (catalog != null) {
            for (LibraryScanner.Item item : catalog.characters) inferred.addAll(inferredTags(item));
            for (LibraryScanner.Item item : catalog.stages) inferred.addAll(inferredTags(item));
        }
        for (String tag : inferred) {
            labels.add(choiceLabel(tag.equalsIgnoreCase(tagFilter) && inferredTagFilter, "Inferred · " + tag));
            actions.add(() -> { tagFilter = tag; inferredTagFilter = true; renderList(); });
        }
        showActionSheet("Filter by tag · first 40 manual tags", labels.toArray(new String[0]), actions.toArray(new Runnable[0]));
    }

    private static List<String> inferredTags(LibraryScanner.Item item) {
        return TagInference.from(item.reference, item.name, item.author);
    }

    private void showSettings() {
        if (busy) return;
        String orientation = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_ORIENTATION, "landscape");
        String destination;
        try { destination = library == null ? "Choose source first" : BackupPolicy.load(this, library).displayName(); }
        catch (IOException unavailable) { destination = "Unavailable"; }
        boolean warn = warnUnchangedExport();
        showActionSheet("Settings", new String[]{"Source folder · " + sourceFolderLabel(),
                        "Character preview · " + PreviewChoice.label(previewMode()),
                        "Screen orientation · " + ("portrait".equals(orientation) ? "Portrait" : "Landscape"),
                        "Select.def backup location · " + destination,
                        "Backups to keep · " + retentionLabel(),
                        "No-change export warning · " + (warn ? "On" : "Off"),
                        "Reconnect source folder"},
                new Runnable[]{this::pickFolder, this::showPreviewSetting, this::showOrientationSetting,
                        this::showBackupLocationSetting, this::showRetentionSetting,
                        this::showUnchangedSetting, this::reconnectSource});
    }

    private CharacterPreview.Mode previewMode() {
        return PreviewChoice.fromPreference(getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(KEY_PREVIEW_MODE, null));
    }

    private void showPreviewSetting() {
        showActionSheet("Character preview · " + PreviewChoice.label(previewMode()),
                new String[]{choiceLabel(previewMode() == CharacterPreview.Mode.NEUTRAL, "Neutral stance"),
                        choiceLabel(previewMode() == CharacterPreview.Mode.PORTRAIT, "Portrait"),
                        choiceLabel(previewMode() == CharacterPreview.Mode.NEUTRAL_OVER_PORTRAIT, "Neutral over portrait")},
                new Runnable[]{() -> choosePreviewMode(CharacterPreview.Mode.NEUTRAL),
                        () -> choosePreviewMode(CharacterPreview.Mode.PORTRAIT),
                        () -> choosePreviewMode(CharacterPreview.Mode.NEUTRAL_OVER_PORTRAIT)});
    }

    private void choosePreviewMode(CharacterPreview.Mode mode) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_PREVIEW_MODE, mode.name()).apply();
        previewKey = null;
        previewBitmap = null;
        previewReason = null;
        previewSource = null;
        previewNotice = null;
        previewLoading = false;
        renderDetail();
        showStatus("Character preview: " + PreviewChoice.label(mode) + ".");
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

    private boolean warnUnchangedExport() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_NO_CHANGE_WARNING, true);
    }

    private void showUnchangedSetting() {
        boolean warn = warnUnchangedExport();
        showActionSheet("No-change export warning", new String[]{
                        choiceLabel(warn, "Show warning before Export anyway (default)"),
                        choiceLabel(!warn, "Skip extra warning; keep export review")},
                new Runnable[]{() -> chooseUnchangedWarning(true), () -> chooseUnchangedWarning(false)});
    }

    private void chooseUnchangedWarning(boolean enabled) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_NO_CHANGE_WARNING, enabled).apply();
        showStatus("No-change export warning " + (enabled ? "on" : "off") + ". Export still requires a review and tap.");
    }

    private void showBackupLocationSetting() {
        if (library == null) { showStatus("Choose a source folder in Settings before selecting where backups go."); return; }
        BackupPolicy policy;
        try { policy = BackupPolicy.load(this, library); }
        catch (IOException error) { showStatus("Backup setting unavailable: " + error.getMessage()); return; }
        if (hasPending(library)) {
            Uri needed;
            try { needed = requiredRecoveryCustomTree(policy); }
            catch (IOException error) { showStatus("Recovery backup setting unavailable: " + error.getMessage()); return; }
            showDecisionSheet("Backup location locked during recovery",
                    "Resolve the pending source operation before changing where future backups go."
                            + (needed == null ? " Private-preimage rollback does not need a custom backup folder."
                            : " To finish recovery, reconnect the same custom folder: " + policy.displayNameFor(needed) + "."),
                    "Reconnect required folder", needed == null ? null : this::pickCustomBackupFolder);
            return;
        }
        showActionSheet("Verified source preimage backups · " + policy.displayName(),
                new String[]{choiceLabel(policy.kind == BackupPolicy.Kind.SOURCE, "Source data/select-backups (default)"),
                        choiceLabel(policy.kind == BackupPolicy.Kind.APP, "App backup directory"),
                        choiceLabel(policy.kind == BackupPolicy.Kind.CUSTOM,
                                "Custom folder" + (policy.customTree == null ? "" : " · " + policy.customName))},
                new Runnable[]{() -> chooseBackupLocation(BackupPolicy.Kind.SOURCE),
                        () -> chooseBackupLocation(BackupPolicy.Kind.APP), this::pickCustomBackupFolder});
    }

    private void chooseBackupLocation(BackupPolicy.Kind kind) {
        if (library == null) return;
        if (hasPending(library)) { showStatus("Resolve pending source recovery before changing the backup location."); return; }
        try {
            BackupPolicy chosen = BackupPolicy.choose(this, library, kind, null, null);
            showStatus("Verified source preimage backups: " + chosen.displayName()
                    + ". App working undo and recovery snapshots remain private.");
        } catch (IOException error) { showStatus("Backup setting unchanged: " + error.getMessage()); }
    }

    private void pickCustomBackupFolder() {
        if (busy || library == null) return;
        backupPickerRecovery = hasPending(library);
        if (backupPickerRecovery) {
            try {
                backupPickerRequiredTree = requiredRecoveryCustomTree(BackupPolicy.load(this, library));
                if (backupPickerRequiredTree == null) {
                    showStatus("This recovery step does not need a custom backup folder."); return;
                }
            } catch (IOException error) { showStatus("Backup setting unavailable: " + error.getMessage()); return; }
        } else backupPickerRequiredTree = null;
        backupPickerLibrary = library;
        backupPickerBinding = binding;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, PICK_BACKUP_TREE);
    }

    private Uri requiredRecoveryCustomTree(BackupPolicy policy) throws IOException {
        String store = new SelectStorage(policy.library).pendingRestoreBackupStoreIdentity();
        if (store != null && store.startsWith("custom:")) {
            for (Uri known : policy.knownCustomTrees)
                if (store.startsWith("custom:" + known + ":")) return known;
            throw new IOException("The selected restore backup folder is no longer in this library's saved locations");
        }
        if (new File(RosterStore.selectFile(policy.library).getParentFile(),
                "select-backups/pending-restore.properties").isFile()
                && policy.kind == BackupPolicy.Kind.CUSTOM) return policy.customTree;
        return null;
    }

    private void showRosterActions() {
        if (busy) return;
        showActionSheet("Roster actions", new String[]{"Arrange roster · select screen", "Collections · working roster", "Import one add-on · add only", "Library health · selected item", "Refresh library", "Review export to linked source", "Backups and restore",
                        "Save a copy elsewhere", "Recovery"},
                new Runnable[]{() -> showArrangement(0), this::showCollections, this::showImportActions, this::showLibraryHealth, this::refreshCatalog, this::reviewSourceExport, this::showBackups,
                        this::pickExport, this::showRecovery});
    }

    private File importRoot() { return new File(getFilesDir(), "addon-imports"); }

    private List<File> pendingImports() {
        List<File> pending = new ArrayList<>();
        File[] stages = importRoot().listFiles();
        if (stages != null) for (File stage : stages)
            if (stage.isDirectory() && new File(stage, "install.properties").isFile()) pending.add(stage);
        return pending;
    }

    private void discardUnreviewedImports() {
        File[] stages = importRoot().listFiles();
        if (stages != null) for (File stage : stages)
            if (stage.isDirectory() && !new File(stage, "install.properties").exists()) AddonPackage.erase(stage);
    }

    private void showImportActions() {
        if (busy || library == null || binding == null || binding.sourceTree == null) {
            showStatus("Reconnect the linked source before importing an add-on."); return;
        }
        List<File> pending = pendingImports();
        if (!pending.isEmpty()) {
            List<String> labels = new ArrayList<>();
            List<Runnable> actions = new ArrayList<>();
            for (File stage : pending) {
                String description;
                try { description = AddonInstallTransaction.pendingDescription(stage); }
                catch (IOException error) { description = "Damaged import journal"; }
                labels.add("Recover · " + description);
                actions.add(() -> recoverImport(stage));
            }
            showActionSheet("Resolve incomplete import before another add-on", labels.toArray(new String[0]),
                    actions.toArray(new Runnable[0]));
            return;
        }
        discardUnreviewedImports();
        showActionSheet("Import exactly one add-on · source folder only", new String[]{
                "Character ZIP", "Character folder", "Stage ZIP", "Stage folder"},
                new Runnable[]{() -> pickAddon("chars", true), () -> pickAddon("chars", false),
                        () -> pickAddon("stages", true), () -> pickAddon("stages", false)});
    }

    private void pickAddon(String kind, boolean zip) {
        if (busy || library == null || binding == null || binding.sourceTree == null) return;
        importKind = kind;
        importLibrary = library;
        importBinding = binding;
        Intent intent = new Intent(zip ? Intent.ACTION_OPEN_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT_TREE);
        if (zip) { intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("*/*"); }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, zip ? PICK_ADDON_ZIP : PICK_ADDON_FOLDER);
    }

    private void stagePickedAddon(Uri uri, Intent data, boolean zip) {
        File current = importLibrary;
        LibraryBinding expected = importBinding;
        String kind = importKind;
        importLibrary = null; importBinding = null; importKind = null;
        if (current == null || kind == null || !sameBinding(current, expected)) return;
        boolean releaseFolderGrant = false;
        if (!zip) {
            if ((data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0) {
                showStatus("Add-on folder needs read access to stage its files."); return;
            }
            boolean alreadyPersisted = false;
            for (android.content.UriPermission permission : getContentResolver().getPersistedUriPermissions())
                if (permission.getUri().equals(uri) && permission.isReadPermission()) alreadyPersisted = true;
            try {
                if (!alreadyPersisted) getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
                releaseFolderGrant = !alreadyPersisted;
            }
            catch (SecurityException denied) {
                showStatus("Add-on folder needs lasting read access to stage its files."); return;
            }
        }
        boolean releaseAfterStage = releaseFolderGrant;
        busy = true;
        showStatus("Staging one add-on privately for review…");
        IO.execute(() -> {
            AddonPackage addon = null;
            try {
                expected.requireCurrent(this);
                if (zip) {
                    String name;
                    try (Cursor cursor = getContentResolver().query(uri,
                            new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
                        if (cursor == null || !cursor.moveToFirst()) throw new IOException("ZIP document is unavailable");
                        name = cursor.getString(0);
                    }
                    if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".zip"))
                        throw new IOException("Choose one ZIP file");
                    InputStream stream = getContentResolver().openInputStream(uri);
                    if (stream == null) throw new IOException("ZIP document cannot be read");
                    addon = AddonPackage.fromZip(stream, importRoot(), kind);
                } else addon = AddonPackage.fromFolder(
                        new SafLibraryFiles(getContentResolver(), uri).root(), importRoot(), kind);
                LibraryFiles.Node source = openSource(expected);
                AddonHealth.Report health = AddonHealth.inspectStage(addon, source);
                SafAddonDestination destination = new SafAddonDestination(getContentResolver(), expected.sourceTree);
                AddonInstallTransaction.Review reviewed = AddonInstallTransaction.review(addon, destination, source);
                AddonPackage staged = addon;
                runOnUiThread(() -> { if (sameBinding(current, expected)) {
                    busy = false;
                    String detail = "Destination: " + reviewed.kind + "/" + reviewed.name
                            + "\nFiles: " + reviewed.files + " · bytes: " + reviewed.bytes
                            + "\nSource: " + sourceFolderLabel()
                            + "\nNo existing file is replaced. select.def is not edited."
                            + "\n" + health.summary();
                    showAddonReview(current, expected, staged, reviewed, detail, health);
                } else AddonPackage.erase(staged.stageRoot); });
            } catch (Exception error) {
                if (addon != null) AddonPackage.erase(addon.stageRoot);
                runOnUiThread(() -> { if (sameBinding(current, expected)) {
                    busy = false; showStatus("Import review unavailable: " + error.getMessage());
                } });
            } finally {
                if (releaseAfterStage) try {
                    getContentResolver().releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (SecurityException ignored) { }
            }
        });
    }

    private void showAddonReview(File current, LibraryBinding expected, AddonPackage addon,
                                 AddonInstallTransaction.Review reviewed, String detail, AddonHealth.Report health) {
        LinearLayout panel = column();
        panel.addView(sheetTitle("Review add-only import"),
                new LinearLayout.LayoutParams(-1, dp(compactLayout ? 26 : 48)));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(label(detail, compactLayout ? 14 : 15, false));
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        if (!health.blocked) panel.addView(button("Install reviewed add-on", () -> {
            dismissSheet(); executeAddonImport(current, expected, addon, reviewed, health.signature());
        }), new LinearLayout.LayoutParams(-1, dp(48)));
        panel.addView(button("Discard private stage", () -> {
            dismissSheet(); AddonPackage.erase(addon.stageRoot);
            showStatus("Add-on stage discarded; source unchanged.");
        }), new LinearLayout.LayoutParams(-1, dp(48)));
        presentSheet(panel, scroll);
    }

    private void executeAddonImport(File current, LibraryBinding expected, AddonPackage addon,
                                    AddonInstallTransaction.Review reviewed, String reviewedHealth) {
        if (busy || !sameBinding(current, expected)) {
            showStatus("Source changed; review import again."); return;
        }
        busy = true;
        IO.execute(() -> {
            try {
                expected.requireCurrent(this);
                AddonHealth.Report currentHealth = AddonHealth.inspectStage(addon, openSource(expected));
                if (currentHealth.blocked || !currentHealth.signature().equals(reviewedHealth))
                    throw new IOException("Add-on file references changed; review import again");
                SafAddonDestination destination = new SafAddonDestination(getContentResolver(), expected.sourceTree);
                AddonInstallTransaction.install(addon, destination, openSource(expected), reviewed);
            } catch (Exception error) { runOnUiThread(() -> { if (sameBinding(current, expected)) {
                busy = false; showStatus("Import stopped: " + error.getMessage());
            } }); return; }
            try {
                LibraryScanner.Catalog scanned = scanWorking(current, expected);
                runOnUiThread(() -> { if (sameBinding(current, expected)) {
                    catalog = scanned; catalogEpoch++; busy = false; renderList();
                    showStatus("Add-on installed at " + reviewed.kind + "/" + reviewed.name
                            + ". Source roster unchanged; enable it separately if wanted.");
                } });
            } catch (IOException refresh) { runOnUiThread(() -> { if (sameBinding(current, expected)) {
                catalog = null; selected = null; busy = false; renderList();
                showStatus("Add-on installed at " + reviewed.kind + "/" + reviewed.name
                        + "; source refresh unavailable: " + refresh.getMessage());
            } }); }
        });
    }

    private void recoverImport(File stage) {
        if (busy || library == null || binding == null || binding.sourceTree == null) return;
        File current = library;
        LibraryBinding expected = binding;
        showDecisionSheet("Recover incomplete import",
                "Verify the journal and provider documents. A verified final add-on is kept; only a journal-owned pending folder can be removed.",
                "Run recovery", () -> {
                    busy = true;
                    IO.execute(() -> {
                        try {
                            expected.requireCurrent(this);
                            AddonInstallTransaction.recover(stage,
                                    new SafAddonDestination(getContentResolver(), expected.sourceTree));
                            LibraryScanner.Catalog scanned = scanWorking(current, expected);
                            runOnUiThread(() -> { if (sameBinding(current, expected)) {
                                catalog = scanned; catalogEpoch++; busy = false; renderList();
                                showStatus("Import recovery completed. Check the library before another import.");
                            } });
                        } catch (Exception error) { runOnUiThread(() -> { if (sameBinding(current, expected)) {
                            busy = false; showStatus("Import recovery stopped: " + error.getMessage());
                        } }); }
                    });
                });
    }

    private void showLibraryHealth() {
        if (busy || selected == null || library == null || binding == null) {
            showStatus("Choose an available character or stage first."); return;
        }
        LibraryScanner.Item item = selected;
        File current = library;
        LibraryBinding expected = binding;
        busy = true;
        IO.execute(() -> {
            try {
                AddonHealth.Report report = AddonHealth.inspectLibrary(item, openSource(expected));
                runOnUiThread(() -> { if (sameBinding(current, expected)) {
                    busy = false;
                    showDecisionSheet("Read-only health · " + item.name,
                            report.summary() + "\nThis check does not repair, remove, or change roster entries.", "", null);
                } });
            } catch (IOException error) { runOnUiThread(() -> { if (sameBinding(current, expected)) {
                busy = false; showStatus("Health report unavailable: " + error.getMessage());
            } }); }
        });
    }

    private void showCollections() {
        if (busy || library == null || binding == null || binding.sourceTree == null) {
            showStatus("Reconnect the linked source to access its saved collections."); return;
        }
        File current = library;
        LibraryBinding expected = binding;
        String source;
        try { source = collectionSourceIdentity(); }
        catch (IOException error) { showStatus(error.getMessage()); return; }
        busy = true;
        IO.execute(() -> {
            try {
                List<CollectionStore.Record> saved = collections(source).list();
                runOnUiThread(() -> { if (sameBinding(current, expected)) {
                    busy = false; renderCollections(saved);
                } });
            } catch (IOException error) { runOnUiThread(() -> { if (sameBinding(current, expected)) {
                busy = false; showStatus("Collections unavailable: " + error.getMessage());
            } }); }
        });
    }

    private void renderCollections(List<CollectionStore.Record> saved) {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add("Create snapshot from working roster");
        actions.add(this::createStaticCollection);
        labels.add("Create smart collection · dynamic rules");
        actions.add(this::createSmartCollection);
        for (CollectionStore.Record record : saved) {
            labels.add((record.smart ? "Smart · " : "Snapshot · ") + record.name);
            actions.add(() -> showCollection(record));
        }
        showActionSheet("Collections · private to this source (" + saved.size() + ")",
                labels.toArray(new String[0]), actions.toArray(new Runnable[0]));
    }

    private interface CollectionNameAction { void apply(String value); }

    private void collectionNameInput(String title, String initial, CollectionNameAction action) {
        collectionTextInput(title, "Collection names are stored privately for this source.", initial, action);
    }

    private void collectionTextInput(String title, String description, String initial, CollectionNameAction action) {
        LinearLayout panel = column();
        panel.addView(sheetTitle(title), new LinearLayout.LayoutParams(-1, dp(compactLayout ? 26 : 48)));
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = column();
        content.addView(label(description, 15, false));
        EditText name = new EditText(this);
        name.setSingleLine(true); name.setTextColor(Color.WHITE); name.setHint("Collection name");
        name.setText(initial);
        content.addView(name, new LinearLayout.LayoutParams(-1, dp(55)));
        TextView feedback = label("", 14, false);
        content.addView(feedback);
        scroll.addView(content);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        panel.addView(button("Save", () -> {
            String value = name.getText().toString().trim();
            if (value.isEmpty() || value.length() > 60 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
                feedback.setText("Use 1–60 characters on one line."); return;
            }
            dismissSheet(); action.apply(value);
        }), new LinearLayout.LayoutParams(-1, dp(48)));
        panel.addView(button("Close", this::dismissSheet), new LinearLayout.LayoutParams(-1, dp(48)));
        presentSheet(panel, scroll);
        name.requestFocus();
    }

    private void createStaticCollection() {
        String intendedSource;
        try { intendedSource = collectionSourceIdentity(); }
        catch (IOException error) { showStatus(error.getMessage()); return; }
        collectionNameInput("New roster snapshot", "", name -> {
            File current = library;
            LibraryBinding expected = binding;
            String source;
            try { source = collectionSourceIdentity(); }
            catch (IOException error) { showStatus(error.getMessage()); return; }
            if (!intendedSource.equals(source)) { showStatus("Source changed; create the collection again."); return; }
            busy = true;
            IO.execute(() -> {
                try {
                    byte[] working = new SelectStorage(current).readWorking().bytes;
                    CollectionStore.Record saved = collections(source).create(name, false, new RosterProfile(working).snapshot());
                    runOnUiThread(() -> { if (sameBinding(current, expected)) {
                        busy = false; showCollection(saved);
                        showStatus("Collection saved privately; source unchanged.");
                    } });
                } catch (IOException error) { runOnUiThread(() -> { if (sameBinding(current, expected)) {
                    busy = false; showStatus("Collection not saved: " + error.getMessage());
                } }); }
            });
        });
    }

    private void createSmartCollection() {
        String intendedSource;
        try { intendedSource = collectionSourceIdentity(); }
        catch (IOException error) { showStatus(error.getMessage()); return; }
        collectionNameInput("New smart collection", "", name -> {
            try {
                if (!intendedSource.equals(collectionSourceIdentity())) {
                    showStatus("Source changed; create the collection again."); return;
                }
            } catch (IOException error) { showStatus(error.getMessage()); return; }
            CollectionStore.Record draft = new CollectionStore.Record(java.util.UUID.randomUUID().toString(), name, true);
            draft.sourceIdentity = intendedSource;
            showSmartRuleChoices(draft, true);
        });
    }

    private void showCollection(CollectionStore.Record record) {
        String count = record.smart ? record.rules.size() + " supported rule(s), re-evaluated on preview"
                : record.characters.size() + " character slots, " + record.stages.size() + " stages";
        showActionSheet(record.name + " · " + count, new String[]{
                "Preview activation · private working roster only",
                record.smart ? "Edit dynamic rules" : "Edit ordered entries",
                "Rename collection", "Delete collection"}, new Runnable[]{
                () -> previewCollection(record),
                record.smart ? () -> showSmartRules(record) : () -> showCollectionEditor(record),
                () -> collectionNameInput("Rename collection", record.name,
                        name -> changeCollection(record, changed -> changed.name = name)),
                () -> showDecisionSheet("Delete " + record.name,
                        "Delete this private collection? The working roster and source stay unchanged.",
                        "Delete collection", () -> deleteCollection(record))});
    }

    private interface CollectionChange { void apply(CollectionStore.Record record) throws IOException; }

    private void changeCollection(CollectionStore.Record record, CollectionChange change) {
        if (busy || library == null) return;
        File current = library;
        LibraryBinding expected = binding;
        String source;
        try { source = collectionSourceIdentity(); }
        catch (IOException error) { showStatus(error.getMessage()); return; }
        if (!source.equals(record.sourceIdentity)) { showStatus("Source changed; reopen collections."); return; }
        busy = true;
        IO.execute(() -> {
            try {
                CollectionStore store = collections(source);
                CollectionStore.Record fresh = store.get(record.id);
                change.apply(fresh);
                store.save(fresh);
                runOnUiThread(() -> { if (sameBinding(current, expected)) {
                    busy = false; showCollection(fresh);
                    showStatus("Collection saved privately; source unchanged.");
                } });
            } catch (IOException error) { runOnUiThread(() -> { if (sameBinding(current, expected)) {
                busy = false; showStatus("Collection not changed: " + error.getMessage());
            } }); }
        });
    }

    private void showCollectionEditor(CollectionStore.Record record) {
        String selectedItem = selected == null ? "Choose an item in the browser first" : "Add selected · " + selected.name;
        showActionSheet("Edit " + record.name + " · ordered snapshot", new String[]{
                "Character slots · " + record.characters.size(), "Stage entries · " + record.stages.size(),
                selectedItem, "Add randomselect slot", "Add empty slot", "Back to collection"}, new Runnable[]{
                () -> showCollectionEntries(record, true, 0), () -> showCollectionEntries(record, false, 0),
                () -> {
                    LibraryScanner.Item item = selected;
                    if (item == null || item.warning != null || item.defNode == null) {
                        showStatus("Select an available character or stage in the browser first."); return;
                    }
                    changeCollection(record, changed -> {
                        if (item.kind.equals("characters")) changed.characters.add(item.reference);
                        else changed.stages.add(item.reference);
                    });
                },
                () -> changeCollection(record, changed -> changed.characters.add("randomselect")),
                () -> changeCollection(record, changed -> changed.characters.add("empty")),
                () -> showCollection(record)});
    }

    private void showCollectionEntries(CollectionStore.Record record, boolean characters, int page) {
        List<String> entries = characters ? record.characters : record.stages;
        int size = 40;
        int pages = Math.max(1, (entries.size() + size - 1) / size);
        int current = Math.max(0, Math.min(page, pages - 1));
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (current > 0) { labels.add("Previous page"); actions.add(() -> showCollectionEntries(record, characters, current - 1)); }
        for (int i = current * size; i < Math.min(entries.size(), (current + 1) * size); i++) {
            final int position = i;
            String text = entries.get(i);
            labels.add((i + 1) + ". " + (text.length() > 80 ? text.substring(0, 79) + "…" : text));
            actions.add(() -> showCollectionEntryActions(record, characters, position, current));
        }
        if (current + 1 < pages) { labels.add("Next page"); actions.add(() -> showCollectionEntries(record, characters, current + 1)); }
        labels.add("Back to editor"); actions.add(() -> showCollectionEditor(record));
        showActionSheet((characters ? "Character slots" : "Stage entries") + " · page " + (current + 1) + "/" + pages,
                labels.toArray(new String[0]), actions.toArray(new Runnable[0]));
    }

    private void showCollectionEntryActions(CollectionStore.Record record, boolean characters, int position, int page) {
        showActionSheet("Entry " + (position + 1), new String[]{"Move up", "Move down", "Remove entry", "Back to entries"},
                new Runnable[]{
                        () -> moveCollectionEntry(record, characters, position, -1),
                        () -> moveCollectionEntry(record, characters, position, 1),
                        () -> changeCollection(record, changed -> {
                            List<String> entries = characters ? changed.characters : changed.stages;
                            if (position >= entries.size()) throw new IOException("Collection changed; reopen entries");
                            entries.remove(position);
                        }),
                        () -> showCollectionEntries(record, characters, page)});
    }

    private void moveCollectionEntry(CollectionStore.Record record, boolean characters, int position, int delta) {
        changeCollection(record, changed -> {
            List<String> entries = characters ? changed.characters : changed.stages;
            int next = position + delta;
            if (position < 0 || next < 0 || next >= entries.size()) throw new IOException("Entry is at the collection edge");
            java.util.Collections.swap(entries, position, next);
        });
    }

    private void showSmartRules(CollectionStore.Record record) {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add("Combine rules · " + (record.allRules ? "All (AND)" : "Any (OR)"));
        actions.add(() -> changeCollection(record, changed -> changed.allRules = !changed.allRules));
        labels.add("Add supported rule (max 6)");
        actions.add(() -> showSmartRuleChoices(record, false));
        for (int i = 0; i < record.rules.size(); i++) {
            int position = i;
            CollectionStore.Rule rule = record.rules.get(i);
            labels.add((i + 1) + ". " + rule.field + " · " + rule.value);
            actions.add(() -> showActionSheet("Rule " + (position + 1), new String[]{"Remove rule", "Back to rules"},
                    new Runnable[]{() -> changeCollection(record, changed -> {
                        if (changed.rules.size() <= 1) throw new IOException("Smart collection needs at least one rule");
                        if (position >= changed.rules.size()) throw new IOException("Rules changed; reopen collection");
                        changed.rules.remove(position);
                    }), () -> showSmartRules(record)}));
        }
        labels.add("Back to collection"); actions.add(() -> showCollection(record));
        showActionSheet("Smart rules · current source is re-evaluated on preview",
                labels.toArray(new String[0]), actions.toArray(new Runnable[0]));
    }

    private void showSmartRuleChoices(CollectionStore.Record record, boolean creating) {
        if (record.rules.size() >= 6) { showStatus("Smart collections support at most six rules."); return; }
        String[] labels = {"Name contains…", "Author contains…", "Manual tag is…", "Inferred cue is…",
                "Type · Characters", "Type · Stages", "Status · Enabled", "Status · Disabled", "Status · Unlisted"};
        Runnable[] actions = {
                () -> promptSmartValue(record, creating, "name"),
                () -> promptSmartValue(record, creating, "author"),
                () -> promptSmartValue(record, creating, "manualTag"),
                () -> promptSmartValue(record, creating, "inferredTag"),
                () -> addSmartRule(record, creating, "type", "Characters"),
                () -> addSmartRule(record, creating, "type", "Stages"),
                () -> addSmartRule(record, creating, "status", "Enabled"),
                () -> addSmartRule(record, creating, "status", "Disabled"),
                () -> addSmartRule(record, creating, "status", "Unlisted")};
        showActionSheet("Supported smart fields · " + (creating ? "choose first rule" : "add rule"), labels, actions);
    }

    private void promptSmartValue(CollectionStore.Record record, boolean creating, String field) {
        collectionTextInput("Smart rule · " + field,
                "Supported field only. This rule is re-evaluated against the linked source on preview.", "",
                value -> addSmartRule(record, creating, field, value));
    }

    private void addSmartRule(CollectionStore.Record record, boolean creating, String field, String value) {
        if (!creating) {
            changeCollection(record, changed -> changed.rules.add(new CollectionStore.Rule(field, value)));
            return;
        }
        File current = library;
        LibraryBinding expected = binding;
        String source;
        try { source = collectionSourceIdentity(); }
        catch (IOException error) { showStatus(error.getMessage()); return; }
        if (!source.equals(record.sourceIdentity)) { showStatus("Source changed; reopen collections."); return; }
        record.rules.add(new CollectionStore.Rule(field, value));
        busy = true;
        IO.execute(() -> {
            try {
                collections(source).save(record);
                runOnUiThread(() -> { if (sameBinding(current, expected)) {
                    busy = false; showCollection(record);
                    showStatus("Smart collection saved; rules re-evaluate on preview.");
                } });
            } catch (IOException error) { runOnUiThread(() -> { if (sameBinding(current, expected)) {
                busy = false; showStatus("Smart collection not saved: " + error.getMessage());
            } }); }
        });
    }

    private void deleteCollection(CollectionStore.Record record) {
        File current = library;
        LibraryBinding expected = binding;
        String source;
        try { source = collectionSourceIdentity(); }
        catch (IOException error) { showStatus(error.getMessage()); return; }
        if (!source.equals(record.sourceIdentity)) { showStatus("Source changed; reopen collections."); return; }
        busy = true;
        IO.execute(() -> {
            try {
                collections(source).delete(record.id);
                runOnUiThread(() -> { if (sameBinding(current, expected)) {
                    busy = false; showStatus("Private collection deleted; working roster unchanged."); showCollections();
                } });
            } catch (IOException error) { runOnUiThread(() -> { if (sameBinding(current, expected)) {
                busy = false; showStatus("Delete stopped: " + error.getMessage());
            } }); }
        });
    }

    private void previewCollection(CollectionStore.Record record) {
        if (busy || library == null || catalog == null || binding == null || binding.sourceTree == null) {
            showStatus("Reconnect and refresh the source before activating a collection."); return;
        }
        if (screenpackStatus != null && screenpackStatus.knownAlternate) {
            showDecisionSheet("Collection activation unavailable",
                    "This screenpack uses " + screenpackStatus.select + ". Collections apply to the private working data/select.def, which is not the active screenpack roster.",
                    "", null);
            return;
        }
        File current = library;
        LibraryBinding expected = binding;
        long epoch = catalogEpoch;
        String source;
        try { source = collectionSourceIdentity(); }
        catch (IOException error) { showStatus(error.getMessage()); return; }
        if (!source.equals(record.sourceIdentity)) { showStatus("Source changed; reopen collections."); return; }
        busy = true;
        IO.execute(() -> {
            try {
                CollectionStore.Record fresh = collections(source).get(record.id);
                TagStore manual = new TagStore(new File(getFilesDir(), "manual-tags"), source);
                byte[] working = new SelectStorage(current).readWorking().bytes;
                LibraryFiles.Node activeSource = openSource(expected);
                CollectionPlan.requireActiveWorkingRoster(activeSource);
                LibraryScanner.Catalog freshCatalog = LibraryScanner.scan(activeSource, working);
                CollectionPlan plan = CollectionPlan.prepare(working, fresh, freshCatalog, manual);
                runOnUiThread(() -> { if (sameBinding(current, expected) && catalogEpoch == epoch) {
                    busy = false;
                    String message = (fresh.smart ? "Dynamic rules re-evaluated against the current library.\n" : "Saved ordered entries.\n")
                            + "Characters: " + plan.entries.characters.size() + " · Stages: " + plan.entries.stages.size()
                            + "\nWorking roster SHA-256: " + plan.workingSha.substring(0, 12) + "…"
                            + "\nUnknown select.def sections and inactive lines are retained. Source files stay unchanged until separate Export review."
                            + (screenpackStatus != null && screenpackStatus.warning != null ? "\nScreenpack: " + screenpackStatus.warning : "")
                            + (plan.missing.isEmpty() ? "" : "\nMissing references (activation blocked): " + plan.missing.size()
                            + "\n" + String.join(", ", plan.missing.subList(0, Math.min(5, plan.missing.size()))))
                            + (!plan.changed ? "\nWorking roster already matches this collection." : "");
                    showDecisionSheet("Review collection activation · " + fresh.name, message,
                            "Activate working roster", plan.missing.isEmpty() && plan.changed
                                    ? () -> activateCollection(current, expected, source, epoch, plan, fresh.id) : null);
                } });
            } catch (IOException error) { runOnUiThread(() -> { if (sameBinding(current, expected)) {
                busy = false; showStatus("Collection preview stopped: " + error.getMessage());
            } }); }
        });
    }

    private void activateCollection(File current, LibraryBinding expected, String source, long epoch,
                                    CollectionPlan plan, String id) {
        if (busy || !sameBinding(current, expected) || catalogEpoch != epoch) {
            showStatus("Library changed after review; preview the collection again."); return;
        }
        busy = true;
        IO.execute(() -> {
            try {
                expected.requireCurrent(this);
                CollectionPlan.requireActiveWorkingRoster(openSource(expected));
                SelectStorage storage = new SelectStorage(current);
                byte[] working = storage.readWorking().bytes;
                LibraryScanner.Catalog currentCatalog = scanWorking(current, expected);
                CollectionPlan refreshed = CollectionPlan.prepare(working, collections(source).get(id), currentCatalog,
                        new TagStore(new File(getFilesDir(), "manual-tags"), source));
                if (!plan.workingSha.equals(refreshed.workingSha) || !refreshed.missing.isEmpty()
                        || !java.util.Arrays.equals(plan.replacement, refreshed.replacement))
                    throw new IOException("Collection, source, or working roster changed; review activation again");
                CollectionPlan.requireActiveWorkingRoster(openSource(expected));
                storage.commitWorking(plan.workingSha, plan.replacement,
                        "collection:activate:" + id, retention());
            } catch (Exception error) { runOnUiThread(() -> { if (sameBinding(current, expected)) {
                busy = false; showStatus("Collection activation stopped: " + error.getMessage());
            } }); return; }
            try {
                LibraryScanner.Catalog scanned = scanWorking(current, expected);
                runOnUiThread(() -> { if (sameBinding(current, expected)) {
                    catalog = scanned; catalogEpoch++;
                    selected = selected == null ? null : find(scanned, selected);
                    busy = false; renderList();
                    showStatus("Collection activated in private working roster. Review Export to change source select.def.");
                } });
            } catch (IOException refresh) { runOnUiThread(() -> { if (sameBinding(current, expected)) {
                catalog = null; selected = null; busy = false; renderList();
                showStatus("Collection activated locally. Source refresh unavailable: " + refresh.getMessage());
            } }); }
        });
    }

    private void showArrangement(int page) {
        if (busy || library == null) { showStatus("Choose a source folder first."); return; }
        File current = library;
        LibraryBinding expected = binding;
        busy = true;
        IO.execute(() -> {
            try {
                RosterArrangement arrangement = new RosterArrangement(new SelectStorage(current).readWorking().bytes);
                runOnUiThread(() -> { if (sameBinding(current, expected)) {
                    busy = false;
                    renderArrangement(page, arrangement);
                } });
            } catch (IOException error) { runOnUiThread(() -> { if (sameBinding(current, expected)) {
                busy = false; showStatus("Roster arrangement unavailable: " + error.getMessage());
            } }); }
        });
    }

    private void renderArrangement(int page, RosterArrangement arrangement) {
        List<RosterArrangement.Slot> slots = arrangement.slots();
        ScreenpackStatus pack = screenpackStatus;
        LinearLayout panel = column();
        panel.addView(sheetTitle("Select screen · approximate slot order"), new LinearLayout.LayoutParams(-1, dp(compactLayout ? 26 : 48)));
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = column();
        scroll.addView(content);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        content.addView(label(pack == null ? "Screenpack information unavailable. Refresh the library."
                : "Motif: " + pack.motif + "\nSelect: " + pack.select
                + "\nCapacity: " + (pack.capacity() == 0 ? "unknown" : pack.rows + " × " + pack.columns + " = " + pack.capacity())
                + " · Active slots: " + slots.size()
                + (pack.capacity() > 0 && slots.size() > pack.capacity() ? " · " + (slots.size() - pack.capacity()) + " beyond capacity" : "")
                + "\nStatic order preview only; actual game placement may differ."
                + (pack.warning == null ? "" : "\nWarning: " + pack.warning), 15, false));
        boolean editable = pack != null && pack.globalRoster;
        int pageSize = 100;
        int pageCount = Math.max(1, (slots.size() + pageSize - 1) / pageSize);
        int currentPage = Math.max(0, Math.min(page, pageCount - 1));
        int start = currentPage * pageSize;
        int end = Math.min(slots.size(), start + pageSize);
        content.addView(label("Slots " + (slots.isEmpty() ? 0 : start + 1) + "–" + end + " of " + slots.size()
                + " · page " + (currentPage + 1) + "/" + pageCount, 14, true));
        if (pack != null && pack.capacity() > 0 && pack.columns > 16 && currentPage == 0)
            content.addView(label("Grid preview unavailable for more than 16 columns; the ordered slot list remains available.", 14, false));
        if (pack != null && pack.capacity() > 0 && pack.columns <= 16 && currentPage == 0) {
            int previewColumns = pack.columns;
            int previewCount = Math.min(Math.min(pack.capacity(), slots.size()), 100);
            content.addView(label("Grid preview · first " + previewCount + " occupied positions", 14, true));
            GridLayout grid = new GridLayout(this);
            grid.setColumnCount(previewColumns);
            for (int i = 0; i < previewCount; i++) {
                RosterArrangement.Slot slot = slots.get(i);
                LinearLayout cell = column();
                cell.setBackgroundColor(0xff243440);
                ImageView portrait = new ImageView(this);
                portrait.setScaleType(ImageView.ScaleType.FIT_CENTER);
                cell.addView(portrait, new LinearLayout.LayoutParams(dp(64), dp(48)));
                TextView caption = label((i + 1) + " " + slot.title, 10, false);
                caption.setSingleLine(true);
                caption.setEllipsize(android.text.TextUtils.TruncateAt.END);
                cell.addView(caption, new LinearLayout.LayoutParams(dp(64), dp(30)));
                GridLayout.LayoutParams cellParams = new GridLayout.LayoutParams();
                cellParams.width = dp(66); cellParams.height = dp(82);
                cellParams.setMargins(dp(1), dp(1), dp(1), dp(1));
                grid.addView(cell, cellParams);
                if (i < 16) loadSlotPortrait(slot, portrait);
            }
            HorizontalScrollView horizontal = new HorizontalScrollView(this);
            horizontal.addView(grid);
            content.addView(horizontal);
        }
        for (int index = start; index < end; index++) {
            final int slotIndex = index;
            RosterArrangement.Slot slot = slots.get(index);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            int labelColor = pack != null && pack.capacity() > 0 && index >= pack.capacity() ? 0xffffb86b : Color.WHITE;
            TextView title = label((index + 1) + ". " + slot.title, 15, false);
            title.setTextColor(labelColor);
            row.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));
            if (editable) {
                Button up = button("↑", () -> moveSlot(slotIndex, -1, slotIndex / pageSize));
                Button down = button("↓", () -> moveSlot(slotIndex, 1, slotIndex / pageSize));
                up.setEnabled(index > 0); down.setEnabled(index + 1 < slots.size());
                up.setContentDescription("Move slot " + (index + 1) + " up");
                down.setContentDescription("Move slot " + (index + 1) + " down");
                row.addView(up, new LinearLayout.LayoutParams(dp(56), dp(48)));
                row.addView(down, new LinearLayout.LayoutParams(dp(56), dp(48)));
            }
            content.addView(row);
        }
        LinearLayout pages = new LinearLayout(this);
        pages.setOrientation(LinearLayout.HORIZONTAL);
        Button previous = button("Previous", () -> showArrangement(currentPage - 1));
        Button next = button("Next", () -> showArrangement(currentPage + 1));
        previous.setEnabled(currentPage > 0); next.setEnabled(currentPage + 1 < pageCount);
        pages.addView(previous, new LinearLayout.LayoutParams(0, dp(48), 1));
        pages.addView(next, new LinearLayout.LayoutParams(0, dp(48), 1));
        panel.addView(pages);
        panel.addView(button("Close", this::dismissSheet), new LinearLayout.LayoutParams(-1, dp(48)));
        presentSheet(panel, scroll);
    }

    private void loadSlotPortrait(RosterArrangement.Slot slot, ImageView image) {
        if (catalog == null || binding == null) return;
        LibraryScanner.Item match = null;
        for (LibraryScanner.Item item : catalog.characters) {
            if (item.warning == null && item.defNode != null
                    && (item.reference.equalsIgnoreCase(slot.title)
                    || item.reference.split("/", 2)[0].equalsIgnoreCase(slot.title))) { match = item; break; }
        }
        if (match == null) return;
        LibraryScanner.Item portraitItem = match;
        File current = library;
        LibraryBinding expected = binding;
        PREVIEW.execute(() -> {
            try {
                PreviewFrame frame = CharacterPreview.render(portraitItem.defNode, CharacterPreview.Mode.PORTRAIT, 48, 48);
                Bitmap bitmap = Bitmap.createBitmap(frame.argb, frame.width, frame.height, Bitmap.Config.ARGB_8888);
                runOnUiThread(() -> { if (sameBinding(current, expected) && image.isAttachedToWindow()) image.setImageBitmap(bitmap); });
            } catch (IOException ignored) { }
        });
    }

    private void moveSlot(int position, int direction, int page) {
        if (busy || library == null) return;
        File current = library;
        LibraryBinding source = binding;
        busy = true;
        IO.execute(() -> {
            try {
                requireReady(current, source, false);
                if (!ScreenpackStatus.inspect(openSource(source)).globalRoster)
                    throw new IOException("Active screenpack does not use data/select.def");
                RosterArrangement.moveWorking(current, position, direction, retention());
                LibraryScanner.Catalog scanned = scanWorking(current, source);
                runOnUiThread(() -> { if (sameBinding(current, source)) {
                    catalog = scanned; catalogEpoch++; busy = false;
                    if (selected != null) selected = find(scanned, selected);
                    renderList(); showArrangement(page);
                    showStatus("Working roster reordered. Review Export to apply it to the linked folder.");
                } });
            } catch (Exception error) { runOnUiThread(() -> { if (sameBinding(current, source)) {
                busy = false; showStatus("Roster move stopped: " + error.getMessage());
            } }); }
        });
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
        panel.setOnApplyWindowInsetsListener((view, insets) -> {
            int left, top, right, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                left = bars.left; top = bars.top; right = bars.right; bottom = bars.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft(); top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight(); bottom = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(dp(4) + left, dp(4) + top, dp(4) + right, dp(8) + bottom);
            return insets;
        });
        panel.setPadding(dp(4), dp(4), dp(4), dp(8));
        panel.setClickable(true);
        screenFrame.addView(panel, new FrameLayout.LayoutParams(-1, -1));
        panel.requestApplyInsets();
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
                + "\nAdded " + changes.added + " · Removed " + changes.removed
                + (changes.reorderedPositions > 0 ? "\nRoster order changed: " + changes.reorderedPositions + " positions" : "")
                + (changes.otherContentChanged ? "\nOther select.def content changed" : "");
    }

    private void reviewSourceExport() {
        if (busy || library == null) { showStatus("Select a library source first."); return; }
        File root = library;
        LibraryBinding current = binding;
        busy = true;
        showStatus("Checking exact source destination…");
        IO.execute(() -> {
            try {
                ScreenpackStatus activeScreenpack = ScreenpackStatus.inspect(openSource(current));
                if (activeScreenpack.knownAlternate)
                    throw new IOException("Active screenpack select target is " + activeScreenpack.select
                            + ". Linked export to data/select.def is disabled until the screenpack uses that file.");
                SelectStorage.External target = requireReady(root, current, true);
                SelectStorage storage = new SelectStorage(root, openSource(current));
                SelectStorage.Snapshot working = storage.readWorking();
                SelectStorage.ExportPlan plan = storage.planExport(target, working.sha256);
                byte[] source = target.read();
                if (!SelectStorage.hash(source).equals(plan.sourceHash)) throw new IOException("Destination changed during preview; review again");
                RosterChangeSummary changes = RosterChangeSummary.compare(openSource(current), source, plan.replacement);
                Integer keep = retention();
                runOnUiThread(() -> { if (!sameBinding(root, current)) return;
                    busy = false;
                    boolean changed = !plan.sourceHash.equals(plan.workingHash);
                    String message = (changed ? "Destination: linked " : "No changes · linked ")
                            + shortTargetName(plan.targetIdentity) + "\n" + changeCounts(changes)
                            + "\nVerified source preimage backup before overwrite: yes"
                            + "\nMissing references: " + plan.missingWarnings.size()
                            + "\n\nExact document: " + plan.targetIdentity
                            + "\nBackup folder: " + plan.backupDestination
                            + "\nVerified backups to keep: " + (keep == null ? "unlimited" : keep)
                            + "\nApp working undo and recovery snapshots stay private.";
                    Runnable review = () -> showDecisionSheet("Review source export", message,
                            changed ? "Back up and overwrite" : "Export anyway",
                            () -> executeSourceExport(root, current, plan, keep, !changed));
                    if (!changed && warnUnchangedExport())
                        showDecisionSheet("No changes to select.def",
                                "The working roster matches the linked source. You can still export after reviewing the exact destination and backup plan.",
                                "Review Export anyway", review);
                    else review.run();
                    showStatus(summary());
                });
            } catch (Exception error) { runOnUiThread(() -> { if (!isDestroyed()) {
                busy = false; showStatus("Export preview unavailable: " + error.getMessage());
            } }); }
        });
    }

    private void executeSourceExport(File root, LibraryBinding current, SelectStorage.ExportPlan plan,
                                     Integer keep, boolean forceNoChange) {
        if (busy) return;
        busy = true;
        IO.execute(() -> {
            try {
                ScreenpackStatus activeScreenpack = ScreenpackStatus.inspect(openSource(current));
                if (activeScreenpack.knownAlternate)
                    throw new IOException("Active screenpack select target changed to " + activeScreenpack.select
                            + ". Review export again after restoring data/select.def as the target.");
                SelectStorage.External target = requireReady(root, current, true);
                if (!java.util.Objects.equals(keep, retention()))
                    throw new IOException("Backup retention changed; review export again");
                SelectStorage.ExportResult result = forceNoChange
                        ? new SelectStorage(root).executeExportAnyway(target, plan, keep)
                        : new SelectStorage(root).executeExport(target, plan, keep);
                runOnUiThread(() -> { if (sameBinding(root, current)) {
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
        if (busy || library == null) { showStatus("Select a library source first."); return; }
        File root = library;
        LibraryBinding current = binding;
        busy = true;
        IO.execute(() -> {
            try {
                SelectStorage.External target = null;
                String warning = null;
                if (current != null && current.sourceTree != null) {
                    try {
                        current.requireCurrent(this);
                        BackupTargetRouter routed = new BackupTargetRouter(this, current,
                                BackupPolicy.load(this, root), true);
                        target = routed;
                        if (!routed.sourceAvailable()) warning = "Source unavailable; app backups remain available.";
                    }
                    catch (IOException unavailable) { warning = "Source backups unavailable. Reconnect source to view them."; }
                }
                List<SelectStorage.BackupRef> backups = new SelectStorage(root).listAllBackups(target);
                if (target instanceof BackupTargetRouter
                        && ((BackupTargetRouter) target).unavailableHistoryCount() > 0)
                    warning = (warning == null ? "" : warning + " ")
                            + "Some older backup folders are unavailable; reconnect them in Settings.";
                String unavailable = warning;
                runOnUiThread(() -> { if (!sameBinding(root, current)) return;
                    busy = false;
                    if (backups.isEmpty()) { showStatus(unavailable == null ? "No verified backups yet." : unavailable); return; }
                    String[] labels = new String[backups.size()];
                    for (int i = 0; i < backups.size(); i++) {
                        SelectStorage.BackupRef ref = backups.get(i);
                        labels[i] = (ref.origin == SelectStorage.BackupRef.Origin.SOURCE
                                ? ref.storeLabel : "App working undo/recovery")
                                + " · " + java.text.DateFormat.getDateTimeInstance().format(new Date(ref.createdAt))
                                + " · " + ref.byteCount + " bytes";
                    }
                    Runnable[] actions = new Runnable[backups.size()];
                    for (int i = 0; i < backups.size(); i++) {
                        SelectStorage.BackupRef ref = backups.get(i);
                        actions[i] = () -> chooseRestore(ref);
                    }
                    showActionSheet(unavailable == null ? "Verified backups · source preimages and working undo"
                            : "Available backups; reconnect source", labels, actions);
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
            SelectStorage.CommitResult result;
            try {
                SelectStorage.External target = requireReady(root, current, false);
                SelectStorage storage = new SelectStorage(root);
                result = storage.restoreLoadOnly(target, ref, storage.readWorking().sha256, retention());
            } catch (Exception error) { runOnUiThread(() -> { if (sameBinding(root, current)) {
                busy = false; showStatus("Local restore stopped: " + error.getMessage());
            } }); return; }
            try {
                LibraryScanner.Catalog scanned = scanWorking(root, current);
                runOnUiThread(() -> { if (sameBinding(root, current)) {
                    busy = false; catalog = scanned; catalogEpoch++; selected = selected == null ? null : find(scanned, selected);
                    renderList(); showStatus(result.changed ? "Backup loaded into the app's working roster. Source unchanged." : "Working roster already matches backup; source unchanged.");
                } });
            } catch (Exception refresh) { runOnUiThread(() -> { if (sameBinding(root, current)) {
                busy = false; catalog = null; selected = null; renderList();
                showStatus("Working roster restore completed. Source refresh unavailable: " + refresh.getMessage());
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
                SelectStorage.External target = requireReady(root, current, true);
                SelectStorage storage = new SelectStorage(root, openSource(current));
                String workingHash = storage.readWorking().sha256;
                SelectStorage.ExportPlan plan = storage.planRestoreSource(target, ref);
                byte[] source = target.read();
                if (!SelectStorage.hash(source).equals(plan.sourceHash)) throw new IOException("Destination changed during preview; review again");
                RosterChangeSummary changes = RosterChangeSummary.compare(openSource(current), source, plan.replacement);
                Integer keep = retention();
                runOnUiThread(() -> { if (!sameBinding(root, current)) return;
                    busy = false;
                    showDecisionSheet("Review source and local restore",
                            "Destination: linked " + shortTargetName(plan.targetIdentity)
                                    + "\n" + changeCounts(changes)
                                    + "\nBackup before overwrite: yes"
                                    + "\nApp working roster loads selected backup"
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
            SelectStorage.RestoreResult result;
            try {
                SelectStorage.External target = requireReady(root, current, true);
                result = new SelectStorage(root).restoreSourceAndWorking(
                        target, ref, workingHash, plan, keep);
            } catch (Exception error) { runOnUiThread(() -> { if (sameBinding(root, current)) {
                busy = false; showStatus("Restore stopped: " + error.getMessage());
                checkRecoveryAtStartup(root, current);
            } }); return; }
            try {
                LibraryScanner.Catalog scanned = scanWorking(root, current);
                runOnUiThread(() -> { if (sameBinding(root, current)) {
                    busy = false; catalog = scanned; catalogEpoch++; selected = selected == null ? null : find(scanned, selected);
                    renderList();
                    showDecisionSheet("Restore complete", "Source and private roster now contain the selected backup.\n"
                            + (result.source.changed ? "Source pre-write backup: " + result.source.backupLocation
                            : "Source was already identical."), null, null);
                } });
            } catch (Exception refresh) { runOnUiThread(() -> { if (sameBinding(root, current)) {
                busy = false; catalog = null; selected = null; renderList();
                showDecisionSheet("Restore complete", "Source and working roster were restored, but source browsing could not refresh: "
                        + refresh.getMessage(), null, null);
            } }); }
        });
    }

    private void showOrientationSetting() {
        boolean portrait = "portrait".equals(getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(KEY_ORIENTATION, "landscape"));
        showActionSheet("Screen orientation", new String[]{choiceLabel(!portrait, "Landscape"),
                        choiceLabel(portrait, "Portrait")},
                new Runnable[]{() -> chooseOrientation("landscape"), () -> chooseOrientation("portrait")});
    }

    private void chooseOrientation(String choice) {
        if (busy) return;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_ORIENTATION, choice).apply();
        applyOrientation();
    }

    private void showCompactMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("Roster actions").setOnMenuItemClickListener(item -> {
            anchor.post(this::showRosterActions); return true;
        });
        menu.getMenu().add(browserGrid() ? "Switch to list" : "Switch to grid").setOnMenuItemClickListener(item -> {
            anchor.post(this::toggleBrowserView); return true;
        });
        menu.getMenu().add("Filters").setOnMenuItemClickListener(item -> {
            anchor.post(this::showFilters); return true;
        });
        menu.show();
    }

    private static String choiceLabel(boolean selected, String value) {
        return (selected ? "✓ Current · " : "○ ") + value;
    }

    private String sourceFolderLabel() {
        if (binding == null || binding.sourceTree == null) return "Not selected";
        if (binding.displayName != null && !binding.displayName.trim().isEmpty()) return binding.displayName;
        try {
            String id = DocumentsContract.getTreeDocumentId(binding.sourceTree);
            int separator = Math.max(id.lastIndexOf('/'), id.lastIndexOf(':'));
            String name = id.substring(separator + 1);
            return name.isEmpty() || name.matches("[0-9a-fA-F-]{20,}") ? "Linked folder" : name;
        } catch (RuntimeException invalid) { return "Linked folder"; }
    }

    private String retentionLabel() {
        String value = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_RETENTION, "");
        return value.isEmpty() ? "Unlimited" : value;
    }

    private void updateExportAvailability() {
        if (exportButton == null) return;
        boolean writable = false;
        if (binding != null && binding.sourceTree != null) {
            try { SafSelectTarget.requireGrant(getContentResolver(), binding.sourceTree, true); writable = true; }
            catch (IOException ignored) { }
        }
        boolean ready = library != null && binding != null && binding.sourceTree != null
                && sourceSelectExists && writable && RosterStore.selectFile(library).isFile()
                && (screenpackStatus == null || !screenpackStatus.knownAlternate);
        exportButton.setEnabled(ready);
        String reason = library == null ? "Choose a source folder in Settings"
                : binding == null || binding.sourceTree == null ? "Reconnect source folder in Settings"
                : !sourceSelectExists ? "Linked source has no data/select.def to overwrite"
                : screenpackStatus != null && screenpackStatus.knownAlternate ? "Active screenpack select target is " + screenpackStatus.select
                : !writable ? "Reconnect source with write access in Settings"
                : "Create a working roster before exporting";
        exportButton.setContentDescription(ready ? "Export select.def to the linked source folder"
                : "Export unavailable. " + reason + ".");
        if (exportHint != null) {
            exportHint.setVisibility(ready ? View.GONE : View.VISIBLE);
            exportHint.setText("Export unavailable · " + reason + ".");
        }
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

    private SelectStorage.External requireReady(File root, LibraryBinding current, boolean needSource) throws IOException {
        SelectStorage.External target = null;
        if (current != null && current.sourceTree != null) {
            current.requireCurrent(this);
            if (needSource) SafSelectTarget.requireGrant(getContentResolver(), current.sourceTree, true);
            try { target = new BackupTargetRouter(this, current, BackupPolicy.load(this, root), !needSource); }
            catch (IOException inaccessible) { if (needSource || hasPending(root)) throw new IOException(
                    "Source unavailable. Reconnect its folder; your working roster and backups are retained. " + inaccessible.getMessage(), inaccessible); }
        }
        if (needSource && target == null) throw new IOException("No writable select.def linked. Reconnect source or choose a source with data/select.def.");
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
            runOnUiThread(() -> { if (sameBinding(root, current)) {
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
                SelectStorage.External target = new BackupTargetRouter(this, current, BackupPolicy.load(this, root), true);
                String destination = target.identity();
                SelectStorage storage = new SelectStorage(root);
                String export = storage.inspectPending(target);
                String restore = export.equals("RECOVERY_REQUIRED") || export.equals("OTHER_TARGET")
                        ? "DEFERRED" : storage.inspectPendingRestore(target);
                runOnUiThread(() -> { if (!sameBinding(root, current)) return;
                    busy = false;
                    if (export.equals("RECOVERY_REQUIRED")) {
                        showDecisionSheet("Repair interrupted export",
                                "Destination: " + destination + "\nRestore its exact pre-write bytes from the private recovery copy? This overwrites the current destination.",
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
                                "The source and private roster no longer match this pending restore. Preserve and verify both current versions as separate backups, then stop this restore? No roster will be overwritten. Destination: " + destination,
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
                SelectStorage.External target = new BackupTargetRouter(this, current, BackupPolicy.load(this, root), true);
                SelectStorage storage = new SelectStorage(root);
                if (finishLocal) storage.completePendingRestore(target);
                else storage.restorePendingPreimage(target);
            } catch (Exception error) { runOnUiThread(() -> { if (sameBinding(root, current)) {
                busy = false; showStatus("Recovery failed: " + error.getMessage());
            } }); return; }
            try {
                LibraryScanner.Catalog scanned = scanWorking(root, current);
                runOnUiThread(() -> { if (sameBinding(root, current)) {
                    busy = false; recoveryIssue = null; catalog = scanned; catalogEpoch++;
                    selected = selected == null ? null : find(scanned, selected);
                    renderList(); showStatus("Recovery completed and roster refreshed.");
                } });
            } catch (Exception refresh) { runOnUiThread(() -> { if (sameBinding(root, current)) {
                busy = false; recoveryIssue = null; catalog = null; selected = null; renderList();
                showStatus("Recovery completed. Source refresh unavailable: " + refresh.getMessage());
            } }); }
        });
    }

    private void abandonRecovery(File root, LibraryBinding current) {
        if (busy) return;
        busy = true;
        IO.execute(() -> {
            try {
                current.requireCurrent(this);
                SelectStorage.External target = new BackupTargetRouter(this, current, BackupPolicy.load(this, root));
                SelectStorage.AbandonResult result = new SelectStorage(root).abandonPendingRestore(target);
                runOnUiThread(() -> { if (sameBinding(root, current)) {
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
            updateExportAvailability();
            return;
        }
        library = active;
        catalog = null;
        sourceSelectExists = false;
        screenpackStatus = null;
        selected = null;
        restoreSelection = null;
        previewKey = null;
        previewBitmap = null;
        previewReason = null;
        previewSource = null;
        previewNotice = null;
        previewLoading = false;
        renderList();
        showStatus(summary());
        updateExportAvailability();
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
        if (busy || library == null) { showStatus("Select a library before reconnecting its source folder."); return; }
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
            showStatus("Select a library with data/select.def or enable an item first.");
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
            if (request == PICK_ADDON_ZIP || request == PICK_ADDON_FOLDER) {
                importKind = null; importLibrary = null; importBinding = null;
            }
            if (request == RECONNECT_TREE) reconnectLibrary = null;
            if (request == PICK_BACKUP_TREE) {
                backupPickerLibrary = null; backupPickerBinding = null;
                backupPickerRecovery = false; backupPickerRequiredTree = null;
            }
            return;
        }
        Uri uri = data.getData();
        if (request == PICK_ADDON_ZIP || request == PICK_ADDON_FOLDER) {
            stagePickedAddon(uri, data, request == PICK_ADDON_ZIP);
            return;
        }
        if (request == PICK_BACKUP_TREE) {
            File expected = backupPickerLibrary;
            LibraryBinding expectedBinding = backupPickerBinding;
            boolean recoveryReconnect = backupPickerRecovery;
            Uri requiredTree = backupPickerRequiredTree;
            backupPickerLibrary = null; backupPickerBinding = null;
            backupPickerRecovery = false; backupPickerRequiredTree = null;
            if (expected == null || !sameBinding(expected, expectedBinding)) return;
            if (recoveryReconnect && (requiredTree == null || !requiredTree.equals(uri))) {
                showStatus("Choose the exact custom backup folder named in Recovery settings; backup location unchanged.");
                return;
            }
            int flags = persistGrant(uri, data);
            busy = true;
            IO.execute(() -> {
                try {
                    int required = recoveryReconnect ? Intent.FLAG_GRANT_READ_URI_PERMISSION
                            : Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                    if ((flags & required) != required)
                        throw new IOException(recoveryReconnect ? "Recovery backup folder needs lasting read access"
                                : "Custom backup folder needs lasting read and write access");
                    Uri folder = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri));
                    String name;
                    try (Cursor cursor = getContentResolver().query(folder,
                            new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                                    DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
                        if (cursor == null || !cursor.moveToFirst()
                                || !DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(1)))
                            throw new IOException("Custom backup selection is not an accessible folder");
                        name = cursor.getString(0);
                    }
                    if (name == null || name.trim().isEmpty()) throw new IOException("Backup folder has no display name");
                    if (recoveryReconnect) {
                        BackupPolicy policy = BackupPolicy.load(this, expected);
                        if (requiredTree == null || !requiredTree.equals(uri)
                                || !requiredTree.equals(requiredRecoveryCustomTree(policy)))
                            throw new IOException("Choose the exact custom backup folder named in Recovery settings");
                        String requiredStore = new SelectStorage(expected).pendingRestoreBackupStoreIdentity();
                        if (requiredStore != null && requiredStore.startsWith("custom:")) {
                            BackupTargetRouter target = new BackupTargetRouter(this, expectedBinding, policy, true);
                            new SelectStorage(expected).verifyPendingRestoreBackup(target);
                        }
                        runOnUiThread(() -> { if (sameBinding(expected, expectedBinding)) {
                            busy = false; showStatus("Required custom backup folder is readable. Open Roster actions > Recovery. Source preservation may also need write access.");
                        } });
                        return;
                    }
                    String probeName = "ikemen-write-check-" + UUID.randomUUID() + ".tmp";
                    Uri probe = DocumentsContract.createDocument(getContentResolver(), folder,
                            "application/octet-stream", probeName);
                    if (probe == null) throw new IOException("Backup provider could not create a file");
                    try {
                        try (Cursor cursor = getContentResolver().query(probe,
                                new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
                            if (cursor == null || !cursor.moveToFirst()) throw new IOException("Backup test file is unavailable");
                            ManagedBackupFormat.requireExactName(probeName, cursor.getString(0));
                        }
                    } finally {
                        if (!DocumentsContract.deleteDocument(getContentResolver(), probe))
                            throw new IOException("Backup provider could not remove its test file; prior setting retained");
                    }
                    if (!sameBinding(expected, expectedBinding)) throw new IOException("Source folder changed during backup selection");
                    if (hasPending(expected)) throw new IOException("Resolve pending source recovery before changing the backup location");
                    BackupPolicy chosen = BackupPolicy.choose(this, expected, BackupPolicy.Kind.CUSTOM, uri, name);
                    runOnUiThread(() -> { if (sameBinding(expected, expectedBinding)) {
                        busy = false; showStatus("Verified source preimage backups: custom folder " + chosen.customName + ".");
                    } });
                } catch (Exception error) { runOnUiThread(() -> { if (!isDestroyed()) {
                    busy = false; showStatus("Custom backup setting unchanged: " + error.getMessage());
                } }); }
            });
        } else if (request == PICK_TREE) {
            if (library != null && binding != null && uri.equals(binding.sourceTree)) {
                reconnectLibrary = library;
                reconnectPickedSource(uri, data);
                return;
            }
            int flags = persistGrant(uri, data);
            busy = true;
            showStatus("Linking source folder…");
            IO.execute(() -> {
                try {
                    if ((flags & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0)
                        throw new IOException("Choose a folder that grants lasting read access");
                    LibraryFiles.Node source = new SafLibraryFiles(getContentResolver(), uri).root();
                    LibraryFiles.Node sourceData = LibraryFiles.child(source, "data");
                    LibraryFiles.Node sourceSelect = sourceData == null ? null : LibraryFiles.child(sourceData, "select.def");
                    byte[] roster = sourceSelect == null ? new byte[0]
                            : LibraryFiles.readLimited(sourceSelect, SelectStorage.MAX_BYTES);
                    LibraryScanner.Catalog scanned = LibraryScanner.scan(source, roster);
                    ScreenpackStatus screenpack = ScreenpackStatus.inspect(source);
                    File workspace = new File(new File(getFilesDir(), "libraries"), UUID.randomUUID().toString());
                    File workData = new File(workspace, "data");
                    if (!workData.mkdirs()) throw new IOException("Could not create working roster directory");
                    try (FileOutputStream output = new FileOutputStream(new File(workData, "select.def"))) {
                        output.write(roster); output.getFD().sync();
                    }
                    LibraryBinding linked = LibraryBinding.bindSource(this, workspace, uri, flags, source.name());
                    if (!getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_PATH, workspace.getAbsolutePath()).commit())
                        throw new IOException("Could not remember selected source folder");
                    runOnUiThread(() -> { if (isDestroyed()) return;
                        library = workspace; binding = linked; catalog = scanned; catalogEpoch++; selected = null; busy = false;
                        sourceSelectExists = sourceSelect != null;
                        screenpackStatus = screenpack;
                        syncActiveLibrary(workspace.getAbsolutePath()); renderList();
                        updateExportAvailability();
                        showStatus((flags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0
                                ? "Source linked directly. Character and stage files stay in place."
                                : "Source linked for browsing. Export needs write permission.");
                    });
                } catch (Exception error) { runOnUiThread(() -> { if (!isDestroyed()) { busy = false; showStatus("Could not link source folder: " + error.getMessage()); } }); }
            });
        } else if (request == RECONNECT_TREE) {
            reconnectPickedSource(uri, data);
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

    private void reconnectPickedSource(Uri uri, Intent data) {
        File expected = reconnectLibrary;
        reconnectLibrary = null;
        if (expected == null || !expected.equals(library)) return;
        try {
            LibraryBinding remembered = LibraryBinding.load(this, expected);
            if (!LibraryBinding.acceptsReconnect(
                    remembered.sourceTree == null ? null : remembered.sourceTree.toString(), uri.toString())) {
                showStatus("This is a different source folder. Use Settings > Source folder to switch libraries.");
                return;
            }
        } catch (IOException error) { showStatus("Could not check the remembered source: " + error.getMessage()); return; }
        int flags = persistGrant(uri, data);
        busy = true;
        IO.execute(() -> {
            try {
                if ((flags & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0)
                    throw new IOException("Choose a folder that grants lasting read access");
                LibraryFiles.Node source = new SafLibraryFiles(getContentResolver(), uri).root();
                LibraryScanner.Catalog scanned = LibraryScanner.scan(source,
                        new SelectStorage(expected).readWorking().bytes);
                ScreenpackStatus screenpack = ScreenpackStatus.inspect(source);
                LibraryFiles.Node sourceData = LibraryFiles.child(source, "data");
                boolean hasSourceSelect = sourceData != null && LibraryFiles.child(sourceData, "select.def") != null;
                LibraryBinding restored = LibraryBinding.bindSource(this, expected, uri, flags, source.name());
                runOnUiThread(() -> { if (!isDestroyed() && expected.equals(library)) {
                    binding = restored; catalog = scanned; catalogEpoch++; selected = selected == null ? null : find(scanned, selected);
                    sourceSelectExists = hasSourceSelect;
                    screenpackStatus = screenpack;
                    busy = false; renderList(); updateExportAvailability(); showStatus("Source folder reconnected directly.");
                    checkRecoveryAtStartup(expected, restored);
                } });
            } catch (Exception error) { runOnUiThread(() -> { if (!isDestroyed()) {
                busy = false; showStatus("Reconnect failed; working roster preserved: " + error.getMessage());
            } }); }
        });
    }

    private String summary() {
        if (library == null || catalog == null) return "Choose an IKEMEN folder with chars and stages.";
        String legacy = new File(library, "chars").isDirectory() || new File(library, "stages").isDirectory()
                ? " · earlier copied data retained in app storage" : "";
        return catalog.characters.size() + " characters · " + catalog.stages.size() + " stages · "
                + (binding != null && binding.sourceTree != null ? "source linked directly" : "reconnect source folder")
                + legacy;
    }

    private LibraryFiles.Node openSource(LibraryBinding current) throws IOException {
        if (current == null || current.sourceTree == null)
            throw new IOException("Source folder is not linked. Reconnect it; your working roster and backups are retained.");
        current.requireCurrent(this);
        return new SafLibraryFiles(getContentResolver(), current.sourceTree).root();
    }

    private boolean sameBinding(File root, LibraryBinding expected) {
        if (isDestroyed() || root == null || !root.equals(library)) return false;
        if (expected == null || binding == null) return expected == binding;
        return binding.generation == expected.generation
                && java.util.Objects.equals(binding.sourceTree, expected.sourceTree);
    }

    private LibraryScanner.Catalog scanWorking(File root, LibraryBinding current) throws IOException {
        LibraryFiles.Node source = openSource(current);
        return LibraryScanner.scan(source, new SelectStorage(root).readWorking().bytes);
    }

    private void refreshCatalog() {
        if (library == null) return;
        File current = library;
        LibraryBinding currentBinding = binding;
        IO.execute(() -> {
            try {
                LibraryFiles.Node source = openSource(currentBinding);
                LibraryFiles.Node sourceData = LibraryFiles.child(source, "data");
                boolean hasSourceSelect = sourceData != null && LibraryFiles.child(sourceData, "select.def") != null;
                LibraryScanner.Catalog scanned = LibraryScanner.scan(source, new SelectStorage(current).readWorking().bytes);
                ScreenpackStatus screenpack = ScreenpackStatus.inspect(source);
                runOnUiThread(() -> { if (sameBinding(current, currentBinding)) {
                    catalog = scanned; catalogEpoch++;
                    sourceSelectExists = hasSourceSelect;
                    screenpackStatus = screenpack;
                    previewKey = null; previewBitmap = null; previewReason = null;
                    if (restoreSelection != null) {
                        selected = findByKey(scanned, restoreSelection);
                        restoreSelection = null;
                    } else if (selected != null) selected = find(scanned, selected);
                    renderList(); updateExportAvailability(); showStatus(summary());
                } });
            } catch (Exception error) { runOnUiThread(() -> { if (sameBinding(current, currentBinding)) {
                catalog = null; selected = null; sourceSelectExists = false; screenpackStatus = null; renderList(); updateExportAvailability();
                showStatus("Source scan unavailable: " + error.getMessage());
            } }); }
        });
    }

    private void renderList() {
        if (browserAdapter == null) return;
        if (catalog == null) {
            browserAdapter.replace(new ArrayList<>(), library == null
                    ? "Select a source folder to browse your library."
                    : "Source unavailable. Reconnect the selected folder or refresh the library.");
            renderDetail(); return;
        }
        String query = searchText.trim().toLowerCase(Locale.ROOT);
        List<LibraryScanner.Item> items = new ArrayList<>();
        TagStore manual = null;
        try { manual = tags(); }
        catch (IOException unavailable) { showStatus("Manual tags unavailable: " + unavailable.getMessage()); }
        for (LibraryScanner.Item item : catalog.characters)
            if (matchesBrowserItem(item, query, manual)) items.add(item);
        for (LibraryScanner.Item item : catalog.stages)
            if (matchesBrowserItem(item, query, manual)) items.add(item);
        browserAdapter.replace(items, items.isEmpty() ? "No matching content." : null);
        renderDetail();
    }

    private boolean matchesBrowserItem(LibraryScanner.Item item, String query, TagStore manual) {
        if (!query.isEmpty() && !(item.name + " " + item.author + " " + item.reference)
                .toLowerCase(Locale.ROOT).contains(query)) return false;
        boolean character = item.kind.equals("characters");
        if (typeFilter.equals("Characters") && !character || typeFilter.equals("Stages") && character) return false;
        if (statusFilter.equals("Enabled") && !Boolean.TRUE.equals(item.enabled)
                || statusFilter.equals("Disabled") && !Boolean.FALSE.equals(item.enabled)
                || statusFilter.equals("Unlisted") && item.enabled != null
                || statusFilter.equals("Missing") && item.warning == null) return false;
        if (tagFilter != null) {
            List<String> tags = inferredTagFilter ? inferredTags(item)
                    : manual == null ? java.util.Collections.emptyList() : manual.get(selectionKey(item));
            boolean matched = false;
            for (String tag : tags) if (tag.equalsIgnoreCase(tagFilter)) { matched = true; break; }
            if (!matched) return false;
        }
        return true;
    }

    private final class BrowserAdapter extends BaseAdapter {
        private final List<LibraryScanner.Item> items = new ArrayList<>();
        private String message;

        boolean isMessage() { return message != null; }

        int positionOf(String key) {
            for (int i = 0; i < items.size(); i++) if (selectionKey(items.get(i)).equals(key)) return i;
            return -1;
        }

        void replace(List<LibraryScanner.Item> next, String emptyMessage) {
            items.clear(); items.addAll(next);
            message = emptyMessage;
            configureBrowserLayout();
            notifyDataSetChanged();
        }

        @Override public int getCount() { return isMessage() ? 1 : items.size(); }
        @Override public Object getItem(int position) { return isMessage() ? message : items.get(position); }
        @Override public long getItemId(int position) {
            if (isMessage()) return Long.MIN_VALUE;
            String key = selectionKey(items.get(position));
            long hash = 0xcbf29ce484222325L;
            for (int i = 0; i < key.length(); i++) hash = (hash ^ key.charAt(i)) * 0x100000001b3L;
            return hash;
        }
        @Override public boolean hasStableIds() { return true; }
        @Override public int getViewTypeCount() { return 3; }
        @Override public int getItemViewType(int position) { return isMessage() ? 2 : browserGrid() ? 1 : 0; }

        @Override public View getView(int position, View reusable, ViewGroup parent) {
            if (isMessage()) {
                TextView text = reusable instanceof TextView ? (TextView) reusable : label("", 16, false);
                text.setText(message);
                text.setLayoutParams(new android.widget.AbsListView.LayoutParams(-1, dp(90)));
                return text;
            }
            boolean grid = browserGrid();
            BrowserCard card = reusable instanceof BrowserCard && ((BrowserCard) reusable).grid == grid
                    ? (BrowserCard) reusable : new BrowserCard(grid);
            LibraryScanner.Item item = items.get(position);
            String key = selectionKey(item);
            String state = item.enabled == null ? "Unlisted" : item.enabled ? "Enabled" : "Disabled";
            card.title.setText(item.name + "\n" + (item.kind.equals("characters") ? "Character" : "Stage")
                    + " · " + state + (item.warning == null ? "" : " · MISSING"));
            card.title.setTextColor(item.warning == null ? Color.WHITE : 0xffff6b6b);
            card.setTag(key);
            card.setContentDescription(item.name + ", " + state
                    + (item.warning == null ? "" : ", missing reference"));
            focusStyle(card, selected != null && key.equals(selectionKey(selected)));
            String thumbnailKey = thumbnailKey(item);
            card.boundThumbnailKey = thumbnailKey;
            Bitmap cached = thumbnailCache.get(thumbnailKey);
            card.thumbnail.setImageBitmap(cached);
            card.thumbnail.setContentDescription(cached == null ? "Artwork unavailable or loading" : item.name + " thumbnail");
            if (cached == null && item.warning == null && item.defNode != null
                    && thumbnailUnavailable.get(thumbnailKey) == null) loadThumbnail(item, thumbnailKey);
            return card;
        }
    }

    private final class BrowserCard extends LinearLayout {
        final boolean grid;
        final ImageView thumbnail;
        final TextView title;
        String boundThumbnailKey;
        BrowserCard(boolean grid) {
            super(MainActivity.this);
            this.grid = grid;
            setOrientation(grid ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
            setGravity(android.view.Gravity.CENTER_VERTICAL);
            setPadding(dp(5), dp(5), dp(5), dp(5));
            setLayoutParams(new android.widget.AbsListView.LayoutParams(-1, dp(grid ? 148 : 78)));
            thumbnail = new ImageView(MainActivity.this);
            thumbnail.setScaleType(ImageView.ScaleType.FIT_CENTER);
            thumbnail.setBackgroundColor(0xff172530);
            addView(thumbnail, new LinearLayout.LayoutParams(dp(grid ? 92 : 66), dp(grid ? 88 : 66)));
            title = label("", grid ? 13 : 16, false);
            title.setMaxLines(grid ? 2 : 3);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            addView(title, grid ? new LinearLayout.LayoutParams(-1, 0, 1)
                    : new LinearLayout.LayoutParams(0, -2, 1));
        }
    }

    private void activateBrowserItem(int position) {
        if (browserAdapter == null || browserAdapter.isMessage() || position < 0 || position >= browserAdapter.items.size()) return;
        LibraryScanner.Item item = browserAdapter.items.get(position);
        String key = selectionKey(item);
        RowActivation.Action action = RowActivation.decide(selected == null ? null : selectionKey(selected), key, busy);
        if (action == RowActivation.Action.IGNORE) return;
        if (action == RowActivation.Action.TOGGLE) { toggleSelected(item.enabled == null || !item.enabled); return; }
        selected = item;
        browserAdapter.notifyDataSetChanged();
        renderDetail();
        list.setSelection(position);
    }

    private String thumbnailKey(LibraryScanner.Item item) {
        return (library == null ? "" : library.getAbsolutePath()) + "|" + catalogEpoch + "|"
                + selectionKey(item) + "|" + (item.kind.equals("characters") ? "portrait" : "stage");
    }

    private void loadThumbnail(LibraryScanner.Item item, String key) {
        if (!thumbnailsLoading.add(key)) return;
        try {
            THUMBNAILS.execute(() -> {
                Bitmap bitmap = null;
                try {
                    PreviewFrame frame = item.kind.equals("characters")
                            ? CharacterPreview.render(item.defNode, CharacterPreview.Mode.PORTRAIT, 96, 96)
                            : StagePreview.render(item.defNode, 96, 96);
                    bitmap = Bitmap.createBitmap(frame.argb, frame.width, frame.height, Bitmap.Config.ARGB_8888);
                } catch (IOException | RuntimeException ignored) { }
                Bitmap result = bitmap;
                runOnUiThread(() -> {
                    thumbnailsLoading.remove(key);
                    if (isDestroyed()) return;
                    if (result == null) thumbnailUnavailable.put(key, true);
                    else thumbnailCache.put(key, result);
                    if (list == null) return;
                    for (int i = 0; i < list.getChildCount(); i++) {
                        View child = list.getChildAt(i);
                        if (!(child instanceof BrowserCard)) continue;
                        BrowserCard visible = (BrowserCard) child;
                        if (!key.equals(visible.boundThumbnailKey)) continue;
                        visible.thumbnail.setImageBitmap(result);
                        visible.thumbnail.setContentDescription(result == null ? "Artwork unavailable" : item.name + " thumbnail");
                    }
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException full) {
            thumbnailsLoading.remove(key);
            scheduleThumbnailRetry();
        }
    }

    private void scheduleThumbnailRetry() {
        if (thumbnailRetryScheduled || list == null) return;
        thumbnailRetryScheduled = true;
        list.postDelayed(() -> {
            thumbnailRetryScheduled = false;
            if (!isDestroyed() && browserAdapter != null) browserAdapter.notifyDataSetChanged();
        }, 350);
    }

    private void renderDetail() {
        if (detail == null) return;
        detail.removeAllViews();
        detail.addView(label("Details", 20, true));
        if (selected == null) { rosterButton = null; detail.addView(label("Select a character or stage. Use touch, D-pad, or left stick; A selects and B goes back.", 15, false)); return; }
        detail.addView(label(selected.name, 21, true));
        detail.addView(label("Author: " + selected.author, 16, false));
        detail.addView(label("Reference: " + selected.reference, 14, false));
        CharacterPreview.Mode mode = previewMode();
        detail.addView(label(selected.kind.equals("characters")
                ? "Character · " + PreviewChoice.label(mode) : "Stage scene", 16, true));
        String key = PreviewChoice.key(library, selected, mode) + '\u0000' + catalogEpoch;
        if (!key.equals(previewKey)) {
            previewKey = key; previewBitmap = null; previewReason = null;
            previewSource = null; previewNotice = null; previewLoading = false;
        }
        if (previewBitmap != null) {
            ImageView image = new ImageView(this);
            image.setImageBitmap(previewBitmap);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setContentDescription(selected.name + " artwork preview");
            detail.addView(image, new LinearLayout.LayoutParams(-1, dp(210)));
            if (previewSource != null) detail.addView(label("Source: " + previewSource, 13, false));
            if (previewNotice != null) detail.addView(label(previewNotice, 13, false));
        } else {
            detail.addView(label(previewReason == null ? "Loading artwork preview…" : "Preview unavailable: " + previewReason, 14, false));
            if (!previewLoading && previewReason == null) loadPreview(selected, key, mode);
        }
        if (selected.warning != null) {
            TextView warning = label("MISSING: " + selected.warning + ". Enable is blocked until the referenced file is restored.", 16, true);
            warning.setTextColor(0xffff6b6b);
            detail.addView(warning);
        }
        detail.addView(label("DEF: " + selected.file, 13, false));
        detail.addView(label("Roster: " + (selected.enabled == null ? "Not listed" : selected.enabled ? "Enabled" : "Disabled"), 16, false));
        detail.addView(label("Inferred cues: " + (inferredTags(selected).isEmpty()
                ? "None" : String.join(", ", inferredTags(selected))), 14, false));
        try {
            List<String> manual = tags().get(selectionKey(selected));
            detail.addView(label("Manual tags: " + (manual.isEmpty() ? "None" : String.join(", ", manual)), 14, false));
        } catch (IOException unavailable) {
            detail.addView(label("Manual tags unavailable: " + unavailable.getMessage(), 14, false));
        }
        detail.addView(button("DEF facts & input definitions", this::showMetadataDetails));
        detail.addView(button("Edit manual tags", this::showManualTags));
        rosterButton = button(selected.warning != null && !Boolean.TRUE.equals(selected.enabled) ? "Cannot enable missing file"
                : selected.enabled != null && selected.enabled ? "Disable in roster" : "Enable in roster",
                () -> toggleSelected(selected.enabled == null || !selected.enabled));
        if (selected.warning != null && !Boolean.TRUE.equals(selected.enabled)) rosterButton.setEnabled(false);
        detail.addView(rosterButton);
    }

    private void showMetadataDetails() {
        if (selected == null) return;
        LibraryScanner.Item item = selected;
        String itemKey = selectionKey(item);
        long epoch = catalogEpoch;
        showDecisionSheet("DEF facts & input definitions", "Reading source DEF and CMD…", "", null);
        LinearLayout loading = activeSheet;
        DETAILS.execute(() -> {
            MetadataDetails result = null;
            String error = null;
            try { result = MetadataDetails.read(item); }
            catch (IOException unavailable) { error = unavailable.getMessage(); }
            MetadataDetails facts = result;
            String issue = error;
            runOnUiThread(() -> {
                if (isDestroyed() || activeSheet != loading || catalogEpoch != epoch
                        || selected == null || !itemKey.equals(selectionKey(selected))) return;
                if (facts == null) showDecisionSheet("DEF facts unavailable", issue, "", null);
                else showMetadataSheet(item.name, facts);
            });
        });
    }

    private void showMetadataSheet(String name, MetadataDetails facts) {
        LinearLayout panel = column();
        panel.addView(sheetTitle(name + " · source facts"), new LinearLayout.LayoutParams(-1, dp(compactLayout ? 26 : 48)));
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        panel.addView(tabs, new LinearLayout.LayoutParams(-1, dp(48)));
        ScrollView scroll = new ScrollView(this);
        TextView content = label("", compactLayout ? 14 : 15, false);
        content.setTextIsSelectable(true);
        scroll.addView(content);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        Runnable showFacts = () -> {
            content.setText(facts.fields.isEmpty() ? "No supported DEF facts found." : String.join("\n", facts.fields));
            scroll.scrollTo(0, 0);
        };
        Runnable showInputs = () -> {
            String listing = facts.commands.isEmpty() ? facts.commandNotice
                    : facts.commandNotice + "\n\n" + String.join("\n", facts.commands)
                    + (facts.commands.size() == 200 ? "\n…first 200 definitions shown" : "");
            content.setText(listing);
            scroll.scrollTo(0, 0);
        };
        tabs.addView(button("DEF facts", showFacts), new LinearLayout.LayoutParams(0, -1, 1));
        tabs.addView(button("Input definitions", showInputs), new LinearLayout.LayoutParams(0, -1, 1));
        panel.addView(button("Close", this::dismissSheet), new LinearLayout.LayoutParams(-1, dp(48)));
        showFacts.run();
        presentSheet(panel, scroll);
    }

    private void showManualTags() {
        if (selected == null) return;
        LibraryScanner.Item item = selected;
        try {
            List<String> current = tags().get(selectionKey(item));
            List<String> labels = new ArrayList<>();
            List<Runnable> actions = new ArrayList<>();
            labels.add("Add a manual tag");
            actions.add(() -> showTagInput(item));
            for (String tag : current) {
                labels.add("Remove · " + tag);
                actions.add(() -> changeTag(item, tag));
            }
            showActionSheet("Manual tags · private to this source", labels.toArray(new String[0]),
                    actions.toArray(new Runnable[0]));
        } catch (IOException unavailable) { showDecisionSheet("Manual tags unavailable", unavailable.getMessage(), "", null); }
    }

    private void showTagInput(LibraryScanner.Item item) {
        LinearLayout panel = column();
        panel.addView(sheetTitle("Add manual tag"), new LinearLayout.LayoutParams(-1, dp(compactLayout ? 26 : 48)));
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = column();
        content.addView(label("1–24 characters. Stored privately for this source; game files are unchanged.", 15, false));
        EditText entry = new EditText(this);
        entry.setSingleLine(true);
        entry.setTextColor(Color.WHITE);
        entry.setHint("Tag name");
        content.addView(entry, new LinearLayout.LayoutParams(-1, dp(55)));
        scroll.addView(content);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        panel.addView(button("Save tag", () -> changeTag(item, entry.getText().toString())),
                new LinearLayout.LayoutParams(-1, dp(48)));
        panel.addView(button("Close", this::dismissSheet), new LinearLayout.LayoutParams(-1, dp(48)));
        presentSheet(panel, scroll);
        entry.requestFocus();
    }

    private void changeTag(LibraryScanner.Item item, String tag) {
        try {
            tags().toggle(selectionKey(item), tag);
            dismissSheet();
            renderList();
            showStatus("Manual tags saved privately for this source.");
        } catch (IOException unavailable) { showStatus("Tag not saved: " + unavailable.getMessage()); }
    }

    private static final class DisplayPreview {
        final Bitmap bitmap;
        final String source, notice;
        DisplayPreview(Bitmap bitmap, String source, String notice) {
            this.bitmap = bitmap; this.source = source; this.notice = notice;
        }
    }

    private static DisplayPreview fromFrame(PreviewFrame frame, String fallbackNotice) {
        String notice = frame.notice;
        if (fallbackNotice != null) notice = notice == null ? fallbackNotice : fallbackNotice + ". " + notice;
        return new DisplayPreview(Bitmap.createBitmap(frame.argb, frame.width, frame.height, Bitmap.Config.ARGB_8888),
                frame.source, notice);
    }

    private static DisplayPreview legacyArtwork(LibraryScanner.Item item, String notice) throws IOException {
        LibraryFiles.Node file = item.previewNode;
        if (file == null) throw new IOException("No supported artwork file found");
        Bitmap image;
        String source;
        if (file.name().toLowerCase(Locale.ROOT).endsWith(".png")) {
            image = decodeBoundedPng(LibraryFiles.readLimited(file, 64 * 1024 * 1024));
            source = "PNG thumbnail";
        } else {
            try (PreviewSff archive = new PreviewSff(file)) {
                int group = item.previewGroup, index = item.previewImage;
                if (!archive.has(group, index) && archive.has(9000, 1)) { group = 9000; index = 1; }
                PreviewSff.Sprite sprite = archive.sprite(group, index);
                image = Bitmap.createBitmap(sprite.argb, sprite.width, sprite.height, Bitmap.Config.ARGB_8888);
            }
            source = "SFF sprite thumbnail";
        }
        if (image == null) throw new IOException("Thumbnail is malformed or exceeds preview limit");
        return new DisplayPreview(image, source, notice);
    }

    private static DisplayPreview renderArtwork(LibraryScanner.Item item, CharacterPreview.Mode mode) throws IOException {
        LibraryFiles.Node def = item.defNode;
        if (def == null || def.directory()) throw new IOException("Referenced DEF is missing");
        if (!item.kind.equals("characters")) {
            try { return fromFrame(StagePreview.render(def, 400, 240), null); }
            catch (IOException sceneUnavailable) {
                return legacyArtwork(item, "Full stage scene unavailable; showing a thumbnail");
            }
        }
        try { return fromFrame(CharacterPreview.render(def, mode, 400, 240), null); }
        catch (IOException requestedUnavailable) {
            CharacterPreview.Mode alternative = mode == CharacterPreview.Mode.PORTRAIT
                    ? CharacterPreview.Mode.NEUTRAL : CharacterPreview.Mode.PORTRAIT;
            try {
                return fromFrame(CharacterPreview.render(def, alternative, 400, 240),
                        PreviewChoice.label(mode) + " unavailable; showing " + PreviewChoice.label(alternative));
            } catch (IOException alternativeUnavailable) {
                return legacyArtwork(item, PreviewChoice.label(mode) + " unavailable; showing a thumbnail");
            }
        }
    }

    private void loadPreview(LibraryScanner.Item item, String key, CharacterPreview.Mode mode) {
        previewLoading = true;
        PREVIEW.execute(() -> {
            if (!key.equals(previewKey)) return;
            DisplayPreview rendered = null;
            String reason = null;
            try { rendered = renderArtwork(item, mode); }
            catch (IOException error) { reason = error.getMessage(); }
            catch (RuntimeException error) { reason = "Could not decode artwork"; }
            DisplayPreview result = rendered;
            String message = reason;
            runOnUiThread(() -> {
                if (isDestroyed() || !key.equals(previewKey)) return;
                previewBitmap = result == null ? null : result.bitmap;
                previewSource = result == null ? null : result.source;
                previewNotice = result == null ? null : result.notice;
                previewReason = message; previewLoading = false;
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
                if (enabled) {
                    LibraryScanner.Item fresh = find(scanWorking(current, source), item);
                    if (fresh == null || fresh.warning != null || fresh.defNode == null)
                        throw new IOException("Cannot enable a reference missing from the current source: " + item.reference);
                }
                RosterStore.setEnabled(current, item, enabled, retention());
            } catch (Exception error) { runOnUiThread(() -> { if (sameBinding(current, source)) {
                busy = false; showStatus("Roster update stopped: " + error.getMessage());
            } }); return; }
            try {
                LibraryScanner.Catalog scanned = scanWorking(current, source);
                runOnUiThread(() -> {
                    if (!sameBinding(current, source)) return;
                    catalog = scanned; catalogEpoch++;
                    selected = find(scanned, item);
                    busy = false;
                    renderList();
                    if (selected != null) {
                        int position = browserAdapter.positionOf(selectionKey(selected));
                        if (position >= 0) { list.setSelection(position); list.requestFocus(); }
                    }
                    showStatus("Working roster updated. Review Export to apply it to the linked folder.");
                });
            } catch (Exception refresh) { runOnUiThread(() -> { if (sameBinding(current, source)) {
                busy = false; catalog = null; selected = null; renderList();
                showStatus("Working roster saved. Source refresh unavailable: " + refresh.getMessage());
            } }); }
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
                if (focused == list && list != null) {
                    int position = list.getSelectedItemPosition();
                    if (position < 0) position = list.getFirstVisiblePosition();
                    activateBrowserItem(position);
                } else if (focused != null) focused.performClick();
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
                    if (focused == list && browserAdapter != null && !browserAdapter.isMessage()) {
                        int position = Math.max(0, list.getSelectedItemPosition());
                        int columns = browserGrid()
                                ? (getResources().getConfiguration().orientation
                                == android.content.res.Configuration.ORIENTATION_LANDSCAPE ? 3 : 2) : 1;
                        int nextPosition = switch (direction) {
                            case ControllerPolicy.UP -> position - columns;
                            case ControllerPolicy.DOWN -> position + columns;
                            case ControllerPolicy.LEFT -> position % columns == 0 ? -1 : position - 1;
                            case ControllerPolicy.RIGHT -> position % columns == columns - 1 ? -1 : position + 1;
                            default -> -1;
                        };
                        if (nextPosition >= 0 && nextPosition < browserAdapter.getCount()) {
                            list.setSelection(nextPosition);
                            lastStickMove = now;
                            return true;
                        }
                    }
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
