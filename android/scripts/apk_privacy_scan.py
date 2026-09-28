#!/usr/bin/env python3
"""Fail-closed, bounded privacy scan of an APK before sharing it.

The JSON report deliberately contains rule IDs and hashed entry names, never
matched text or private deny terms. This is a leak check, not malware analysis.
"""

import argparse
import binascii
import hashlib
import io
import json
import re
import stat
import subprocess
import sys
import zipfile
import zlib
from pathlib import Path

POLICY_VERSION = 1
RELEASE_CERT_SHA256 = "76ef17eeb19df295b64b8a22f3c31b770e3a56aa050fc49fa7cbf437e2830998"
PACKAGE_ID = "com.chillednems.ikemenlab"
MAX_APK = 1024 * 1024 * 1024
MAX_TOTAL_EXPANDED = 1024 * 1024 * 1024
MAX_ENTRY = 256 * 1024 * 1024
MAX_NESTED = 32 * 1024 * 1024
MAX_PNG_METADATA = 4 * 1024 * 1024
MAX_ENTRIES = 20000
MAX_DEPTH = 2
MAX_RATIO = 500
CHUNK = 64 * 1024
OVERLAP = 2048

RULES = {
    "mac_home_path": rb"/Users/",
    "unix_home_path": rb"/home/",
    "windows_home_path": rb"[A-Za-z]:[/\\]Users[/\\]",
    "mac_private_temp_path": rb"/(?:private/)?var/folders/",
    "private_key_marker": rb"-----BEGIN (?:RSA |EC |OPENSSH |DSA |ENCRYPTED )?PRIVATE KEY-----",
    "github_token": rb"(?:github_pat_[A-Za-z0-9_]{12,}|gh[pousr]_[A-Za-z0-9_]{16,})",
    "aws_access_key": rb"(?:AKIA|ASIA)[A-Z0-9]{16}",
    "gitlab_token": rb"glpat-[A-Za-z0-9_-]{16,}",
    "credential_assignment": rb"(?:password|passwd|secret|access[_-]?token|api[_-]?key)\s*[=:]\s*['\"]?[^\s'\";]{8,}",
}
NESTED_SUFFIXES = (".zip", ".jar", ".aar", ".apk")
UNSUPPORTED_SUFFIXES = (".tar", ".tgz", ".gz", ".svgz", ".bz2", ".xz", ".7z", ".rar", ".zst")
PNG_SAFE_CHUNKS = {
    b"IHDR", b"PLTE", b"IDAT", b"IEND", b"tRNS", b"gAMA", b"sRGB",
    b"sBIT", b"cHRM", b"pHYs", b"bKGD",
}


class ScanFailure(Exception):
    pass


