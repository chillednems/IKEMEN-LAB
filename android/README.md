# IKEMEN Lab for Android

IKEMEN Lab browses an unpacked IKEMEN or MUGEN folder, previews supported character and stage artwork, and manages its `select.def` roster. Android 8.0 or newer is required. It does not launch a game.

## Import and browse

1. Choose **Switch folder** and pick the library root containing `chars/` and `stages/`. Allow read and write access if Android offers it. The app copies the folder into its private storage and remembers that library between launches. The picked source is unchanged during import.
2. Search or scroll characters and stages. Tap an item once to select it; tap the selected item again to toggle its roster state. On a controller, move focus with the D-pad or left stick and press **A** once to select, then **A** again to toggle. **B** clears the selection or goes back.
3. Details show name, author, reference, and preview before file paths and roster controls. The list and details scroll separately. Missing roster references remain visible in red with a text warning; a missing entry can be disabled but cannot be enabled until its file is restored.

On a short landscape window, **More** offers **Switch folder** and **Manage roster & settings**; the latter opens Roster actions and Settings. The toolbar has a direct **Portrait** control. On larger windows, these controls appear in the main action row. You can change back to Landscape through **Settings → Screen orientation**. The orientation choice persists.

## Artwork previews

Character details default to a static neutral stance from AIR action 0. In **Settings → Character preview**, choose **Portrait** or **Neutral over portrait** instead; the choice is saved. Details name the sprite or scene used and explain when a requested view falls back to another supported image. Images fit inside the preview area without cropping.

Stage details show a static composition of supported 2D background layers at the stage's starting camera position. A 3D or layerless stage may show an explicitly labeled SFF thumbnail instead of a full scene.

## Export safely to the source folder

Open **Roster actions → Review export to linked source**. The review shows the exact existing destination, counts of enabled, disabled, added, and removed references, missing-reference warnings, and the pre-write backup location. **No changes** means the source already matches the private roster.

Choose **Back up and overwrite** only after checking the review. The app first saves and verifies the exact source preimage in `data/select-backups/`, then updates the existing `data/select.def` and reads it back. If the destination changed after the review, the operation stops and asks for a fresh preview. It does not create a numbered `select.def` or `select.def.txt` in the source. A provider that cannot preserve the exact backup filename or write safely stops the export.

**Roster actions → Save a copy elsewhere** still uses Android's document picker. That copy does not update the linked source folder; use it when moving a roster to another installation manually. Check the filename your provider creates.

## Backups and restore

Every changed private roster edit saves a verified private version. A successful source overwrite saves a verified source preimage. Open **Roster actions → Backups and restore** to see available private and source versions. Select a version, then choose:

- **Load local only** to replace the app's private working roster while leaving the source untouched.
- **Source and local** to review the exact source replacement, back up its current bytes, then apply the selected version to both source and private working roster.

In **Settings → Backups to keep**, leave the field empty for unlimited history (the default), or enter a positive number. Finite retention applies after successful writes and prunes only verified backups managed by this app. A restore may temporarily keep its selected version and immediate pre-write backup beyond that number. Unmanaged files are left alone.

If the source folder's permission is revoked or you upgrade from an older local-only library, choose **Settings → Reconnect source folder** (also available in Roster actions) and pick the original folder again. Reconnect accepts only the previously linked folder; use **Switch folder** to import a different library. Your private copy remains available. If an interrupted source operation needs attention, **Roster actions → Recovery** shows the required step; normal edits and exports remain blocked until it is resolved. If a restore is irreconcilable, Recovery can preserve and verify both current versions as backups before stopping that restore.

## Current limits

Import accepts an unpacked folder rather than a ZIP/RAR/7z archive, with up to 20,000 entries, 8 GB of copied data, and 20 folder levels. Failed imports remove their partial staging copy. Switching folders retains earlier managed copies in private app storage; uninstalling the app removes those copies and their private backups.

Previews support direct PNG and bounded SFF v1 8-bit PCX and SFF v2 indexed or embedded PNG sprites. They are still images: character previews do not animate, and stage previews do not run gameplay, GLB/3D rendering, or every BG effect. Unsupported encodings, missing palettes, oversized or malformed images may be unavailable. Legacy Japanese `.def` metadata uses heuristic decoding and can misread a one-character field. The app does not manage screenpacks, install content into a game, or launch IKEMEN GO.

## Build from source

The package ID is `com.chillednems.ikemenlab`; this source is version 0.5.0 (version code 5). Install JDK 17 and Android SDK platform/build tools 36. From `android/`, run:

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The debug APK uses a debug certificate and cannot update a release-signed install. A release build requires the existing key through `IKEMEN_RELEASE_STORE_FILE` (absolute path), `IKEMEN_RELEASE_KEY_ALIAS`, `IKEMEN_RELEASE_STORE_PASSWORD`, and `IKEMEN_RELEASE_KEY_PASSWORD` in the build environment. With those set, run `./gradlew :app:assembleRelease`. Preserve that key for updates and keep it and its passwords outside Git.
