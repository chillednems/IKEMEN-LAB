# IKEMEN Lab for Android

IKEMEN Lab lets you browse an unpacked IKEMEN or MUGEN library, preview supported character and stage artwork, and edit its `select.def` roster on Android 8.0 or newer. It does not launch a game. Touch and physical D-pad, stick, A, and B controls are supported.

**Release status:** The newest [published Android prerelease is 0.5.0](https://github.com/chillednems/IKEMEN-LAB/releases/tag/android-v0.5.0). This Android branch contains an **unreleased 0.6.0 candidate** under review. Its instructions below describe this branch's current app, not the 0.5.0 APK. [The 0.5.0 README](https://github.com/chillednems/IKEMEN-LAB/blob/android-v0.5.0/android/README.md) explains the older app, which copied a selected library into private storage. Earlier [0.1.0–0.5.0 screenshots](screenshots/README.md) use public-safe sample content. `main` has not received the Android app.

The [0.6.0 candidate screenshots](screenshots/README.md#060-development-candidate) show the visible Export action, current Settings values, and a character preview with self-created sample content.

## Start using the 0.6.0 candidate

1. Open **Settings → Source folder** and choose the unpacked library root containing `chars/` and `stages/`. Grant lasting read access, and write access if you want to export to its existing `data/select.def`. The app remembers this folder across launches. It reads character and stage files **in place**; it does not duplicate those folders in app storage.
2. Search or scroll the library. Tap an item once to select it and again to enable or disable it. With physical controls, move focus using the D-pad or left stick and press **A** to select, then **A** again to toggle; **B** clears selection or goes back. Details show the artwork below the name, author, and reference. Missing entries stay visible in red with a text warning; disable them if needed, but restore their files before enabling them.
3. Use **Export** on the main screen when you want the working roster written to the source `data/select.def`. The button stays visible in landscape, portrait, and compact layouts. If export is unavailable, the screen says why. You can also save a separate copy through **Roster actions → Save a copy elsewhere**.

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

The selected library must be an unpacked folder, not a ZIP/RAR/7z archive. Browsing is bounded to 20,000 listed entries and 20 folder levels. There is no 8 GB full-folder copy limit in this candidate because characters and stages stay in the selected source. Earlier 0.5.0 private copies, working roster edits, and backups are preserved on upgrade; the app does not silently delete them or fall back to stale copied content if source permission is lost. Reconnect the original folder in Settings when needed. [Storage and migration details](DIRECT-SOURCE-DEVELOPMENT.md) describe the development candidate.

This candidate has not yet completed signed-device QA, including physical Odin 3 touch/controller checks. Screenpack preview and roster arrangement, importing new game content, and game launching are future work; see the [roadmap](ROADMAP.md).

## Build from source

The package ID is `com.chillednems.ikemenlab`; this branch is version 0.6.0 (version code 6). Install JDK 17 and Android SDK platform/build tools 36. From `android/`, run:

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The debug APK uses a debug certificate and cannot update a release-signed install. A release build requires the existing key through `IKEMEN_RELEASE_STORE_FILE` (absolute path), `IKEMEN_RELEASE_KEY_ALIAS`, `IKEMEN_RELEASE_STORE_PASSWORD`, and `IKEMEN_RELEASE_KEY_PASSWORD` in the build environment. With those set, run `./gradlew :app:assembleRelease`. Preserve that key for updates and keep it and its passwords outside Git.
