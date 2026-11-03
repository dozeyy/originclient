#!/usr/bin/env python3
"""Build the small self-update downloads ("deltas") for a launcher release.

Why: every self-update used to download the whole OriginLauncher-win-x64.zip
(~250 MB: the launcher exe with its .NET runtime, plus every bundled mod jar),
even when only a few of those files changed. A delta from an older release
carries only the files that changed, and an installed launcher of exactly that
older version rebuilds the new release from its own folder plus the delta
(Core/Updates/UpdateDelta.cs). The full zip stays published and is always the
fallback: a delta is only ever an optimisation, never the only way to update.

One delta per older release, for the last KEEP published releases (Will keeps
the last 3), published as:
  OriginLauncher-win-x64.delta-from-<old version>.zip
  OriginLauncher-win-x64.delta-from-<old version>.zip.sha256   (sha256sum format,
      written after the delta is final, exactly like the full zip's)

Delta layout (format 1):
  origin-delta.json   the manifest: EVERY file of the new release (path, size,
                      sha256), the old and new versions, and the paths the new
                      release no longer has ("deleted", for people reading it;
                      the launcher's swap never deletes, same as the full zip)
  files/<path>        a file that is new, or changed and not worth patching: the whole file
  patches/<path>      a changed file as a binary patch against the SAME path in the old release
                      (only when the patch is smaller than the file; format below)

Each file of the new release has at most one of files/ or patches/; a file with neither is unchanged and the
launcher copies it from its own folder.

Patch format (ODPATCH1, every number little-endian), rsync style:
  "ODPATCH1"  u64 old file size  u64 new file size
  then ops, each one byte + fields:
    "C" u64 offset u32 length   COPY length bytes of the old file from offset
    "L" u32 length <bytes>      LITERAL: these bytes as they are
    "E"                         END: nothing may follow, and the ops must have written exactly the new size
The old file is cut into fixed BLOCK-byte blocks, each indexed by a weak rolling checksum (rsync's two sums); the
new file is scanned byte by byte with the same rolling checksum, a checksum hit is confirmed by comparing the bytes
themselves (stronger than any strong hash), and a confirmed match is grown forward and back as far as the bytes
agree. So content that only moved (a jar entry shifted by an edit before it, a runtime file at a new offset inside
the single-file exe) still ships as COPY ops. Applying is a plain stream of copies and literals (UpdateDelta.ApplyPatch);
every patch is applied here once before it is written, so a patch that would not rebuild its file is never shipped.

The launcher trusts nothing in the delta but the manifest (and the manifest only
because the delta matched its published .sha256). Every staged file, whether it
came from the delta or from the installed folder, is checked against the
manifest before the swap; any mismatch falls back to the full zip. So an old zip
that is damaged here can only make a delta useless, never make an install wrong.

A delta is NOT published when it can't be built (old release missing its zip,
download error, its zip fails its own .sha256) or when it would be more than
MAX_RATIO of the full zip (then the full zip is barely bigger and one less
moving part is used). None of this can fail the release: errors are printed and
that delta is skipped.

Usage:
  # The release job (lists + downloads the older releases with gh):
  python3 tools/release/delta.py release --new-zip OriginLauncher-win-x64.zip \
      --tag launcher-v1.0.85 --out .
  # By hand, two local zips:
  python3 tools/release/delta.py build OLD.zip NEW.zip OUT.zip --from 1.0.84 --to 1.0.85
Tests: python3 tools/release/test_delta.py
"""
from __future__ import annotations

import argparse
import hashlib
import itertools
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

ASSET = "OriginLauncher-win-x64.zip"
ZIP_PREFIX = "OriginLauncher-win-x64"
TAG_PREFIX = "launcher-v"
MANIFEST = "origin-delta.json"
FILES_PREFIX = "files/"
PATCHES_PREFIX = "patches/"
PATCH_MAGIC = "ODPATCH1"
OP_COPY = "C"
OP_LITERAL = "L"
OP_END = "E"
# Patch block size: small enough that an edit costs little literal data around it, large enough that the old
# file's index stays small (89 MB exe -> ~22k blocks).
BLOCK = 4096
# Blocks with the same weak checksum kept per checksum; a run of identical blocks (zero padding) needs only one.
MAX_CANDIDATES = 4
# One op's length field is a u32; longer runs are split.
MAX_OP = 1 << 31
FORMAT = 1
KEEP = 3
MAX_RATIO = 0.5
# Fixed entry time so the same inputs always give the same delta bytes.
EPOCH = (1980, 1, 1, 0, 0, 0)


def delta_name(from_version: str) -> str:
    """The release asset name. The launcher matches it in UpdateDelta.IsDeltaFor (C#)."""
    return f"{ZIP_PREFIX}.delta-from-{from_version}.zip"


