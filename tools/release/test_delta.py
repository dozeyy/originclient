#!/usr/bin/env python3
"""Tests for the self-update delta builder (tools/release/delta.py).

  python3 tools/release/test_delta.py

Real zips in temp folders; gh is a fake that serves prepared zips, so nothing
touches the network.
"""
from __future__ import annotations

import hashlib
import json
import shutil
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import delta  # noqa: E402


def make_zip(path: Path, files: dict[str, bytes]) -> Path:
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        for name, data in files.items():
            z.writestr(name, data)
    return path


def apply_delta(old_dir: Path, delta_zip: Path, out_dir: Path) -> None:
    """What the launcher does (UpdateDelta.Stage), in a few lines: every manifest file from the delta or the
    old folder, each checked against the manifest."""
    with zipfile.ZipFile(delta_zip) as z:
        manifest = json.loads(z.read(delta.MANIFEST))
        carried = {n[len(delta.FILES_PREFIX):] for n in z.namelist() if n.startswith(delta.FILES_PREFIX)}
        for f in manifest["files"]:
            data = z.read(delta.FILES_PREFIX + f["path"]) if f["path"] in carried else (old_dir / f["path"]).read_bytes()
            assert len(data) == f["size"] and hashlib.sha256(data).hexdigest() == f["sha256"], f["path"]
            dest = out_dir / f["path"]
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_bytes(data)


OLD = {
    "OriginLauncher.App.exe": b"exe v1" * 1000,
    "Bundled/OriginClient/originclient-1.21.1.jar": b"jar 1.21.1 same" * 500,
    "Bundled/OriginClient/originclient-1.20.jar": b"jar 1.20 old" * 500,
    "gone.dll": b"removed in v2",
}
NEW = {
    "OriginLauncher.App.exe": b"exe v2" * 1000,
    "Bundled/OriginClient/originclient-1.21.1.jar": b"jar 1.21.1 same" * 500,
    "Bundled/OriginClient/originclient-1.20.jar": b"jar 1.20 new" * 500,
    "Bundled/OriginClient/originclient-26.3.jar": b"added in v2" * 500,
}


class BuildDelta(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, True)

    def test_carries_only_changed_and_added_files_and_lists_every_new_file(self):
        old = make_zip(self.tmp / "old.zip", OLD)
        new = make_zip(self.tmp / "new.zip", NEW)
        d = delta.build_delta(old, new, self.tmp / "d.zip", "1.0.1", "1.0.2")

        self.assertEqual(d.changed, ["Bundled/OriginClient/originclient-1.20.jar",
                                     "Bundled/OriginClient/originclient-26.3.jar", "OriginLauncher.App.exe"])
        self.assertEqual(d.deleted, ["gone.dll"])
        with zipfile.ZipFile(d.path) as z:
            names = set(z.namelist())
            manifest = json.loads(z.read(delta.MANIFEST))
        self.assertEqual(names, {delta.MANIFEST, *(delta.FILES_PREFIX + p for p in d.changed)})
        self.assertNotIn(delta.FILES_PREFIX + "Bundled/OriginClient/originclient-1.21.1.jar", names)
        self.assertEqual((manifest["format"], manifest["from"], manifest["to"]), (1, "1.0.1", "1.0.2"))
        self.assertEqual({f["path"]: (f["size"], f["sha256"]) for f in manifest["files"]},
                         {p: (len(b), hashlib.sha256(b).hexdigest()) for p, b in NEW.items()})
        self.assertEqual(manifest["deleted"], ["gone.dll"])
        self.assertFalse((self.tmp / "d.zip.part").exists())

    def test_old_folder_plus_delta_is_exactly_the_new_release(self):
        old = make_zip(self.tmp / "old.zip", OLD)
        new = make_zip(self.tmp / "new.zip", NEW)
        d = delta.build_delta(old, new, self.tmp / "d.zip", "1.0.1", "1.0.2")
        installed = self.tmp / "installed"
        with zipfile.ZipFile(old) as z:
            z.extractall(installed)
        out = self.tmp / "staged"
        apply_delta(installed, d.path, out)
        staged = {p.relative_to(out).as_posix(): p.read_bytes() for p in out.rglob("*") if p.is_file()}
        self.assertEqual(staged, NEW)

    def test_backslash_names_from_windows_powershell_are_the_same_files(self):
        old = make_zip(self.tmp / "old.zip", {k.replace("/", "\\"): v for k, v in OLD.items()})
        new = make_zip(self.tmp / "new.zip", NEW)
        d = delta.build_delta(old, new, self.tmp / "d.zip", "1.0.1", "1.0.2")
        self.assertNotIn("Bundled/OriginClient/originclient-1.21.1.jar", d.changed)
        self.assertEqual(len(d.changed), 3)

    def test_unsafe_or_case_duplicate_paths_are_refused(self):
        old = make_zip(self.tmp / "old.zip", OLD)
        for bad in ({"../evil.dll": b"x"}, {"a.dll": b"1", "A.dll": b"2"}, {"C:/x.dll": b"x"}):
            new = make_zip(self.tmp / "bad.zip", bad)
            with self.assertRaises(ValueError, msg=str(bad)):
                delta.build_delta(old, new, self.tmp / "d.zip", "1", "2")

    def test_sidecar_is_sha256sum_format_of_the_final_file(self):
        f = self.tmp / delta.delta_name("1.0.1")
        f.write_bytes(b"payload")
        side = delta.write_sidecar(f)
        self.assertEqual(side.read_text(), f"{hashlib.sha256(b'payload').hexdigest()}  {f.name}\n")