class Scanner:
    def __init__(self, deny_terms):
        self.patterns = [(name, re.compile(expr, re.IGNORECASE)) for name, expr in RULES.items()]
        for number, term in enumerate(deny_terms, 1):
            self.patterns.append((f"local_deny_{number}", re.compile(re.escape(term), re.IGNORECASE)))
        self.findings = []
        self.seen = set()
        self.expanded = 0
        self.entries = 0

    def finding(self, rule, scope):
        key = (rule, scope)
        if key not in self.seen:
            self.seen.add(key)
            if len(self.findings) >= 1000:
                raise ScanFailure("too many findings to report safely")
            self.findings.append({"rule": rule, "scope": scope})

    def scan_bytes(self, block, scope):
        # Scan ASCII/UTF-8 bytes and both UTF-16 alignments. DEX and resources
        # retain strings in these encodings even when the file is not textual.
        candidates = [block]
        for offset in (0, 1):
            pairs = block[offset:]
            pairs = pairs[:len(pairs) & ~1]
            for zero_index, ascii_index in ((1, 0), (0, 1)):
                # Non-ASCII code units separate strings, preventing random
                # binary from being treated as one long textual value.
                candidates.append(bytes(pairs[i + ascii_index] if pairs[i + zero_index] == 0
                                        and 32 <= pairs[i + ascii_index] <= 126 else 0
                                        for i in range(0, len(pairs), 2)))
        normalized = [candidate.replace(b"\\/", b"/").replace(b"\\\\", b"\\")
                      for candidate in candidates]
        for rule, pattern in self.patterns:
            if any(pattern.search(candidate) for candidate in normalized):
                self.finding(rule, scope)

    def scan_png(self, content, scope):
        if not content.startswith(b"\x89PNG\r\n\x1a\n"):
            raise ScanFailure("invalid PNG content")
        position = 8
        ended = False
        while position + 12 <= len(content):
            length = int.from_bytes(content[position:position + 4], "big")
            kind = content[position + 4:position + 8]
            end = position + 12 + length
            if end > len(content):
                raise ScanFailure("malformed PNG metadata")
            payload = content[position + 8:position + 8 + length]
            crc = int.from_bytes(content[position + 8 + length:end], "big")
            if binascii.crc32(kind + payload) & 0xFFFFFFFF != crc:
                raise ScanFailure("PNG chunk CRC mismatch")
            if kind == b"eXIf":
                raise ScanFailure("unsupported embedded PNG EXIF metadata")
            compressed = None
            if kind in (b"zTXt", b"iCCP"):
                separator = payload.find(b"\x00")
                if separator < 0 or separator + 1 >= len(payload) or payload[separator + 1] != 0:
                    raise ScanFailure("malformed compressed PNG metadata")
                compressed = payload[separator + 2:]
            elif kind == b"iTXt":
                keyword_end = payload.find(b"\x00")
                if keyword_end < 0 or keyword_end + 2 >= len(payload):
                    raise ScanFailure("malformed international PNG metadata")
                flag = payload[keyword_end + 1]
                method = payload[keyword_end + 2]
                remainder = payload[keyword_end + 3:]
                language_end = remainder.find(b"\x00")
                translated_end = remainder.find(b"\x00", language_end + 1)
                if language_end < 0 or translated_end < 0 or flag not in (0, 1):
                    raise ScanFailure("malformed international PNG metadata")
                if flag == 1:
                    if method != 0:
                        raise ScanFailure("unsupported PNG metadata compression")
                    compressed = remainder[translated_end + 1:]
            if compressed is not None:
                if len(compressed) > MAX_PNG_METADATA:
                    raise ScanFailure("PNG metadata size limit exceeded")
                try:
                    decompressor = zlib.decompressobj()
                    expanded = decompressor.decompress(compressed, MAX_PNG_METADATA + 1)
                except zlib.error as exc:
                    raise ScanFailure("invalid compressed PNG metadata") from exc
                if len(expanded) > MAX_PNG_METADATA or not decompressor.eof or decompressor.unused_data:
                    raise ScanFailure("PNG metadata expansion limit or trailing data")
                self.scan_bytes(expanded, scope)
            if kind not in PNG_SAFE_CHUNKS:
                raise ScanFailure("PNG metadata or unknown chunk requires manual review")
            position = end
            if kind == b"IEND":
                ended = True
                break
        if not ended or position != len(content):
            raise ScanFailure("malformed PNG end marker or trailing data")

    def scan_stream(self, stream, scope, limit, capture=False):
        size = 0
        tail = b""
        captured = bytearray() if capture else None
        while True:
            chunk = stream.read(CHUNK)
            if not chunk:
                break
            size += len(chunk)
            if size > limit:
                raise ScanFailure("entry or file exceeds scan size limit")
            self.scan_bytes(tail + chunk, scope)
            tail = (tail + chunk)[-OVERLAP:]
            if captured is not None:
                captured.extend(chunk)
        return bytes(captured) if captured is not None else size

    def scan_archive(self, source, depth=0):
        if depth > MAX_DEPTH:
            raise ScanFailure("nested archive depth exceeded")
        try:
            archive = zipfile.ZipFile(source)
            infos = archive.infolist()
        except (zipfile.BadZipFile, OSError, ValueError) as exc:
            raise ScanFailure("malformed ZIP/APK archive") from exc
        try:
            names = set()
            for info in infos:
                self.entries += 1
                if self.entries > MAX_ENTRIES:
                    raise ScanFailure("too many archive entries")
                name = info.filename
                normalized = name.replace("\\", "/")
                if (not name or "\x00" in name or normalized.startswith("/")
                        or re.match(r"^[A-Za-z]:", normalized)
                        or any(part in ("", ".", "..") for part in normalized.rstrip("/").split("/"))):
                    raise ScanFailure("unsafe archive entry name")
                if normalized in names:
                    raise ScanFailure("duplicate archive entry name")
                names.add(normalized)
                if info.flag_bits & 1:
                    raise ScanFailure("encrypted archive entry")
                mode = (info.external_attr >> 16) & 0xFFFF
                file_type = stat.S_IFMT(mode)
                if file_type == stat.S_IFLNK:
                    raise ScanFailure("symlink archive entry")
                if file_type not in (0, stat.S_IFREG, stat.S_IFDIR):
                    raise ScanFailure("special-file archive entry")
                if info.file_size > MAX_ENTRY:
                    raise ScanFailure("archive entry exceeds size limit")
                if info.file_size > MAX_RATIO * max(info.compress_size, 1):
                    raise ScanFailure("archive entry exceeds compression-ratio limit")
                scope = f"entry:{self.entries}:{hashlib.sha256(name.encode('utf-8')).hexdigest()[:16]}"
                self.scan_bytes(name.encode("utf-8"), scope)
                self.scan_bytes(info.comment + info.extra, scope)
                if info.is_dir():
                    if info.file_size:
                        raise ScanFailure("directory archive entry contains payload")
                    continue
                lower = name.lower()
                nested = lower.endswith(NESTED_SUFFIXES)
                if lower.endswith(UNSUPPORTED_SUFFIXES):
                    raise ScanFailure("unsupported nested archive format")
                self.expanded += info.file_size
                if self.expanded > MAX_TOTAL_EXPANDED:
                    raise ScanFailure("expanded archive size limit exceeded")
                try:
                    with archive.open(info) as entry:
                        prefix = entry.read(8)
                        nested = nested or prefix.startswith(b"PK\x03\x04")
                        png = prefix.startswith(b"\x89PNG\r\n\x1a\n")
                        if prefix.startswith((b"\x1f\x8b", b"7z\xbc\xaf\x27\x1c")):
                            raise ScanFailure("unsupported nested archive format")
                        if nested and info.file_size > MAX_NESTED:
                            raise ScanFailure("nested archive exceeds size limit")
                        if png and info.file_size > MAX_NESTED:
                            raise ScanFailure("PNG metadata inspection size limit exceeded")
                        # Reopen to include the prefix in content and CRC checks.
                    with archive.open(info) as entry:
                        content = self.scan_stream(entry, scope, MAX_ENTRY, capture=nested or png)
                except (zipfile.BadZipFile, RuntimeError, EOFError, OSError) as exc:
                    raise ScanFailure("archive entry failed to decompress or verify") from exc
                actual_size = len(content) if isinstance(content, bytes) else content
                if actual_size != info.file_size:
                    raise ScanFailure("archive entry size differs from manifest")
                if nested:
                    self.scan_archive(io.BytesIO(content), depth + 1)
                elif png:
                    self.scan_png(content, scope)
        finally:
            archive.close()


