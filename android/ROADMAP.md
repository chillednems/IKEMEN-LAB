# Android roadmap after 0.5.0

This page describes requested work that is **not yet in a released APK**. Android changes use separate, scoped PRs into `codex/android-library`. The `main` branch receives Android only at a later approved rollout.

## Direct library access

The 0.6.0 development candidate keeps the selected library's `chars/` and `stages/` in place using Android's persisted folder permission. It does not duplicate whole character or stage collections in app-private storage. Small app settings, roster working state, and backups placed in the app's own directory by user choice may still be stored there. The earlier private copy is retained on upgrade; verification and release approval remain pending. See [candidate behavior](DIRECT-SOURCE-DEVELOPMENT.md).

## Export and backup choices

The 0.6.0 development candidate prompts on an unchanged `select.def` by default with an explicit **Export anyway** path. A setting can skip that extra prompt without skipping the normal review. It offers three preimage destinations: the default `select-backups` location in the selected IKEMEN data folder, the app's backup directory, or a user-selected writable directory. Exports and restores state the destination and retain recovery checks. Verification and release approval remain pending.

## Character-select view

A separate screen should preview the character-select screen using the current screenpack and let users arrange the roster. This first version does not edit the screenpack's grid, spacing, portraits, or positions. If a usable screenpack is missing, show a clear warning. The suggested remedy (including whether launching IKEMEN once creates a default screenpack for this installation) must be verified before it is shown in the app.

## Later: import new game content

After the current library manager has been tested on the Odin 3, a later version may import new characters and stages into the selected IKEMEN folder. That workflow should stage only the new content temporarily, validate it, and ask before adding it to the game folder. This is deferred and is not part of the direct-access change.

For visible features, add public-safe screenshots to the Android branch and the corresponding prerelease. Changes with no meaningful visual effect do not need new screenshots. Never publish assets from a user's private game archive.

## Portrait export controls follow-up

The 0.6.0 development candidate pads action sheets for Android navigation insets. Emulator and Odin 3 hit-target verification is still required before treating the portrait overlap as resolved.
