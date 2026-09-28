# APK privacy gate

Every Android debug APK uploaded by CI must pass `scripts/apk_privacy_scan.py`. Its
unit tests, the build, and the scan run in that order; CI uploads the APK only
after the scan passes. The JSON scan report is retained as a separate CI
artifact, including on a scan failure.

Before uploading **any signed release APK**, scan the exact file after signing.
Keep a local deny-term file outside Git with one literal per line. Include
identifiers you would not want published, such as your username, home paths,
computer name, private email address, and private project names. The file may
contain comments starting with `#`; never commit or print its contents. A
signed scan requires the file, even when there are no extra terms to add.

Set `BUILD_SOURCE_SHA` to the full 40-character commit recorded for the APK
build. Compare this claim with the independent build log. It can differ from a
later documentation-only release tag commit. Ensure `python3`, `apksigner`,
and `aapt` from the Android SDK build tools are on `PATH`, then run from
`android/`:

```sh
python3 scripts/apk_privacy_scan.py /absolute/path/to/signed.apk \
  --source-sha "$BUILD_SOURCE_SHA" \
  --local-deny-file /absolute/path/to/private-deny-terms.txt \
  --release-signed \
  --expected-version-code 7 \
  --expected-version-name 0.7.0 \
  --report /absolute/path/to/private-apk-privacy-report.json
```

Use the release's actual version code and name. The command must exit zero and
the report must say `"status": "pass"`. The signed mode also requires Android
signature verification, the recorded release certificate SHA-256, package
`com.chillednems.ikemenlab`, and the requested version. The package and
`chillednems` signer identity are intentional public identifiers; do not
delete or rename them in an attempt to pass privacy review.

Before publication, retain the report with the release evidence and check its
`apk_sha256`, `scanner_sha256`, `source_sha`, `release_identity`, and local
deny-term count. Compare `apk_sha256` with the exact staged/uploaded asset
and compare `source_sha` with the build record. Re-run the gate if the APK,
scanner, deny terms, or signing changes. Never treat a report for a debug APK
or an earlier signed file as evidence for a different asset.

The scanner checks raw APK bytes (including signing-block strings), archive
names/metadata, decompressed entries, UTF-8/ASCII and UTF-16-like strings,
nested ZIP/JAR/AAR/APK entries, and bounded compressed PNG metadata. It rejects
malformed, encrypted, ambiguous, unsafe, unsupported, or over-budget archives.
Its findings list rule IDs and hashed entry names, not matched private text.
It cannot prove that every possible personal identifier is absent; review
release notes, screenshots, and bundled assets separately. An inaccessible
input, unavailable Android identity tool, or failed scan blocks upload.