def _safe_path(name: str) -> bool:
    """A relative '/'-path with no empty, '.' or '..' parts and no drive or stream colon (the launcher
    applies the same rule and refuses anything else)."""
    if not name or name.startswith("/") or "\\" in name or ":" in name:
        return False
    return all(part not in ("", ".", "..") for part in name.split("/"))


def read_release_zip(path: Path) -> dict[str, tuple[int, str]]:
    """{path: (size, sha256)} for every file in a release zip. Compress-Archive on Windows PowerShell wrote
    '\\' separators, so names are normalised to '/'. Two names that differ only in case would be one file
    on Windows: refused."""
    out: dict[str, tuple[int, str]] = {}
    seen: set[str] = set()
    with zipfile.ZipFile(path) as z:
        for info in z.infolist():
            name = info.filename.replace("\\", "/")
            if name.endswith("/"):
                continue
            if not _safe_path(name):
                raise ValueError(f"{path.name}: unsafe path {name!r}")
            if name.lower() in seen:
                raise ValueError(f"{path.name}: duplicate path {name!r}")
            seen.add(name.lower())
            h = hashlib.sha256()
            with z.open(info) as f:
                for chunk in iter(lambda: f.read(1 << 20), b""):
                    h.update(chunk)
            out[name] = (info.file_size, h.hexdigest())
    return out


def _sums(block) -> tuple[int, int]:
    """rsync's weak checksum of one block, as its two sums: a = sum of the bytes, b = sum of (len - i) * byte[i]
    (= the sum of the running totals of a). Computed with C-speed builtins; the scan then rolls them byte by byte."""
    return sum(block), sum(itertools.accumulate(block))


def _key(a: int, b: int) -> int:
    # a < 2**20 for a 4 KB block (4096 * 255), so the pair packs into one int without collisions.
    return (b << 20) | a


def make_patch(old: bytes, new: bytes, block: int = BLOCK) -> bytes:
    """An ODPATCH1 patch that turns `old` into `new` (format in the module docstring)."""
    index: dict[int, list[int]] = {}
    for off in range(0, len(old) - block + 1, block):
        offs = index.setdefault(_key(*_sums(old[off:off + block])), [])
        if len(offs) < MAX_CANDIDATES:
            offs.append(off)

    out = bytearray(PATCH_MAGIC.encode("ascii"))
    out += struct.pack("<QQ", len(old), len(new))
    oldv, newv = memoryview(old), memoryview(new)

    def literal(start: int, end: int) -> None:
        for s in range(start, end, MAX_OP):
            e = min(end, s + MAX_OP)
            out.extend(OP_LITERAL.encode("ascii") + struct.pack("<I", e - s))
            out.extend(newv[s:e])

    def copy(offset: int, length: int) -> None:
        for s in range(0, length, MAX_OP):
            out.extend(OP_COPY.encode("ascii") + struct.pack("<QI", offset + s, min(MAX_OP, length - s)))

    n, n_old = len(new), len(old)
    i = lit = 0          # i: scan position; lit: start of the bytes not yet written as an op
    sums = None          # rolling (a, b) of new[i:i+block]; None = recompute at i
    while i + block <= n:
        if sums is None:
            sums = _sums(newv[i:i + block])
        a, b = sums
        hit = -1
        for o in index.get(_key(a, b), ()):
            if oldv[o:o + block] == newv[i:i + block]:   # the checksum only nominates; the bytes decide
                hit = o
                break
        if hit < 0:
            if i + block < n:   # roll the window one byte: drop new[i], take new[i + block]
                x = new[i]
                a = a - x + new[i + block]
                sums = (a, b - block * x + a)
            i += 1
            continue
        # Grow the match back over bytes not yet written, then forward a block at a time, then byte by byte.
        start, o_start = i, hit
        while start > lit and o_start > 0 and new[start - 1] == old[o_start - 1]:
            start -= 1
            o_start -= 1
        j, o_end = i + block, hit + block
        while j + block <= n and o_end + block <= n_old and newv[j:j + block] == oldv[o_end:o_end + block]:
            j += block
            o_end += block
        while j < n and o_end < n_old and new[j] == old[o_end]:
            j += 1
            o_end += 1
        literal(lit, start)
        copy(o_start, j - start)
        i = lit = j
        sums = None
    literal(lit, n)
    out.extend(OP_END.encode("ascii"))
    return bytes(out)


class PatchError(ValueError):
    pass


