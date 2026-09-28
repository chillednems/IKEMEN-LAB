import importlib.util
import binascii
import io
import json
import stat
import subprocess
import tempfile
import unittest
import zipfile
import zlib
from pathlib import Path
from unittest import mock


SCRIPT = Path(__file__).resolve().parents[1] / "apk_privacy_scan.py"
spec = importlib.util.spec_from_file_location("apk_privacy_scan", SCRIPT)
scanner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(scanner)
SHA = "a" * 40


class ApkPrivacyScanTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.folder = Path(self.temp.name)
        self.apk = self.folder / "test.apk"

    def make_apk(self, entries, comment=b""):
        with zipfile.ZipFile(self.apk, "w", zipfile.ZIP_DEFLATED) as archive:
            for name, data in entries:
                archive.writestr(name, data)
            archive.comment = comment
        return self.apk

    def report(self, deny=None):
        return scanner.run(self.apk, SHA, deny)

    def test_public_package_and_project_identity_are_allowed(self):
        self.make_apk([("classes.dex", b"com.chillednems.ikemenlab chillednems")])
        report = self.report()
        self.assertEqual("pass", report["status"])
        self.assertEqual(SHA, report["source_sha"])
        self.assertEqual(64, len(report["apk_sha256"]))
        self.assertEqual(1, report["entries_scanned"])

    def test_private_path_in_compressed_entry_and_name_is_detected_without_echo(self):
        self.make_apk([("assets/Users/fixture/private.txt", b"/home/fixture/source")])
        report = self.report()
        self.assertEqual("fail", report["status"])
        self.assertIn("unix_home_path", {f["rule"] for f in report["findings"]})
        self.assertIn("mac_home_path", {f["rule"] for f in report["findings"]})
        self.assertNotIn("fixture", json.dumps(report))

    def test_utf16_binary_path_and_private_key_marker_are_detected(self):
        self.make_apk([("classes.dex", b"\x81" + "/Users/fixture/build/".encode("utf-16le")
                        + b"\x00" + b"-----BEGIN PRIVATE KEY-----")])
        rules = {f["rule"] for f in self.report()["findings"]}
        self.assertIn("mac_home_path", rules)
        self.assertIn("private_key_marker", rules)

    def test_path_prefixes_include_terminal_names_spaces_and_json_escapes(self):
        samples = (
            (b"/Users/fixture", "mac_home_path"),
            (b"/home/fixture", "unix_home_path"),
            (b"C:\\Users\\fixture", "windows_home_path"),
            (b"/Users/John Doe/project", "mac_home_path"),
            (b"\\/Users\\/fixture\\/project", "mac_home_path"),
        )
        for data, rule in samples:
            with self.subTest(data=data):
                self.make_apk([("classes.dex", data)])
                self.assertIn(rule, {f["rule"] for f in self.report()["findings"]})

    def test_png_compressed_text_metadata_is_scanned(self):
        def chunk(kind, data):
            return (len(data).to_bytes(4, "big") + kind + data
                    + (binascii.crc32(kind + data) & 0xFFFFFFFF).to_bytes(4, "big"))
        png = (b"\x89PNG\r\n\x1a\n"
               + chunk(b"IHDR", b"\x00\x00\x00\x01\x00\x00\x00\x01\x08\x02\x00\x00\x00")
               + chunk(b"zTXt", b"Description\x00\x00" + zlib.compress(b"/Users/fixture/private"))
               + chunk(b"IDAT", zlib.compress(b"\x00\x00\x00\x00"))
               + chunk(b"IEND", b""))
        self.make_apk([("res/a.png", png)])
        self.assertIn("mac_home_path", {f["rule"] for f in self.report()["findings"]})
        self.assertIn("metadata or unknown", self.report()["error"])
        clean_png = (b"\x89PNG\r\n\x1a\n"
                     + chunk(b"IHDR", b"\x00\x00\x00\x01\x00\x00\x00\x01\x08\x02\x00\x00\x00")
                     + chunk(b"IDAT", zlib.compress(b"\x00\x00\x00\x00"))
                     + chunk(b"IEND", b""))
        self.make_apk([("res/a.png", clean_png)])
        self.assertEqual("pass", self.report()["status"])
        malformed = clean_png[:-1] + b"X"
        self.make_apk([("res/a.png", malformed)])
        self.assertIn("PNG chunk CRC", self.report()["error"])
        unknown = (b"\x89PNG\r\n\x1a\n"
                   + chunk(b"IHDR", b"\x00\x00\x00\x01\x00\x00\x00\x01\x08\x02\x00\x00\x00")
                   + chunk(b"vpAg", zlib.compress(b"/Users/fixture/private"))
                   + chunk(b"IDAT", zlib.compress(b"\x00\x00\x00\x00"))
                   + chunk(b"IEND", b""))
        self.make_apk([("res/a.png", unknown)])
        self.assertIn("metadata or unknown", self.report()["error"])

    def test_apk_filename_is_checked_without_scanning_local_parent_path(self):
        self.apk = self.folder / "private-marker-release.apk"
        deny = self.folder / "deny.txt"
        deny.write_text("private-marker\n")
        self.make_apk([("classes.dex", b"safe")])
        report = self.report(deny)
        self.assertEqual("fail", report["status"])
        self.assertIn("local_deny_1", {f["rule"] for f in report["findings"]})
        self.assertNotIn("private-marker", json.dumps(report))

    def test_apk_mutation_between_scan_and_report_fails(self):
        self.make_apk([("classes.dex", b"safe")])
        original_scan = scanner.Scanner.scan_archive

        def mutate_after_scan(instance, source, depth=0):
            original_scan(instance, source, depth)
            self.make_apk([("classes.dex", b"changed")])

        with mock.patch.object(scanner.Scanner, "scan_archive", mutate_after_scan):
            report = self.report()
        self.assertEqual("fail", report["status"])
        self.assertIn("changed during", report["error"])

    def test_local_deny_term_is_private_and_blocks_release(self):
        deny = self.folder / "deny.txt"
        deny.write_text("internal-marker-xyz\n")
        self.make_apk([("classes.dex", b"internal-marker-xyz")])
        report = self.report(deny)
        self.assertEqual("fail", report["status"])
        self.assertIn("local_deny_1", {f["rule"] for f in report["findings"]})
        self.assertNotIn("internal-marker-xyz", json.dumps(report))

    def test_nested_zip_is_scanned(self):
        nested = io.BytesIO()
        with zipfile.ZipFile(nested, "w", zipfile.ZIP_DEFLATED) as jar:
            jar.writestr("payload.txt", b"github_pat_AAAAAAAAAAAAAAAAAAAAAAA")
        self.make_apk([("assets/internal.jar", nested.getvalue())])
        self.assertIn("github_token", {f["rule"] for f in self.report()["findings"]})

    def test_unsafe_and_ambiguous_archives_fail_closed(self):
        for name in ("../secret.txt", "/secret.txt", "C:\\Users\\fixture\\secret.txt"):
            self.make_apk([(name, b"safe")])
            self.assertIn("unsafe", self.report()["error"])
        self.make_apk([("same.txt", b"one"), ("same.txt", b"two")])
        self.assertIn("duplicate", self.report()["error"])
        self.make_apk([("assets/hidden.gz", b"not really gzip")])
        self.assertIn("unsupported", self.report()["error"])
        self.make_apk([("assets/folder/", b"/Users/fixture/private/")])
        self.assertIn("directory archive entry contains payload", self.report()["error"])

    def test_symlink_corruption_and_limits_fail_closed(self):
        link = zipfile.ZipInfo("asset-link")
        link.create_system = 3
        link.external_attr = (stat.S_IFLNK | 0o777) << 16
        self.make_apk([(link, b"target")])
        self.assertIn("symlink", self.report()["error"])
        self.apk.write_bytes(b"not a ZIP")
        self.assertIn("malformed", self.report()["error"])
        self.make_apk([("huge.bin", b"x" * 32)])
        with mock.patch.object(scanner, "MAX_ENTRY", 16):
            self.assertIn("size limit", self.report()["error"])

    def test_comment_and_raw_apk_bytes_are_scanned(self):
        self.make_apk([("normal.txt", b"ordinary")], comment=b"/Users/fixture/build/")
        self.assertIn("mac_home_path", {f["rule"] for f in self.report()["findings"]})

    def test_invalid_source_sha_and_deny_file_fail(self):
        self.make_apk([("normal.txt", b"ordinary")])
        with self.assertRaises(scanner.ScanFailure):
            scanner.run(self.apk, "short", None)
        with self.assertRaises(scanner.ScanFailure):
            scanner.run(self.apk, SHA, self.folder / "missing-deny.txt")

    def test_release_identity_requires_exact_cert_package_and_version(self):
        self.make_apk([("classes.dex", b"public app")])
        deny = self.folder / "deny.txt"
        deny.write_text("# Explicitly reviewed; no project-specific terms in this fixture.\n")
        signer = subprocess.CompletedProcess([], 0,
            f"Signer #1 certificate SHA-256 digest: {scanner.RELEASE_CERT_SHA256}\n", "")
        badging = subprocess.CompletedProcess([], 0,
            "package: name='com.chillednems.ikemenlab' versionCode='7' versionName='0.7.0'\n", "")
        with mock.patch.object(scanner.subprocess, "run", side_effect=[signer, badging]):
            report = scanner.run(self.apk, SHA, deny, True, "7", "0.7.0")
        self.assertEqual("pass", report["status"])
        self.assertEqual(scanner.RELEASE_CERT_SHA256,
                         report["release_identity"]["signer_certificate_sha256"])
        with mock.patch.object(scanner.subprocess, "run", side_effect=[signer, badging]):
            report = scanner.run(self.apk, SHA, deny, True, "8", "0.7.0")
        self.assertEqual("fail", report["status"])
        self.assertIn("package or version", report["error"])
        wrong_signer = subprocess.CompletedProcess([], 0,
            f"Signer #1 certificate SHA-256 digest: {'0' * 64}\n", "")
        with mock.patch.object(scanner.subprocess, "run", side_effect=[wrong_signer, badging]):
            report = scanner.run(self.apk, SHA, deny, True, "7", "0.7.0")
        self.assertEqual("fail", report["status"])
        self.assertIn("certificate", report["error"])
        self.assertRaises(scanner.ScanFailure, scanner.run, self.apk, SHA, None, True, "7", "0.7.0")


if __name__ == "__main__":
    unittest.main()
