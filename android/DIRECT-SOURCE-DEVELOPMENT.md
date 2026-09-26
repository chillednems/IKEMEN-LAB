# Direct source access in the Android development branch

This change is under review after the 0.5.0 prerelease. The published 0.5.0 APK still copies a selected library; its instructions remain in [README.md](README.md).

When a new folder is selected in this development build, the app keeps Android's persisted read permission and reads `chars/` and `stages/` from that source. It creates only a small private working `data/select.def` and its managed roster backups. Scans and artwork previews do not copy character or stage folders into app storage. Refresh library or restart the app to pick up source metadata changes while keeping unexported working roster edits.

An upgrade from 0.5.0 retains the existing private working roster, its backups, and any earlier copied library files. Browsing uses the remembered source link, not those copied files. If the source permission is gone, reconnect the original folder; the app does not silently fall back to stale copied content. Earlier copies are not deleted automatically. Reconnecting or switching folders never overwrites the source library.

Read-only access is sufficient to browse. Writing the existing source `data/select.def` still requires write permission and its normal export review. A folder with no source `data/select.def` can be browsed with an empty working roster, but cannot be overwritten through the linked-source export action until that source file exists. Some document providers expose artwork as a non-seekable stream; SFF previews from those providers show an unavailable reason while library browsing continues. There is no full-asset fallback copy.

This is an implementation checkpoint, not a published 0.6.0 APK. The separate Settings and export changes in [ROADMAP.md](ROADMAP.md) are still pending.