def apply_patch(old: bytes, patch: bytes) -> bytes:
    """The reference applier: the same rules as UpdateDelta.ApplyPatch (C#), used to check every patch before it is
    shipped and by the tests. Raises PatchError on anything the launcher would refuse."""
    magic = PATCH_MAGIC.encode("ascii")
    if len(patch) < len(magic) + 16 or patch[:len(magic)] != magic:
        raise PatchError("not an ODPATCH1 patch")
    base_size, target_size = struct.unpack_from("<QQ", patch, len(magic))
    if base_size != len(old):
        raise PatchError("made from another old file")
    out = bytearray()
    p = len(magic) + 16
    while True:
        if p >= len(patch):
            raise PatchError("patch ends early")
        op = chr(patch[p])
        p += 1
        if op == OP_END:
            break
        if op == OP_COPY:
            if p + 12 > len(patch):
                raise PatchError("patch ends early")
            offset, length = struct.unpack_from("<QI", patch, p)
            p += 12
            if length == 0 or offset + length > len(old) or len(out) + length > target_size:
                raise PatchError("copy outside the old file or past the new size")
            out += old[offset:offset + length]
        elif op == OP_LITERAL:
            if p + 4 > len(patch):
                raise PatchError("patch ends early")
            (length,) = struct.unpack_from("<I", patch, p)
            p += 4
            if length == 0 or p + length > len(patch) or len(out) + length > target_size:
                raise PatchError("literal past the patch or the new size")
            out += patch[p:p + length]
            p += length
        else:
            raise PatchError(f"unknown op {op!r}")
    if p != len(patch):
        raise PatchError("bytes after the end op")
    if len(out) != target_size:
        raise PatchError("wrote the wrong size")
    return bytes(out)


@dataclass
class Delta:
    path: Path
    size: int
    changed: list[str]
    deleted: list[str]
    patched: list[str]


