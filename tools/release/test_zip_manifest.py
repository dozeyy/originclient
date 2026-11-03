#!/usr/bin/env python3
"""Tests for zip_manifest.py (build-check runs this on every push).

The launcher's delta-update tests (src/OriginLauncher.Tests/DeltaUpdateTests.cs) read a fixture zip and the manifest
THIS script wrote for it, so the two sides agree on one format. The test below rebuilds that manifest from the
committed zip and fails if it changed: edit zip_manifest.py, then refresh the fixture with
    python3 tools/release/test_zip_manifest.py --write-fixture
"""
from __future__ import annotations

import hashlib
import io
import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import zip_manifest  # noqa: E402

REPO = Path(__file__).resolve().parents[2]
FIXTURES = REPO / "src/OriginLauncher.Tests/Fixtures"
FIXTURE_ZIP = FIXTURES / "delta-release.zip"
FIXTURE_MANIFEST = FIXTURES / "delta-release.files.json"
STAMP = (2026, 11, 3, 0, 0, 0)


def fixture_bytes() -> bytes:
    """A small stand-in for the launcher zip: a big deflated exe, a stored jar, a deflated jar, text, an empty file and
    an empty folder. Deterministic content (hash-based bytes, fixed timestamps)."""
    def noise(n: int, tag: bytes) -> bytes:
        out = bytearray()
        i = 0
        while len(out) < n:
            out += hashlib.sha256(tag + i.to_bytes(4, "big")).digest()
            i += 1
        return bytes(out[:n])
    alphabet = b"ABCDEFGHIJKLMNOP\0\1\2\3"
    semi = bytes(alphabet[b % len(alphabet)] for b in noise(120_000, b"exe"))
    text = ("<doc>" + "".join(f"<member name='M:{i}'>summary {i}</member>" for i in range(500)) + "</doc>").encode()
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as zf:
        def add(name: str, data: bytes, method: int) -> None:
            info = zipfile.ZipInfo(name, STAMP)
            info.compress_type = method
            zf.writestr(info, data)
        add("OriginLauncher.App.exe", semi, zipfile.ZIP_DEFLATED)
        add("Bundled/OriginClient/originclient-1.21.1.jar", noise(60_000, b"jar1"), zipfile.ZIP_STORED)
        add("Bundled/OriginClient/originclient-26.2.jar", noise(60_000, b"jar2"), zipfile.ZIP_DEFLATED)
        add("Microsoft.Web.WebView2.Core.xml", text, zipfile.ZIP_DEFLATED)
        add("empty.txt", b"", zipfile.ZIP_DEFLATED)
        zf.writestr(zipfile.ZipInfo("Bundled/Empty/", STAMP), b"")
    return buf.getvalue()


class ZipManifestTests(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp())
        self.zip = self.tmp / "OriginLauncher-win-x64.zip"
        self.zip.write_bytes(fixture_bytes())

    def test_every_entry_is_described_and_decodes_from_its_offset(self) -> None:
        m = zip_manifest.build(self.zip)
        self.assertEqual(m["format"], 1)
        self.assertEqual(m["zip"], "OriginLauncher-win-x64.zip")
        self.assertEqual(m["zipSize"], self.zip.stat().st_size)
        self.assertEqual(m["zipSha256"], hashlib.sha256(self.zip.read_bytes()).hexdigest())
        self.assertEqual(m["dirs"], ["Bundled/Empty"])
        by = {f["path"]: f for f in m["files"]}
        self.assertEqual(set(by), {"OriginLauncher.App.exe", "Bundled/OriginClient/originclient-1.21.1.jar",
                                   "Bundled/OriginClient/originclient-26.2.jar", "Microsoft.Web.WebView2.Core.xml",
                                   "empty.txt"})
        self.assertEqual(by["Bundled/OriginClient/originclient-1.21.1.jar"]["method"], 0)
        self.assertEqual(by["OriginLauncher.App.exe"]["method"], 8)
        self.assertEqual(by["empty.txt"]["size"], 0)
        raw = self.zip.read_bytes()
        with zipfile.ZipFile(self.zip) as zf:
            for f in m["files"]:
                data = raw[f["offset"]:f["offset"] + f["csize"]]
                if f["method"] == 8:
                    import zlib
                    data = zlib.decompress(data, -15)
                self.assertEqual(data, zf.read(f["path"]))
                self.assertEqual(hashlib.sha256(data).hexdigest(), f["sha256"])
        offsets = [f["offset"] for f in m["files"]]
        self.assertEqual(offsets, sorted(offsets))

    def test_write_makes_the_list_and_its_sidecar(self) -> None:
        out = zip_manifest.write(self.zip)
        self.assertEqual(out.name, "OriginLauncher-win-x64.files.json")
        side = out.with_name(out.name + ".sha256").read_text(encoding="ascii")
        self.assertEqual(side, f"{hashlib.sha256(out.read_bytes()).hexdigest()}  OriginLauncher-win-x64.files.json\n")

    def test_backslash_names_become_forward_slashes(self) -> None:
        z = self.tmp / "b.zip"
        with zipfile.ZipFile(z, "w") as zf:
            zf.writestr(zipfile.ZipInfo("Bundled\\a.jar", STAMP), b"x" * 10)
        self.assertEqual(zip_manifest.build(z)["files"][0]["path"], "Bundled/a.jar")

    def test_unsafe_paths_and_duplicates_fail_the_run(self) -> None:
        for names in (["../evil.dll"], ["/abs.dll"], ["C:/x.dll"], ["a.dll", "A.dll"], ["Bundled/NUL.txt"], ["com1"],
                      ["x.dll."]):
            z = self.tmp / "bad.zip"
            with zipfile.ZipFile(z, "w") as zf:
                for n in names:
                    zf.writestr(zipfile.ZipInfo(n, STAMP), b"x")
            with self.assertRaises(SystemExit, msg=str(names)):
                zip_manifest.build(z)

    def test_committed_fixture_matches_this_script(self) -> None:
        # The committed zip is the truth (zlib versions compress differently); its manifest must be what this script says.
        built = zip_manifest.build(FIXTURE_ZIP, "OriginLauncher-win-x64.zip")
        self.assertEqual(json.loads(FIXTURE_MANIFEST.read_text(encoding="ascii")), built,
                         "fixture manifest is stale: run with --write-fixture")


def write_fixture() -> None:
    FIXTURE_ZIP.write_bytes(fixture_bytes())
    m = zip_manifest.build(FIXTURE_ZIP, "OriginLauncher-win-x64.zip")
    FIXTURE_MANIFEST.write_text(json.dumps(m, separators=(",", ":")) + "\n", encoding="ascii")
    print(f"wrote {FIXTURE_ZIP} and {FIXTURE_MANIFEST}")


if __name__ == "__main__":
    if "--write-fixture" in sys.argv:
        write_fixture()
    else:
        unittest.main()
