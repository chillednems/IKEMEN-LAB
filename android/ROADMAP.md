# Android roadmap after 0.5.0

This page describes requested work that is **not yet in a released APK**. Android changes use separate, scoped PRs into `codex/android-library`. The `main` branch receives Android only at a later approved rollout.

## Direct library access

The next storage change should keep the selected library's `chars/` and `stages/` in place and use Android's persisted folder permission to read them across launches. It must not duplicate whole character or stage collections in app-private storage. Small app settings, roster working state, backups placed in the app's own directory by user choice, and bounded temporary preview data may still be stored there. This replaces the current full-folder copy behavior; existing users need a safe transition that preserves their selected library and roster edits.

## Export and backup choices

An unchanged `select.def` should produce a clear prompt with **Export anyway**. A setting controls whether this unchanged-export message appears; it defaults to showing the message. Backups should offer three destinations: the default `select-backups` location in the selected IKEMEN data folder, the app's backup directory, or a user-selected writable directory. Exports and restores must state where the pre-write backup went and retain recovery checks.

## Character-select view

A separate screen should preview the character-select screen using the current screenpack and let users arrange the roster. This first version does not edit the screenpack's grid, spacing, portraits, or positions. If a usable screenpack is missing, show a clear warning. The suggested remedy (including whether launching IKEMEN once creates a default screenpack for this installation) must be verified before it is shown in the app.

## Later: import new game content

After the current library manager has been tested on the Odin 3, a later version may import new characters and stages into the selected IKEMEN folder. That workflow should stage only the new content temporarily, validate it, and ask before adding it to the game folder. This is deferred and is not part of the direct-access change.

For visible features, add public-safe screenshots to the Android branch and the corresponding prerelease. Changes with no meaningful visual effect do not need new screenshots. Never publish assets from a user's private game archive.

## Portrait export controls follow-up

Check the export-review panel against Android navigation insets and controller focus in portrait mode. An emulator capture shows the bottom controls near or behind the navigation bar; one comparable 0.5.0 sheet tap returned to Home. Verify the actual hit targets on representative displays and the Odin 3, then adjust layout if needed.
