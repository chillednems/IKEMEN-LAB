package com.chillednems.ikemenlab;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

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
    private static final String PREFS = "library";
    private static final String KEY_PATH = "active_path";
    private LinearLayout root;
    private LinearLayout list;
    private LinearLayout detail;
    private TextView status;
    private EditText search;
    private File library;
    private LibraryScanner.Catalog catalog;
    private LibraryScanner.Item selected;
    private String searchText = "";
    private boolean busy;
    private long lastStickMove;
    private android.window.OnBackInvokedCallback backCallback;
    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener libraryChanged = (prefs, key) -> {
        if (!KEY_PATH.equals(key)) return;
        String path = prefs.getString(KEY_PATH, null);
        if (path != null) {
            File saved = new File(path);
            if (saved.isDirectory() && saved.getParentFile().equals(new File(getFilesDir(), "libraries"))) {
                library = saved;
                selected = null;
                refreshCatalog();
            }
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String path = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_PATH, null);
        if (path != null) {
            File saved = new File(path);
            File parent = new File(getFilesDir(), "libraries");
            if (saved.isDirectory() && saved.getParentFile().equals(parent)) library = saved;
        }
        if (state != null) searchText = state.getString("search", "");
        getSharedPreferences(PREFS, MODE_PRIVATE).registerOnSharedPreferenceChangeListener(libraryChanged);
        if (Build.VERSION.SDK_INT >= 33) {
            backCallback = this::handleBack;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
        }
        buildScreen();
        refreshCatalog();
    }

    @Override protected void onDestroy() {
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null)
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        getSharedPreferences(PREFS, MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(libraryChanged);
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("search", search == null ? searchText : search.getText().toString());
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
        root = column();
        root.setBackgroundColor(0xff111d27);
        root.setPadding(dp(12), dp(8), dp(12), dp(8));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int left, top, right, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                left = bars.left; top = bars.top; right = bars.right; bottom = bars.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft(); top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight(); bottom = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(dp(12) + left, dp(8) + top, dp(12) + right, dp(8) + bottom);
            return insets;
        });
        setContentView(root);

        ScrollView page = new ScrollView(this);
        page.setFillViewport(true);
        root.addView(page, new LinearLayout.LayoutParams(-1, -1));
        LinearLayout body = column();
        page.addView(body);
        body.addView(label("IKEMEN Lab · Android library", 24, true));
        body.addView(label("Manage characters and stages. Game launch is planned for later.", 14, false));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        body.addView(actions);
        actions.addView(button("Import folder", this::pickFolder), new LinearLayout.LayoutParams(0, dp(58), 1));
        actions.addView(button("Export select.def", this::pickExport), new LinearLayout.LayoutParams(0, dp(58), 1));
        status = label("Choose an IKEMEN folder with chars and stages.", 14, false);
        body.addView(status);

        search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("Search characters or stages");
        search.setTextColor(Color.WHITE);
        search.setHintTextColor(0xffa8b9c7);
        search.setText(searchText);
        search.setPadding(dp(12), dp(8), dp(12), dp(8));
        search.setBackgroundColor(0xff243440);
        body.addView(search, new LinearLayout.LayoutParams(-1, dp(52)));
        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { searchText = s.toString(); renderList(); }
            public void afterTextChanged(android.text.Editable e) {}
        });

        boolean wide = getResources().getConfiguration().screenWidthDp >= 600;
        LinearLayout panels = new LinearLayout(this);
        panels.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        body.addView(panels, new LinearLayout.LayoutParams(-1, -2));
        list = column();
        detail = column();
        panels.addView(list, wide ? new LinearLayout.LayoutParams(0, -2, 1.15f) : new LinearLayout.LayoutParams(-1, -2));
        panels.addView(detail, wide ? new LinearLayout.LayoutParams(0, -2, 1f) : new LinearLayout.LayoutParams(-1, -2));
        renderList();
    }

    private void showStatus(String message) { if (status != null) status.setText(message); }

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
        intent.setType("text/plain");
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
                runOnUiThread(() -> { if (!isDestroyed() && current.equals(library)) { catalog = scanned; renderList(); showStatus(summary()); } });
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
            row.setTag(item.kind + "|" + item.reference);
            row.setOnClickListener(v -> {
                selected = item;
                renderList();
                View replacement = list.findViewWithTag(item.kind + "|" + item.reference);
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
        if (selected == null) { detail.addView(label("Select a character or stage. Use touch, D-pad, or left stick; A selects and B goes back.", 15, false)); return; }
        detail.addView(label(selected.name, 21, true));
        detail.addView(label("Author: " + selected.author, 16, false));
        detail.addView(label("Reference: " + selected.reference, 14, false));
        detail.addView(label("DEF: " + selected.file, 13, false));
        detail.addView(label("Roster: " + (selected.enabled == null ? "Not listed" : selected.enabled ? "Enabled" : "Disabled"), 16, false));
        detail.addView(button(selected.enabled != null && selected.enabled ? "Disable in roster" : "Enable in roster", () -> toggleSelected(selected.enabled == null || !selected.enabled)));
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

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0 && (event.getSource() & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) {
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