def build_delta(old_zip: Path, new_zip: Path, out_path: Path, from_version: str, to_version: str) -> Delta:
    """Writes the delta that turns the release in old_zip into the one in new_zip. A file is carried when
    it is new or its bytes differ (compared by SHA-256, never by name or date): as a patch against the same path in
    the old release when that is smaller, else whole. Written to out_path.part and renamed, so a half-written delta
    never sits under the real name."""
    old = read_release_zip(old_zip)
    new = read_release_zip(new_zip)
    changed = sorted(p for p, v in new.items() if old.get(p) != v)
    deleted = sorted(p for p in old if p not in new)
    manifest = {
        "format": FORMAT,
        "asset": ASSET,
        "from": from_version,
        "to": to_version,
        "files": [{"path": p, "size": new[p][0], "sha256": new[p][1]} for p in sorted(new)],
        "deleted": deleted,
    }
    patched = []
    part = out_path.with_name(out_path.name + ".part")
    with zipfile.ZipFile(old_zip) as prev, zipfile.ZipFile(new_zip) as src, \
            zipfile.ZipFile(part, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as dst:
        dst.writestr(zipfile.ZipInfo(MANIFEST, EPOCH), json.dumps(manifest, indent=1), zipfile.ZIP_DEFLATED)
        names = _entries(src)
        old_names = _entries(prev)
        for p in changed:
            data = src.read(names[p])
            name, payload = FILES_PREFIX + p, data
            if p in old_names:
                base = prev.read(old_names[p])
                patch = make_patch(base, data)
                # Every patch is applied once here: one that would not rebuild its file is a builder bug, and the
                # whole delta is skipped (release_deltas) rather than shipping something the launcher would refuse.
                if apply_patch(base, patch) != data:
                    raise PatchError(f"{p}: the patch does not rebuild the file")
                if len(patch) < len(data):
                    name, payload = PATCHES_PREFIX + p, patch
                    patched.append(p)
            info = zipfile.ZipInfo(name, EPOCH)
            info.compress_type = zipfile.ZIP_DEFLATED
            with dst.open(info, "w", force_zip64=True) as w:
                w.write(payload)
    os.replace(part, out_path)
    return Delta(out_path, out_path.stat().st_size, changed, deleted, patched)


def _entries(z: zipfile.ZipFile) -> dict[str, zipfile.ZipInfo]:
    """'/'-path -> entry for every file in a release zip (read_release_zip has already refused unsafe names)."""
    return {i.filename.replace("\\", "/"): i for i in z.infolist() if not i.filename.endswith(("/", "\\"))}


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def write_sidecar(path: Path) -> Path:
    """<file>.sha256 in sha256sum format ("<hex>  <name>\\n"), the format UpdateVerifier.ParseSha256 reads."""
    side = path.with_name(path.name + ".sha256")
    side.write_text(f"{sha256_file(path)}  {path.name}\n", encoding="ascii", newline="\n")
    return side


def tag_version(tag: str) -> tuple[int, ...] | None:
    m = re.fullmatch(re.escape(TAG_PREFIX) + r"(\d+(?:\.\d+){1,3})", tag)
    return tuple(int(x) for x in m.group(1).split(".")) if m else None


def older_releases(releases: list[dict], tag: str, keep: int = KEEP) -> list[str]:
    """The `keep` newest published launcher releases older than `tag` (a rehearsal's "next" = the newest
    ones). Drafts and prereleases never are: launchers only ever install the "latest" release."""
    current = tag_version(tag)
    found = []
    for r in releases:
        v = tag_version(r.get("tagName", ""))
        if v is None or r.get("isDraft") or r.get("isPrerelease"):
            continue
        if current is not None and v >= current:
            continue
        found.append((v, r["tagName"]))
    return [t for _, t in sorted(found, reverse=True)[:keep]]


Runner = Callable[[list[str]], str]


def _gh(args: list[str]) -> str:
    return subprocess.run(["gh", *args], check=True, capture_output=True, text=True).stdout


def release_deltas(new_zip: Path, tag: str, out_dir: Path, gh: Runner = _gh,
                   keep: int = KEEP, max_ratio: float = MAX_RATIO) -> list[str]:
    """Builds and writes (into out_dir) one delta + .sha256 per older release that qualifies. Returns the
    delta names written. Never raises for one release's problem: that delta is skipped and said why."""
    to_version = tag[len(TAG_PREFIX):] if tag_version(tag) else "rehearsal"
    full_size = new_zip.stat().st_size
    releases = json.loads(gh(["release", "list", "--limit", "50", "--json", "tagName,isDraft,isPrerelease"]))
    written = []
    for old_tag in older_releases(releases, tag, keep):
        from_version = old_tag[len(TAG_PREFIX):]
        name = delta_name(from_version)
        with tempfile.TemporaryDirectory() as tmp:
            work = Path(tmp)
            try:
                gh(["release", "download", old_tag, "--pattern", ASSET, "--pattern", ASSET + ".sha256",
                    "--dir", str(work), "--clobber"])
                old_zip = work / ASSET
                if not old_zip.is_file():
                    print(f"{name}: skipped, {old_tag} has no {ASSET}")
                    continue
                side = work / (ASSET + ".sha256")
                # Older releases without a .sha256 are still usable: see the module note (a damaged old zip
                # can only make the delta useless, the launcher checks every file it stages).
                if side.is_file():
                    words = side.read_text(encoding="ascii", errors="replace").split()
                    if not words or words[0].lower() != sha256_file(old_zip):
                        print(f"{name}: skipped, {old_tag}'s zip does not match its .sha256")
                        continue
                delta = build_delta(old_zip, new_zip, work / name, from_version, to_version)
            except Exception as e:  # noqa: BLE001 - one release's problem never fails the release
                print(f"{name}: skipped, could not be built ({type(e).__name__}: {e})")
                continue
            if delta.size > full_size * max_ratio:
                print(f"{name}: skipped, {delta.size / 1e6:.1f} MB is more than {max_ratio:.0%} of the full "
                      f"{full_size / 1e6:.1f} MB ({len(delta.changed)} files changed)")
                continue
            # The delta is final before its hash is written, and both move out together.
            out_dir.mkdir(parents=True, exist_ok=True)
            final = out_dir / name
            # Copied, then renamed inside out_dir: the temp folder can sit on another drive (CI runner: temp on C:,
            # workspace on D:), where os.replace fails with WinError 17 (proven on launcher-v1.0.85).
            part = final.with_name(final.name + ".part")
            shutil.copyfile(delta.path, part)
            os.replace(part, final)
            write_sidecar(final)
            written.append(name)
            print(f"{name}: {delta.size / 1e6:.1f} MB ({len(delta.changed)} files changed, {len(delta.patched)} "
                  f"as patches, "
                  f"{len(delta.deleted)} removed) instead of {full_size / 1e6:.1f} MB")
    return written


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    rel = sub.add_parser("release", help="deltas from the last published releases (uses gh)")
    rel.add_argument("--new-zip", type=Path, required=True)
    rel.add_argument("--tag", required=True, help='this release\'s tag, or "next" for a rehearsal')
    rel.add_argument("--out", type=Path, required=True)
    one = sub.add_parser("build", help="one delta from two local release zips")
    one.add_argument("old", type=Path)
    one.add_argument("new", type=Path)
    one.add_argument("out", type=Path)
    one.add_argument("--from", dest="from_version", required=True)
    one.add_argument("--to", dest="to_version", required=True)
    args = ap.parse_args(argv)

    if args.cmd == "build":
        d = build_delta(args.old, args.new, args.out, args.from_version, args.to_version)
        write_sidecar(d.path)
        print(f"{d.path.name}: {d.size} bytes, {len(d.changed)} changed ({len(d.patched)} as patches), "
              f"{len(d.deleted)} removed")
        return 0
    names = release_deltas(args.new_zip, args.tag, args.out)
    print(f"{len(names)} delta(s) written")
    return 0


if __name__ == "__main__":
    sys.exit(main())
