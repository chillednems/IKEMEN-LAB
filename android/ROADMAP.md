# Android roadmap after 0.6.0

This page separates the 0.6.0 prerelease baseline from work still planned. Android changes use separate, scoped PRs into `codex/android-library`. The `main` branch receives Android only at a later approved rollout.

## Delivered in 0.6.0: direct library access

The [0.6.0 prerelease](https://github.com/chillednems/IKEMEN-LAB/releases/tag/android-v0.6.0) keeps the selected library's `chars/` and `stages/` in place using Android's persisted folder permission. It does not duplicate whole character or stage collections in app-private storage. Small app settings, roster working state, and backups placed in the app's own directory by user choice may still be stored there. Earlier private copies remain after upgrade. See [storage details](DIRECT-SOURCE-DEVELOPMENT.md).

## Delivered in 0.6.0: export and backup choices

Version 0.6.0 prompts on an unchanged `select.def` by default with an explicit **Export anyway** path. A setting can skip that extra prompt without skipping the normal review. It offers three preimage destinations: the default `select-backups` location in the selected IKEMEN data folder, the app's backup directory, or a user-selected writable directory. Exports and restores state the destination and retain recovery checks.

## Character-select view in development

The first implementation resolves the active motif from `save/config.ini`, reads its screenpack `system.def` and select target, and shows a bounded approximate slot grid and ordered list. It supports touch and controller reorder of the private working roster only when the screenpack points to `data/select.def`, with existing export review and recovery. It reports capacity and cutoff, and warns for missing or alternate screenpack data. It does not edit the screenpack's grid, spacing, portraits, or positions. Rendering exact engine placement remains future work.

## Later: import new game content

After the current library manager has been tested on the Odin 3, a later version may import new characters and stages into the selected IKEMEN folder. That workflow should stage only the new content temporarily, validate it, and ask before adding it to the game folder. This is deferred and is not part of the direct-access change.

For visible features, add public-safe screenshots to the Android branch and the corresponding prerelease. Changes with no meaningful visual effect do not need new screenshots. Never publish assets from a user's private game archive.

## Physical-device controls follow-up

Version 0.6.0 pads action sheets for Android navigation insets; emulator touch targets passed QA. Verify portrait touch targets and physical controls on the Odin 3 before closing the device-specific check.
