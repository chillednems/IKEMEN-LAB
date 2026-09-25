# IKEMEN Lab for Android (library preview)

This Android app manages a copied IKEMEN/MUGEN library. It does not include or launch IKEMEN GO. It is intended for landscape handheld use with touch or a built-in controller; Android may show it in another orientation or window size on large displays.

## Build

Install JDK 17 and Android SDK platform/build tools 36, then from `android/` run:

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. This is a locally signed debug build for testing, not a Play Store or public release artifact. Android 8.0 (API 26) or newer is required. SDK setup and license acceptance are the builder's responsibility.

## Use

1. Choose **Import folder** and select the root of an unpacked IKEMEN library with `chars/` and `stages/`. The app copies that tree into its private storage. It never edits the picked source folder. Case differences in the root folder names and `.def` extension are accepted.
2. Search characters and stages, select an entry, and enable or disable it in the roster. An item absent from `select.def` is shown as **Unlisted**; enabling it adds a relative reference. Each edit backs up the previous `data/select.def` inside the managed copy.
3. Choose **Export select.def** to write the edited roster to a user-selected location. Copy that exported file into your actual IKEMEN installation yourself, after checking it against that installation's content.

Use touch, D-pad, or left stick to move between controls. A selects the focused control; B goes back. A yellow outline shows controller focus. The page scrolls on short landscape screens and adapts to wider or narrower windows.

Import is limited to 20,000 entries, 8 GB of copied bytes, and 20 folder levels; failed imports remove their staging directory. It accepts folders, not archive files. Current browsing uses text metadata from character and stage `.def` files; it does not show SFF portraits. It does not install a browser extension, import RAR/7z/ZIP, manage screenpacks, edit the original picked folder, or launch a game. The app currently retains managed copies when a different folder is imported; Android app removal deletes them. Exported `select.def` must be paired with the appropriate content in the target IKEMEN library.

Unit tests cover DEF parsing, case-insensitive folder discovery, nested stages, select.def preservation and backups, import bounds, and stick direction policy. Emulator/physical-controller behavior still needs device QA, including on AYN Odin 3.
