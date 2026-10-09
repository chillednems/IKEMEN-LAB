# IKEMEN Lab for Android

IKEMEN Lab lets you browse an unpacked IKEMEN or MUGEN library, preview supported character and stage artwork, and edit its `select.def` roster on Android 8.0 or newer. It does not run a game or pass a library to the IKEMEN GO app. Touch and physical D-pad, stick, A, and B controls are supported.

**Published version:** [Android 0.7.0](https://github.com/chillednems/IKEMEN-LAB/releases/tag/android-v0.7.0) is the current prerelease. Install its signed APK over an earlier release-signed version. The older [0.5.0 release](https://github.com/chillednems/IKEMEN-LAB/releases/tag/android-v0.5.0) copied a selected library into private storage; its [historical README](https://github.com/chillednems/IKEMEN-LAB/blob/android-v0.5.0/android/README.md) describes that behavior. `main` has not received the Android app.

**New in 0.7.0:** The browser grid, screenpack-aware roster, metadata and tags, collections, add-only import, confirmed engine launch, shared ZIP review, and experimental PNG stage generation described below are in the published 0.7.0 APK. They were not in [0.6.0](https://github.com/chillednems/IKEMEN-LAB/releases/tag/android-v0.6.0). The published APK was built from [source commit `f7d01ca`](https://github.com/chillednems/IKEMEN-LAB/commit/f7d01cac3de7335d4f554ad59108a3cf37a53bc5); later documentation commits do not change its bytes.

The [0.6.0 screenshots](screenshots/README.md#060) show the visible Export action, current Settings values, and the selected character preview setting with self-created sample content.

## Start using Android

1. Open **Settings → Source folder** and choose the unpacked library root containing `chars/` and `stages/`. Grant lasting read access, and write access if you want to export to its existing `data/select.def`. The app remembers this folder across launches. It reads character and stage files **in place**; it does not duplicate those folders in app storage.
2. Search or scroll the library. Tap an item once to select it and again to enable or disable it. With physical controls, move focus using the D-pad or left stick and press **A** to select, then **A** again to toggle; **B** clears selection or goes back. Details show the artwork below the name, author, and reference. Missing entries stay visible in red with a text warning; disable them if needed, but restore their files before enabling them.
3. Use **Export** on the main screen when you want the working roster written to the source `data/select.def`. The button stays visible in landscape, portrait, and compact layouts. If export is unavailable, the screen says why. You can also save a separate copy through **Roster actions → Save a copy elsewhere**.

## Android 0.7.0 features

The **View: List/Grid** control changes between a compact list and thumbnail cards. In compact landscape layouts it is under **More**. Both views recycle offscreen cards, and thumbnails are decoded from the linked source with a bounded memory cache; the library is not copied into app storage. **Filter** narrows the browser by type, roster status, or a manual/inferred tag alongside search. Item details show conservative **Inferred cues** separately from **Manual tags**. Manual tags are app-private and tied to the selected source; they never edit game files. **DEF facts & input definitions** reads bounded metadata and a declared CMD file directly from the source. The CMD tab lists static input definitions, not verified playable moves, and explains missing or unsupported files.

[Synthetic facts and CMD screenshots](screenshots/README.md#source-facts-and-static-input-definitions-feature-branch) show the bounded views.

**Roster actions → Collections** saves named, source-specific snapshots of the current working character slots and stages. Snapshot entries can be added from the selected browser item, moved, or removed; random and empty slots are supported. Smart collections use only the available name, author, type, status, manual-tag, and inferred-cue fields, with All/Any rules. They are re-evaluated against the linked source each time you preview them. Activation requires a separate review and changes only the private working `data/select.def`; the linked source changes only through the existing **Export** review. Missing snapshot references block activation, and collections do not activate automatically at startup. Reconnect the same source folder to access its saved collections.

[Synthetic collection screenshots](screenshots/README.md#source-specific-collections-feature-branch) show the source-specific list and the private activation review.

**Roster actions → Import one add-on** accepts one character or stage folder or ZIP. It stages only that add-on privately, checks one root DEF and supported file references, then reviews the exact add-only destination, file count, bytes, and health findings before writing. Import never replaces an existing name and never edits `select.def`; enable an imported item separately in the roster. A provider must advertise create, rename, and delete support. Incomplete writes stay in a journal-owned pending folder for **Import recovery**; recovery keeps a verified completed add-on and removes only a verified pending folder owned by that journal. Ambiguous provider outcomes stop for inspection rather than deleting other source files. The selected-item **Library health** report reads supported DEF references without changing any files.

[Synthetic import and health screenshots](screenshots/README.md#reviewed-add-only-import-and-health-feature-branch) show the accepted review, blocked review, and read-only health report.

**Open IKEMEN GO** only opens the installed `org.ikemen_engine.ikemen_go` launcher after a warning: it does not pass a game folder or collection, and the official v1 app may overwrite `select.def` during asset refresh. A ZIP shared from another app needs a temporary read grant and an explicit character/stage choice; it enters the same private import review above. An exact GitHub release host HTTPS link may be downloaded after confirmation with bounded redirects and size, then enters that review. Other HTTPS links open in the browser for you to download and share the ZIP yourself. Host and address checks are a best-effort preflight; DNS may change between that check and the platform HTTPS connection. No link opens or downloads automatically, and canceling before Install leaves the source unchanged.

[Synthetic shared ZIP and engine warning screenshots](screenshots/README.md#shared-zip-and-engine-launch-warning-feature-branch) show the explicit import review and launch handoff warning.

**Experimental · PNG to static stage** accepts one PNG up to 16 MB with dimensions from 320×240 to 4096 per side and at most 8 million pixels. It shows the generated 1280×720 center crop before the ordinary add-only import review. The stage is a single static 2D background with a small select thumbnail; gameplay appearance is unverified. Canceling or backing out discards its private stage, and installation never registers it in `select.def`.

[Synthetic PNG stage screenshots](screenshots/README.md#experimental-png-static-stage-feature-branch) show the crop preview, separate import review, and installed static scene.

**Roster actions → Arrange roster · select screen** shows a bounded static preview using the active motif from `save/config.ini`, its `system.def`, and `[Select Info]` rows and columns. It reports active slot count and positions beyond the configured capacity. Character, `randomselect`, and literal `empty` lines appear in order; available character portraits appear in the first visible cells. Use **↑** and **↓** with touch or focus them with the D-pad and press **A** to move a slot in the private working roster. Each move keeps its complete entry, including options and comments. Review **Export** to apply those changes to the linked source.

The preview is an approximation of the engine's select screen. It displays up to 100 occupied positions when the screenpack has 16 or fewer columns; wider layouts use the ordered slot list so positions are not reflowed. It does not reproduce screenpack art, spacing, or game behavior. If the active screenpack points to a separate `select.def`, arrangement and linked export to `data/select.def` are unavailable; the app does not rewrite `system.def`. A missing or unsupported motif gives a warning and an unknown capacity.

Settings always shows the current source folder, character preview choice, orientation, backup location, retention, and no-change warning state. Source selection and reconnection are both in Settings. Choosing the same source again keeps your unexported working roster; choosing a different source starts a separate working roster without changing either source folder.

## Artwork and layout

**Settings → Character preview** offers a neutral stance from AIR action 0, a portrait, or the neutral stance over the portrait. Details identify the sprite used and explain fallbacks. Supported 2D stages show a static background composition at their starting camera position; unsupported scenes may show a labeled thumbnail or an unavailable reason. These are still images, not animated gameplay or 3D rendering. Each list and details pane scrolls independently, and the details pane remains visible while you move through the list. Choose **Settings → Screen orientation** for portrait or landscape; the choice persists.

Some Android document providers cannot offer seekable access to SFF artwork. In that case, browsing still works and the preview explains why it is unavailable. The app does not copy large artwork to work around that limitation. Previews support PNG and bounded SFF v1 8-bit PCX and SFF v2 indexed or embedded PNG sprites; unsupported encodings, missing palettes, or malformed images may not render.

## Review and export `select.def`

The app keeps a small private **working roster** so you can review edits before writing the source. **Export** compares it with the exact existing source `data/select.def`, shows enabled, disabled, added, and removed counts, warns about missing references, and identifies where the verified pre-write backup will go. It refuses to overwrite a source or backup destination that changed after review. A write makes a verified backup of the old source bytes, replaces the existing `select.def`, and reads it back. The linked-source export does not create `select.def.txt`.

If the working and source files match, a warning appears by default with a **Review Export anyway** option. You can turn that extra warning off in **Settings → No-change export warning**. In either mode, exporting still takes a separate tap on the normal review, creates a verified preimage backup, and reads back the source. The setting never starts a write by itself. If the source grants only read access, browse normally and reconnect with write access before exporting. A folder without an existing `data/select.def` can be browsed but has no linked file to overwrite.

## Choose and restore backups

In **Settings → Select.def backup location**, choose where verified **source preimages** are saved before exports and source restores:

- **Source data/select-backups** is the default and keeps them beside the game data.
- **App backup directory** stores them privately, in a source-specific area of the app.
- **Custom folder** uses a folder you choose. The app creates a source-specific subfolder inside it. If the picker is canceled or the provider cannot grant lasting read/write access, your previous selection stays in place.

Changing the selection does not delete or move older backups. **Roster actions → Backups and restore** lists verified versions with their location. Choose **Load local only** to replace the working roster without touching the source, or **Review source and local restore** to back up the current source and apply the chosen version to both. The new preimage goes to your **current** backup location even when the version you selected came from an older location. The separate **app working undo and recovery** snapshots remain private regardless of this setting. If source access is lost, those private snapshots and app backup versions remain available to load locally; reconnect in Settings before writing the source.

**Settings → Backups to keep** defaults to unlimited. Set a positive number to prune only this app's verified, managed backups after a successful write. A selected restore version and the immediate pre-write backup may temporarily exceed the limit. Unmanaged files are not removed. If an interrupted source operation needs attention, use **Roster actions → Recovery** before editing or exporting again.

## Limits and upgrades

The selected library must be an unpacked folder, not a ZIP/RAR/7z archive. Browsing is bounded to 20,000 listed entries and 20 folder levels. There is no 8 GB full-folder copy limit in 0.6.0 because characters and stages stay in the selected source. Earlier 0.5.0 private copies, working roster edits, and backups are preserved on upgrade; the app does not silently delete them or fall back to stale copied content if source permission is lost. Reconnect the original folder in Settings when needed. [Storage and migration details](DIRECT-SOURCE-DEVELOPMENT.md) explain the transition.

The signed 0.7.0 APK passed synthetic emulator checks for a clean install, upgrade from signed 0.6.0, source access, and core roster behavior. Physical Odin 3 touch/controller behavior, engine gameplay, and some providers' permission and interrupted-write recovery edge cases remain unverified; see the [roadmap](ROADMAP.md).

## Build from source

The package ID is `com.chillednems.ikemenlab`; this branch is version 0.7.0 (version code 7). Install JDK 17 and Android SDK platform/build tools 36. From `android/`, run:

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The debug APK uses a debug certificate and cannot update a release-signed install. A release build requires the existing key through `IKEMEN_RELEASE_STORE_FILE` (absolute path), `IKEMEN_RELEASE_KEY_ALIAS`, `IKEMEN_RELEASE_STORE_PASSWORD`, and `IKEMEN_RELEASE_KEY_PASSWORD` in the build environment. With those set, run `./gradlew :app:assembleRelease`. Preserve that key for updates and keep it and its passwords outside Git.

Before sharing an APK, run the [APK privacy gate](APK-PRIVACY.md). CI checks its debug APK; a signed release needs a fresh scan of the exact signed file and the recorded signer/package/version checks before upload.
