# IKEMEN Lab for Android

IKEMEN Lab lets you browse characters and stages from an unpacked IKEMEN or MUGEN folder, preview supported artwork, and edit a managed copy of its `select.def` roster. It does not launch a game. Android 8.0 or newer is required.

## Get started

1. Tap **Switch folder** and choose the root folder containing `chars/` and `stages/` in Android's folder picker. The app copies it into its private storage. The source folder stays untouched.
2. Search or scroll the character and stage list. Tap an item once to select it and show its details. Tap that selected item again to enable or disable it in the roster. With a controller, move focus to the item and press **A** once to select it, then **A** again to toggle it. You can also use the roster button in Details.
3. Tap **Export select.def** and choose where to save your edited roster. Check the filename shown by your document provider, then copy the exported file into the corresponding IKEMEN installation yourself. Exporting does not transfer characters or stages.

The list and details scroll separately, so the selected item's information stays visible while you browse the list. The app remembers the selected library between launches until you choose **Switch folder**. Each roster edit makes a backup of the previous `select.def` inside the managed copy. Switching folders retains earlier managed copies in private app storage; uninstalling the app removes those copies and their backups.

## Controls and settings

Use touch, a D-pad, or a left stick to navigate. **A** activates the focused control; **B** clears the current selection or goes back. A yellow outline marks controller focus. Roster edits are ignored while another operation is in progress.

Tap **Settings** to choose **Landscape** or **Portrait**. The app remembers your choice; landscape is the initial setting. In landscape, the list and details are side by side. In portrait, they are stacked, each with its own scroll area.

## Current limits

Import accepts an unpacked folder, not an archive, with up to 20,000 entries, 8 GB of copied data, and 20 folder levels. A failed import removes its partial copy. The app does not edit the original folder, install content into a game, manage screenpacks, or launch IKEMEN GO.

Artwork preview supports direct PNG, SFF v1 8-bit PCX with a palette, and SFF v2 embedded PNG formats 11/12. Stage previews show one sprite rather than a composited scene. Other SFF encodings, missing palettes, oversized images, or malformed art show an unavailable message. Legacy Japanese `.def` metadata uses heuristic decoding; a one-character Japanese field can display incorrectly. Check exported `select.def` against the target installation before replacing its roster.

## Build from source

The package ID is `com.chillednems.ikemenlab`. This source is version 0.3.0 (version code 3). Install JDK 17 and Android SDK platform/build tools 36. From `android/`, run:

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. It uses a debug certificate and cannot update a release-signed install. Release builds require the existing signing key and `IKEMEN_RELEASE_STORE_FILE` (absolute path), `IKEMEN_RELEASE_KEY_ALIAS`, `IKEMEN_RELEASE_STORE_PASSWORD`, and `IKEMEN_RELEASE_KEY_PASSWORD` in the build environment. With those set, run `./gradlew :app:assembleRelease`. Preserve the key for future updates. Do not put it or its passwords in Git.
