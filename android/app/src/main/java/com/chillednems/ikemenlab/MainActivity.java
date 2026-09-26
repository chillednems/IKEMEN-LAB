package com.chillednems.ikemenlab;

import android.app.Activity;
import android.app.AlertDialog;
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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Landscape-friendly touch and hardware-controller library browser. */
public final class MainActivity extends Activity {
    private static final int PICK_TREE = 100;
    private static final int EXPORT_SELECT = 101;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final ExecutorService PREVIEW = Executors.newSingleThreadExecutor();
    private static final String PREFS = "library";
    private static final String KEY_PATH = "active_path";
    private static final String KEY_ORIENTATION = "orientation";
    private LinearLayout root;
    private LinearLayout list;
    private LinearLayout detail;
    private Button rosterButton;
    private TextView status;
    private boolean compactLayout;
    private boolean forceCompactLayout;
    private EditText search;
    private File library;
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
        setContentView(root);

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
            actions.addView(button("Export select.def", this::pickExport), new LinearLayout.LayoutParams(0, dp(58), 1));
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
        String current = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_ORIENTATION, "landscape");
        new AlertDialog.Builder(this).setTitle("Screen orientation")
                .setSingleChoiceItems(new String[]{"Landscape", "Portrait"}, "portrait".equals(current) ? 1 : 0,
                        (dialog, which) -> {
                            dialog.dismiss();
                            chooseOrientation(which == 1 ? "portrait" : "landscape");
                        })
                .setNegativeButton("Cancel", null).show();
    }

    private void chooseOrientation(String choice) {
        if (busy) return;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_ORIENTATION, choice).apply();
        applyOrientation();
    }

    private void showCompactMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("Switch folder").setOnMenuItemClickListener(item -> { pickFolder(); return true; });
        menu.getMenu().add("Export select.def").setOnMenuItemClickListener(item -> { pickExport(); return true; });
        menu.show();
    }

    private void showStatus(String message) {
        if (status != null) status.setText(message);
        else if (!message.equals(summary()) && !message.startsWith("Choose an IKEMEN folder"))
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private File managedLibrary(String path) {
        if (path == null) return null;
        File saved = new File(path);
        File parent = new File(getFilesDir(), "libraries");
        return saved.isDirectory() && parent.equals(saved.getParentFile()) ? saved : null;
    }

    private void syncActiveLibrary(String path) {
        File active = managedLibrary(path);
        if (active == null ? library == null : active.equals(library)) {
            if (active != null && catalog == null) refreshCatalog();
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
    }

    private void pickFolder() {
        if (busy) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, PICK_TREE);
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
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (request == PICK_TREE) {
            try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (SecurityException ignored) { /* The copy still uses the current grant. */ }
            busy = true;
            showStatus("Importing folder…");
            IO.execute(() -> {
                try {
                    File copied = SafImporter.importTree(getContentResolver(), uri, new File(getFilesDir(), "libraries"));
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_PATH, copied.getAbsolutePath()).apply();
                    LibraryScanner.Catalog scanned = LibraryScanner.scan(copied);
                    runOnUiThread(() -> { if (isDestroyed()) return; library = copied; catalog = scanned; selected = null; busy = false; renderList(); showStatus(summary()); });
                } catch (Exception error) { runOnUiThread(() -> { if (!isDestroyed()) { busy = false; showStatus("Import failed: " + error.getMessage()); } }); }
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
        return catalog.characters.size() + " characters · " + catalog.stages.size() + " stages · local copy " + library.getName();
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
            TextView row = label((item.kind.equals("characters") ? "Character" : "Stage") + " · " + item.name + " · " + state, 16, false);
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
        detail.addView(label("DEF: " + selected.file, 13, false));
        detail.addView(label("Roster: " + (selected.enabled == null ? "Not listed" : selected.enabled ? "Enabled" : "Disabled"), 16, false));
        rosterButton = button(selected.enabled != null && selected.enabled ? "Disable in roster" : "Enable in roster", () -> toggleSelected(selected.enabled == null || !selected.enabled));
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
        LibraryScanner.Item item = selected;
        File current = library;
        busy = true;
        showStatus("Updating roster…");
        IO.execute(() -> {
            try {
                RosterStore.setEnabled(current, item, enabled);
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
                    showStatus("Roster updated. Export select.def to use it outside this app.");
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
