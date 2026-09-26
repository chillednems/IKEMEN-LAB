# IKEMEN Lab for Android (library preview)

This Android app manages a copied IKEMEN/MUGEN library. It does not include or launch IKEMEN GO. It is intended for landscape handheld use with touch or a built-in controller; Android may show it in another orientation or window size on large displays. Its stable package ID is `com.chillednems.ikemenlab`. The 0.1.0 prerelease has version code 1; this 0.2.0 build has version code 2. Updates require a higher version code and the same signing certificate.

## Android releases

- **0.1.0 (code 1):** First library manager from source commit `c028b52`. Import an unpacked folder through Android's folder picker into a bounded managed copy; browse and search character and stage DEF metadata; enable or disable roster entries with backups and preservation of unrelated `select.def` content; export the roster through Android's document picker; navigate by touch or physical controller. This baseline includes legacy roster byte-preservation and lone-CR line-ending fixes. Some document providers may append `.txt` to an exported `select.def` in this version.
- **0.2.0 (code 2):** Adds bounded character portrait and stage artwork previews in supported SFF/PNG formats and the approved Fighter Lab launcher icon. Changes the export document type to avoid providers appending `.txt`, moves previews to a separate latest-selection queue so large artwork does not delay library operations, and adds environment-based durable release signing. A provider's final export filename should still be checked after saving. This version is intended to update 0.1.0 when signed with the same release key.

## Build

Install JDK 17 and Android SDK platform/build tools 36, then from `android/` run:

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. This is a locally signed debug build for testing, not a Play Store or public release artifact. Android 8.0 (API 26) or newer is required. SDK setup and license acceptance are the builder's responsibility.

To build the signed 0.2.0 APK, set `IKEMEN_RELEASE_STORE_FILE` to an existing **absolute** keystore path and set `IKEMEN_RELEASE_KEY_ALIAS`, `IKEMEN_RELEASE_STORE_PASSWORD`, and `IKEMEN_RELEASE_KEY_PASSWORD` in the build process environment. Run `./gradlew :app:assembleRelease`; missing values or a missing keystore fail the release build. Keep the keystore and credentials outside Git. The result is `app/build/outputs/apk/release/app-release.apk`. Preserve that signing key for updates, and increase `versionCode` for every later release. Existing debug installs use a different certificate: uninstall the debug app once before installing a release APK. This removes its managed library data, so export any needed `select.def` first. A release-signed 0.1.0 installation should upgrade to release-signed 0.2.0 without uninstalling when both use the same certificate.

## Use

1. Choose **Import folder** and select the root of an unpacked IKEMEN library with `chars/` and `stages/`. The app copies that tree into its private storage. It never edits the picked source folder. Case differences in the root folder names and `.def` extension are accepted.
2. Search characters and stages, select an entry, view its metadata and available sprite artwork, and enable or disable it in the roster. An item absent from `select.def` is shown as **Unlisted**; enabling it adds a relative reference. Each edit backs up the previous `data/select.def` inside the managed copy.
3. Choose **Export select.def** to write the edited roster to a user-selected location. Copy that exported file into your actual IKEMEN installation yourself, after checking it against that installation's content.

Use touch, D-pad, or left stick to move between controls. A selects the focused control; B goes back. A yellow outline shows controller focus. The page scrolls on short landscape screens and adapts to wider or narrower windows.

Import is limited to 20,000 entries, 8 GB of copied bytes, and 20 folder levels; failed imports remove their staging directory. It accepts folders, not archive files. Browsing uses `.def` metadata and shows a real character portrait or stage artwork sprite when it can decode SFF v1 8-bit PCX with a palette, SFF v2 embedded PNG formats 11/12, or a direct PNG. Stage artwork uses the first `[BG ...] spriteno` reference when present, then group 9000 or 0,0; it is one sprite, not a composited stage scene. SFF v2 RLE5/RLE8/LZ5, linked sprites, missing palettes, and oversized or malformed images report an unavailable preview. Preview SFF files are limited to 64 MB and decoded images to 4 million pixels. Legacy Japanese `.def` metadata is decoded heuristically; a field with only one Japanese character may display incorrectly. A future explicit encoding selection is needed for ambiguous files. It does not install a browser extension, import RAR/7z/ZIP, manage screenpacks, edit the original picked folder, or launch a game. The app currently retains managed copies when a different folder is imported; Android app removal deletes them. Exported `select.def` must be paired with the appropriate content in the target IKEMEN library.

Unit tests cover DEF parsing, case-insensitive folder discovery, nested stages and stage sprite references, SFF preview extraction/failure cases, select.def preservation and backups, import bounds, and stick direction policy. Emulator/physical-controller behavior still needs device QA, including on AYN Odin 3.