def load_deny(path):
    if path is None:
        return []
    try:
        data = Path(path).read_bytes()
    except OSError as exc:
        raise ScanFailure("local deny file cannot be read") from exc
    if len(data) > 64 * 1024:
        raise ScanFailure("local deny file exceeds size limit")
    terms = []
    for line in data.splitlines():
        term = line.strip()
        if not term or term.startswith(b"#"):
            continue
        if len(term) < 3 or len(term) > 200:
            raise ScanFailure("local deny term has invalid length")
        terms.append(term)
    if len(terms) > 100:
        raise ScanFailure("too many local deny terms")
    return terms


def verify_release_identity(apk, version_code, version_name):
    if not version_code or not version_name:
        raise ScanFailure("signed release requires expected version code and name")
    try:
        signer = subprocess.run(
            ["apksigner", "verify", "--print-certs", str(apk)],
            capture_output=True, text=True, check=True, timeout=60)
        badging = subprocess.run(
            ["aapt", "dump", "badging", str(apk)],
            capture_output=True, text=True, check=True, timeout=60)
    except (FileNotFoundError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as exc:
        raise ScanFailure("signed APK identity tools failed or are unavailable") from exc
    fingerprints = re.findall(r"Signer #\d+ certificate SHA-256 digest:\s*([0-9a-fA-F]{64})", signer.stdout)
    if len(fingerprints) != 1 or fingerprints[0].lower() != RELEASE_CERT_SHA256:
        raise ScanFailure("signed APK certificate differs from recorded release certificate")
    package = re.search(r"^package:\s+name='([^']+)'\s+versionCode='([^']+)'\s+versionName='([^']+)'", badging.stdout, re.MULTILINE)
    if not package or package.groups() != (PACKAGE_ID, version_code, version_name):
        raise ScanFailure("signed APK package or version differs from expected release identity")
    return {"package_id": PACKAGE_ID, "version_code": version_code,
            "version_name": version_name, "signer_certificate_sha256": RELEASE_CERT_SHA256}


def run(apk, source_sha, deny_file, release_signed=False, version_code=None, version_name=None):
    if not re.fullmatch(r"[0-9a-f]{40}", source_sha):
        raise ScanFailure("source SHA must be a full lowercase Git commit SHA")
    if release_signed and deny_file is None:
        raise ScanFailure("signed release requires an explicit local deny file")
    start_stat = apk.stat()
    size = start_stat.st_size
    if size <= 0 or size > MAX_APK:
        raise ScanFailure("APK size outside supported bounds")
    scanner = Scanner(load_deny(deny_file))
    scanner.scan_bytes(apk.name.encode("utf-8"), f"apk_name:{hashlib.sha256(apk.name.encode('utf-8')).hexdigest()[:16]}")
    sha = hashlib.sha256()
    with apk.open("rb") as stream:
        while chunk := stream.read(CHUNK):
            sha.update(chunk)
    report = {
        "policy_version": POLICY_VERSION,
        "scanner_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        "source_sha": source_sha,
        "source_sha_basis": "caller-supplied build-source claim; compare with independent build record",
        "apk_sha256": sha.hexdigest(),
        "apk_bytes": size,
        "allowed_public_identity": [
            "com.chillednems.ikemenlab package identifier",
            "chillednems public project and signing identity",
        ],
        "local_deny_term_count": len(scanner.patterns) - len(RULES),
        "findings": [],
        "status": "fail",
    }
    try:
        with apk.open("rb") as stream:
            scanner.scan_stream(stream, "raw_apk", MAX_APK)
        scanner.scan_archive(apk)
        report["entries_scanned"] = scanner.entries
        report["expanded_bytes_scanned"] = scanner.expanded
        report["findings"] = scanner.findings
        report["status"] = "pass" if not scanner.findings else "fail"
        if release_signed:
            report["release_identity"] = verify_release_identity(apk, version_code, version_name)
        after_scan_sha = hashlib.sha256()
        with apk.open("rb") as stream:
            while chunk := stream.read(CHUNK):
                after_scan_sha.update(chunk)
        end_stat = apk.stat()
        if (after_scan_sha.digest() != sha.digest()
                or (start_stat.st_dev, start_stat.st_ino, start_stat.st_size, start_stat.st_mtime_ns)
                != (end_stat.st_dev, end_stat.st_ino, end_stat.st_size, end_stat.st_mtime_ns)):
            raise ScanFailure("APK changed during privacy scan")
    except ScanFailure as exc:
        report["findings"] = scanner.findings
        report["status"] = "fail"
        report["error"] = str(exc)
    return report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--source-sha", required=True)
    parser.add_argument("--report", required=True, type=Path)
    parser.add_argument("--local-deny-file", type=Path)
    parser.add_argument("--release-signed", action="store_true",
                        help="require recorded signer certificate and expected package/version")
    parser.add_argument("--expected-version-code")
    parser.add_argument("--expected-version-name")
    args = parser.parse_args(argv)
    try:
        report = run(args.apk, args.source_sha, args.local_deny_file,
                     args.release_signed, args.expected_version_code, args.expected_version_name)
    except OSError:
        report = {"policy_version": POLICY_VERSION, "status": "fail", "error": "input or report file unavailable"}
    except ScanFailure as exc:
        report = {"policy_version": POLICY_VERSION, "status": "fail", "error": str(exc)}
    try:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
    except OSError:
        print("APK privacy scan: fail (report unavailable)", file=sys.stderr)
        return 1
    print(f"APK privacy scan: {report['status']}")
    return 0 if report["status"] == "pass" else 1


if __name__ == "__main__":
    sys.exit(main())
