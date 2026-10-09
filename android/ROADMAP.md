# Android 0.7.0 and remaining roadmap

The [published 0.7.0 prerelease](https://github.com/chillednems/IKEMEN-LAB/releases/tag/android-v0.7.0) includes the features below. The [0.6.0 prerelease](https://github.com/chillednems/IKEMEN-LAB/releases/tag/android-v0.6.0) remains the direct-source baseline described below. Android development targets `codex/android-library`; `main` receives Android only in a later approved rollout. The 0.7.0 APK was built from [source commit `f7d01ca`](https://github.com/chillednems/IKEMEN-LAB/commit/f7d01cac3de7335d4f554ad59108a3cf37a53bc5) and passed signed build, privacy, and synthetic emulator upgrade checks.

## Published 0.6.0 baseline

The selected library's `chars/` and `stages/` stay in place through Android's persisted folder permission. The app does not duplicate whole character or stage collections in app-private storage. It keeps small settings and a private working roster; backups may be placed in the app's directory by user choice. Earlier private copies remain after upgrade. See [storage details](DIRECT-SOURCE-DEVELOPMENT.md).

Export reviews the working `select.def` against the linked source, warns by default when unchanged, and offers an explicit **Export anyway** path. The preimage backup can go to the source `select-backups` folder, the app's backup directory, or a user-selected writable folder. Export and restore retain source and backup recovery checks.

## Published 0.7.0 features

1. **Screenpack-aware roster:** Resolve `save/config.ini` motif, `system.def`, and the active select target. Show a bounded approximate slot grid, capacity, overflow, and an ordered list. Touch and controller reorder affect only the private working roster when the active target is verified as `data/select.def`. Screenpack art, spacing, and actual engine placement are not edited or reproduced.
2. **Browser and details:** Recycled list/grid thumbnails, expanded bounded DEF and CMD input-definition views, source-specific manual tags, conservative inferred cues, and type/status/tag filtering. CMD labels are static definitions, not verified playable moves.
3. **Collections:** Source-specific named snapshots and smart rules over supported metadata. Preview and activation are explicit; activation changes the private working roster, while linked source changes still require Export.
4. **Add-only content:** Stage one character or stage ZIP/folder privately, validate a root DEF and supported references, then review the exact destination before adding files. Existing names are never replaced and `select.def` is never changed by import. Recovery acts only on verified journal-owned pending writes. A read-only health action checks supported references. RAR archives and bulk packs are outside this workflow.
5. **Engine and sharing:** Open the official `org.ikemen_engine.ikemen_go` launcher only after a warning; no source folder or collection is passed. The official v1 asset refresh may overwrite `select.def`. A user-shared ZIP or confirmed HTTPS link from exact GitHub release hosts enters the same importer; other HTTPS links open in the browser for user download and sharing back. No automatic network download or install occurs.
6. **Experimental PNG stage:** A bounded PNG becomes one static 1280×720 2D backdrop and select thumbnail, with its center crop shown before the normal add-only import review. It does not auto-register a stage in `select.def` or promise gameplay fidelity.

## Remaining validation and limits

The select-screen view is a static approximation, not an engine renderer. Image, SFF, DEF, and document-provider support is bounded; unsupported content is reported rather than silently rewritten. The add-only importer's real-provider interrupted-write recovery was not exercised in the representative emulator QA; fake-provider tests cover the journal logic. Shared-link address checks are a best-effort preflight and do not pin DNS for the platform TLS connection. A synthetic PNG stage passed emulator SAF install, preview, health, cancel, and collision checks; engine gameplay appearance and playability remain unverified.

Emulator checks do not replace physical Odin 3 testing of portrait touch targets, controller focus, source grants, and install/upgrade. Public-safe screenshots belong with visible Android branch features and a later prerelease; do not publish assets from a private game archive.