class OlderReleases(unittest.TestCase):
    RELEASES = [
        {"tagName": "launcher-v1.0.84", "isDraft": False, "isPrerelease": False},
        {"tagName": "launcher-v1.0.83", "isDraft": False, "isPrerelease": False},
        {"tagName": "launcher-v1.0.82", "isDraft": True, "isPrerelease": False},
        {"tagName": "launcher-v1.0.81", "isDraft": False, "isPrerelease": True},
        {"tagName": "launcher-v1.0.80", "isDraft": False, "isPrerelease": False},
        {"tagName": "launcher-v1.0.9", "isDraft": False, "isPrerelease": False},
        {"tagName": "other-v9.9.9", "isDraft": False, "isPrerelease": False},
        {"tagName": "launcher-v1.0.78", "isDraft": False, "isPrerelease": False},
    ]

    def test_the_three_newest_published_releases_older_than_the_tag(self):
        self.assertEqual(delta.older_releases(self.RELEASES, "launcher-v1.0.84"),
                         ["launcher-v1.0.83", "launcher-v1.0.80", "launcher-v1.0.78"])

    def test_a_newer_release_already_published_is_never_a_base(self):
        self.assertEqual(delta.older_releases(self.RELEASES, "launcher-v1.0.81", keep=5),
                         ["launcher-v1.0.80", "launcher-v1.0.78", "launcher-v1.0.9"])

    def test_a_rehearsal_takes_the_newest_published(self):
        self.assertEqual(delta.older_releases(self.RELEASES, "next"),
                         ["launcher-v1.0.84", "launcher-v1.0.83", "launcher-v1.0.80"])


class FakeGh:
    """Serves prepared release zips. zips: {tag: (zip bytes or None, sidecar text or None)}; a tag in
    `broken` raises like a failed gh call."""

    def __init__(self, releases, zips, broken=()):
        self.releases, self.zips, self.broken = releases, zips, set(broken)

    def __call__(self, args):
        if args[:2] == ["release", "list"]:
            return json.dumps([{"tagName": t, "isDraft": False, "isPrerelease": False} for t in self.releases])
        assert args[:2] == ["release", "download"], args
        tag, out = args[2], Path(args[args.index("--dir") + 1])
        if tag in self.broken:
            raise RuntimeError("gh: HTTP 502")
        data, side = self.zips[tag]
        if data is not None:
            (out / delta.ASSET).write_bytes(data)
        if side is not None:
            (out / (delta.ASSET + ".sha256")).write_text(side)
        return ""


class ReleaseDeltas(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, True)
        # A big incompressible file that stays the same, so a small change is well under half the full size.
        big = bytes(hashlib.sha256(str(i).encode()).digest()[0] for i in range(200_000))
        self.new = make_zip(self.tmp / delta.ASSET, {**NEW, "runtime.dll": big})
        self.old_small_change = make_zip(self.tmp / "o1.zip", {**OLD, "runtime.dll": big})
        self.old_all_changed = make_zip(self.tmp / "o2.zip", {"runtime.dll": b"different"})

    def out(self):
        return self.tmp / "out"

    @staticmethod
    def sidecar(p: Path) -> str:
        return f"{hashlib.sha256(p.read_bytes()).hexdigest()}  {delta.ASSET}\n"

    def test_writes_a_delta_and_its_hash_for_a_small_change(self):
        gh = FakeGh(["launcher-v1.0.2", "launcher-v1.0.1"],
                    {"launcher-v1.0.1": (self.old_small_change.read_bytes(), self.sidecar(self.old_small_change))})
        names = delta.release_deltas(self.new, "launcher-v1.0.2", self.out(), gh)
        self.assertEqual(names, ["OriginLauncher-win-x64.delta-from-1.0.1.zip"])
        d = self.out() / names[0]
        side = (self.out() / (names[0] + ".sha256")).read_text()
        self.assertEqual(side, f"{hashlib.sha256(d.read_bytes()).hexdigest()}  {names[0]}\n")
        with zipfile.ZipFile(d) as z:
            self.assertEqual(json.loads(z.read(delta.MANIFEST))["to"], "1.0.2")
            self.assertNotIn(delta.FILES_PREFIX + "runtime.dll", z.namelist())

    def test_a_delta_over_half_the_full_size_is_not_published(self):
        gh = FakeGh(["launcher-v1.0.1"], {"launcher-v1.0.1": (self.old_all_changed.read_bytes(), None)})
        self.assertEqual(delta.release_deltas(self.new, "launcher-v1.0.2", self.out(), gh), [])
        self.assertFalse(self.out().exists() and any(self.out().iterdir()))

    def test_problems_with_one_release_skip_only_that_delta_and_never_raise(self):
        good = self.old_small_change.read_bytes()
        gh = FakeGh(["launcher-v1.0.1", "launcher-v1.0.0", "launcher-v0.9.0"],
                    {"launcher-v1.0.1": (good, "0" * 64 + "  " + delta.ASSET),   # zip fails its own .sha256
                     "launcher-v1.0.0": (None, None),                           # release without the zip
                     },
                    broken={"launcher-v0.9.0"})                                 # download error
        self.assertEqual(delta.release_deltas(self.new, "launcher-v1.0.2", self.out(), gh), [])
        self.assertFalse(self.out().exists() and any(self.out().iterdir()))

    def test_an_old_release_without_a_sidecar_is_still_a_base(self):
        gh = FakeGh(["launcher-v1.0.1"], {"launcher-v1.0.1": (self.old_small_change.read_bytes(), None)})
        self.assertEqual(len(delta.release_deltas(self.new, "launcher-v1.0.2", self.out(), gh)), 1)


if __name__ == "__main__":
    unittest.main(verbosity=1)
