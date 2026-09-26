# Android 0.5 preview backend checkpoint

Branch: `codex/android-previews`, based on `0e837242f5ca3cc2ddbb2746176d9686729f762a`. This branch owns only the preview backend and synthetic tests. UI integration and settings belong to the other assigned Developer; there is no merge or release here.

## API for UI integration

- `CharacterPreview.render(File characterDef, Mode mode, int width, int height)` supports `PORTRAIT`, `NEUTRAL`, and `NEUTRAL_OVER_PORTRAIT`.
- `StagePreview.render(File stageDef, int width, int height)` returns a representative static 2D scene.
- Both return `PreviewFrame` with `width`, `height`, `argb`, `source`, and nullable `notice`. Android can call `Bitmap.createBitmap(frame.argb, frame.width, frame.height, Bitmap.Config.ARGB_8888)`.
- Errors are `IOException` with bounded failure reasons. A 3D or layerless stage returns a clearly labeled thumbnail where one exists.

## Implementation and private fixture evidence

`PreviewSff` reads SFF headers, tables, palettes, and selected sprites by bounded random access. It handles v1 PCX, v2 indexed/truecolor PNG formats 10-12, RLE8 format 2, LZ5 format 4, and linked sprite records. `PreviewPng` decodes bounded 8-bit noninterlaced PNG rows to ARGB. AIR action 0 controls the neutral pose; stage DEF sections remain ordered even when BG names repeat. Scene composition uses local coordinates, camera start, sprite axes, BG starts/deltas/tiles, parallax width/xscale, and transparency. Work, compressed bytes, sprite pixels, and output size have explicit caps.

The supplied archive was checked for traversal, symlinks, duplicate paths, entry count and total size before extraction. A private full copy is at `/private/tmp/ikemen-user-fixture/full/Ikemen-GO`; the original ZIP was not modified. Neither archive bytes nor artwork are in this branch.

Real fixture checks using a separate local Java probe: Hiruzen, kfm, and Madara neutral poses rendered, including Madara AIR 0 reference 677,0 linked to sprite record 22. Stage0 rendered two layers, stage1 three, kfm seven, stageZ five, interactive training stage sixteen, and stage0-720 two. Stage3D returned a labeled SFF thumbnail. Representative rendered PNGs were inspected privately under `/private/tmp/ikemen-user-fixture/preview-audit`; they are not deliverable assets.

Synthetic repository tests cover indexed palette PNG, a linked neutral frame referenced by AIR action 0 after collision lines, repeated BG sections and layer order, malicious offsets, composition work bounds, a 65 MB sparse SFF, and oversized PNG dimensions. Full Android unit tests, lintDebug, and assembleDebug passed with Gradle offline. Device UI and visual QA against the final integrated app remain for independent QA.

## Known limits

Scenes are static snapshots at the DEF camera start. BG animation chooses its first valid frame; runtime BGCtrl, 3D GLB rendering, and every possible MUGEN SFF compression/color variant are outside this backend. The renderer reports omitted layers and 3D thumbnail status in `notice` so the UI can show the limit. Large or malformed assets fail within bounds rather than allocating without limit.

Format decoding was checked against the [IKEMEN GO image reader](https://github.com/ikemen-engine/Ikemen-GO/blob/develop/src/image.go); the real archive was used only for local verification. The private audit report is `/private/tmp/ikemen-user-fixture/preview-audit/report.md`.
