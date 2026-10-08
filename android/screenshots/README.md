# Android screenshots

These screenshots use synthetic demonstration content created for this project. They show the Android app UI without distributing files from a user's game archive. The linked prerelease pages identify which image belongs to each version.

## 0.1.0

![Android library browser in 0.1.0](android-v0.1.0-library.png)

The first library browser in landscape mode.

## 0.2.0

![Android library browser in 0.2.0](android-v0.2.0-library.png)

The initial previews and Fighter Lab icon are also shown in the [character](android-v0.2.0-character.png), [stage](android-v0.2.0-stage.png), and [icon](android-v0.2.0-icon.png) screenshots.

## 0.3.0

![Portrait library layout in 0.3.0](android-v0.3.0-library-portrait.png)

The details pane stays visible in portrait mode, with character artwork below name, author, and reference.

## 0.4.0

![Roster actions in 0.4.0](android-v0.4.0-roster-actions.png)

Roster actions include export review, backups, restore, and recovery. The [export review](android-v0.4.0-export-review.png) shows its change summary and backup plan before overwriting `select.def`.

## 0.5.0

![Combined character artwork in the details pane](android-v0.5.0-character-modes.png)

Choose neutral, portrait, or combined character artwork. The screenshot shows the combined setting.

![Composed two-layer stage scene in the details pane](android-v0.5.0-stage-scene.png)

A supported 2D stage shows a static composition of two background layers.

## 0.6.0

These actual-app captures use a self-created demonstration library. They were captured from signed candidate `c0b38d9`; later recovery corrections did not change these screens. See the [0.6.0 prerelease](https://github.com/chillednems/IKEMEN-LAB/releases/tag/android-v0.6.0) for its APK and screenshots.

![Library browser with visible Export action in landscape](android-v0.6.0-landscape-export.png)

The main library browser keeps **Export** visible beside Roster actions and Settings.

![Settings with current source and backup location](android-v0.6.0-settings.png)

Settings shows the selected source, preview mode, orientation, backup destination, retention, and no-change warning.

![Character preview setting with a selected choice](android-v0.6.0-selected-preview.png)

The preview setting marks **Neutral over portrait** as the current choice with a checkmark and text.

## Screenpack-aware roster (Android feature branch)

![Approximate select screen and roster arrangement with a synthetic 1-by-3 screenpack](android-screenpack-roster-portrait.png)

The static preview reads the active motif's select-screen capacity and shows the five occupied roster slots, including random and empty slots, with two beyond capacity. This is an approximate slot-order view; in-game placement may differ. The capture uses a self-created demonstration library and the tested `600f115` debug APK.

## Recycled Android browser (feature branch)

![Synthetic character thumbnails in the Android library grid](android-browser-grid-synthetic.png)

The grid displays visible character thumbnails from a self-created demonstration library and a selected item's details. The list/grid control preserves controller selection while switching views. Captured from the independently tested `b251c11` debug APK.
