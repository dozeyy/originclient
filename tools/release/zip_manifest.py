#!/usr/bin/env python3
"""Writes the self-update's file list ("delta manifest") for a launcher zip, and proves it against the zip.

    python tools/release/zip_manifest.py OriginLauncher-win-x64.zip
      -> OriginLauncher-win-x64.files.json + OriginLauncher-win-x64.files.json.sha256 next to the zip

Why: installed launchers update by downloading only the files that changed (Core/Updates/DeltaStager.cs). They read
this list, hash the files they already have, and fetch each changed file's compressed bytes out of the SAME release zip
with an HTTP Range request (GitHub's asset host honours Range). So for every file in the zip the list carries:
  path    relative path, "/" separators (what ends up under the install folder)
  size    uncompressed size
  sha256  SHA-256 of the uncompressed bytes (the launcher checks every staged file against it)
  offset  where the file's compressed bytes start in the zip (after its local header, so the launcher parses no zip)
  csize   compressed size
  method  0 stored / 8 deflate (anything else is refused here)
plus the zip's own name, size and SHA-256, so a launcher can tell the list belongs to the zip whose .sha256 it trusts.

Trust: the list gets its own sha256sum-format sidecar, published next to it, the same model as the zip's .sha256.
Every byte the launcher stages is checked against a hash in this list, and the list is tied to the zip's hash.

Self-check: every entry is read back from its raw offset, inflated and hashed, and must equal what the zip library
itself extracts (which also checks each CRC). A zip this script can't describe exactly fails the run, loudly: the
launcher would only fall back to the full download, but a release should never ship a list that is wrong.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import struct
import sys
import zipfile
import zlib
from pathlib import Path

FORMAT = 1
LOCAL_HEADER = struct.Struct("<IHHHHHIIIHH")  # 30 bytes
LOCAL_SIG = 0x04034B50
CHUNK = 1 << 20
BAD_CHARS = set('<>:"|?*\\')


def _is_device(segment: str) -> bool:
    """Windows device names (CON, NUL, COM1, LPT1, ...), with or without an extension: DeltaManifest.IsDeviceName."""
    stem = segment.split(".", 1)[0].rstrip(" ").upper()
    return stem in ("CON", "PRN", "AUX", "NUL", "CONIN$", "CONOUT$") or (
        len(stem) == 4 and stem[:3] in ("COM", "LPT") and (stem[3].isdigit() or stem[3] in "\u00b9\u00b2\u00b3"))


def _safe_path(name: str) -> str:
    """The launcher refuses anything else (DeltaManifest.IsSafeRelative); refuse it here first, loudly."""
    path = name.replace("\\", "/")
    parts = path.split("/")
    if not path or path.startswith("/") or any(p == "" or p[-1] in ". " or _is_device(p) for p in parts) \
            or any(c in BAD_CHARS or ord(c) < 0x20 for c in path):
        raise SystemExit(f"zip_manifest: unsafe path in zip: {name!r}")
    return path


def _sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(CHUNK), b""):
            h.update(chunk)
    return h.hexdigest()


def _raw_entry_sha(raw, offset: int, csize: int, method: int) -> tuple[str, int]:
    """SHA-256 and length of one entry decoded from its raw bytes at offset (what the launcher does)."""
    raw.seek(offset)
    h = hashlib.sha256()
    out = 0
    inflater = zlib.decompressobj(-15) if method == 8 else None
    left = csize
    while left > 0:
        chunk = raw.read(min(CHUNK, left))
        if not chunk:
            raise SystemExit("zip_manifest: zip ended inside an entry")
        left -= len(chunk)
        data = inflater.decompress(chunk) if inflater else chunk
        h.update(data)
        out += len(data)
    if inflater:
        tail = inflater.flush()
        h.update(tail)
        out += len(tail)
        if not inflater.eof:
            raise SystemExit("zip_manifest: deflate stream did not end where the zip says it does")
    return h.hexdigest(), out


def _lib_entry_sha(zf: zipfile.ZipFile, info: zipfile.ZipInfo) -> str:
    h = hashlib.sha256()
    with zf.open(info) as f:  # checks the CRC at the end
        for chunk in iter(lambda: f.read(CHUNK), b""):
            h.update(chunk)
    return h.hexdigest()


def build(zip_path: Path, zip_name: str | None = None) -> dict:
    zip_path = Path(zip_path)
    files, dirs, seen = [], [], set()
    with open(zip_path, "rb") as raw, zipfile.ZipFile(zip_path) as zf:
        for info in zf.infolist():
            if info.flag_bits & 0x1:
                raise SystemExit(f"zip_manifest: encrypted entry {info.filename!r}")
            if info.filename.replace("\\", "/").endswith("/"):
                d = _safe_path(info.filename.replace("\\", "/").rstrip("/"))
                if d.lower() not in seen:
                    seen.add(d.lower())
                    dirs.append(d)
                continue
            path = _safe_path(info.filename)
            if path.lower() in seen:
                raise SystemExit(f"zip_manifest: duplicate path {path!r}")
            seen.add(path.lower())
            if info.compress_type not in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED):
                raise SystemExit(f"zip_manifest: {path}: compression method {info.compress_type} (only 0/8)")
            raw.seek(info.header_offset)
            header = raw.read(LOCAL_HEADER.size)
            fields = LOCAL_HEADER.unpack(header)
            if fields[0] != LOCAL_SIG:
                raise SystemExit(f"zip_manifest: {path}: no local header at {info.header_offset}")
            name_len, extra_len = fields[9], fields[10]
            offset = info.header_offset + LOCAL_HEADER.size + name_len + extra_len
            sha, length = _raw_entry_sha(raw, offset, info.compress_size, info.compress_type)
            if length != info.file_size or sha != _lib_entry_sha(zf, info):
                raise SystemExit(f"zip_manifest: {path}: raw bytes at {offset} do not decode to the zip's own file")
            files.append({"path": path, "size": info.file_size, "sha256": sha, "offset": offset,
                          "csize": info.compress_size, "method": info.compress_type})
    files.sort(key=lambda f: f["offset"])
    return {
        "format": FORMAT,
        "zip": zip_name or zip_path.name,
        "zipSize": zip_path.stat().st_size,
        "zipSha256": _sha256_file(zip_path),
        "files": files,
        "dirs": sorted(dirs),
    }


def manifest_name(zip_name: str) -> str:
    """OriginLauncher-win-x64.zip -> OriginLauncher-win-x64.files.json (UpdateService.Windows.cs DeltaManifestName)."""
    stem = zip_name[:-4] if zip_name.lower().endswith(".zip") else zip_name
    return stem + ".files.json"


def write(zip_path: Path, out_dir: Path | None = None) -> Path:
    zip_path = Path(zip_path)
    manifest = build(zip_path)
    body = (json.dumps(manifest, separators=(",", ":"), ensure_ascii=True) + "\n").encode("ascii")
    out = (out_dir or zip_path.parent) / manifest_name(zip_path.name)
    out.write_bytes(body)
    sidecar = out.with_name(out.name + ".sha256")
    sidecar.write_bytes(f"{hashlib.sha256(body).hexdigest()}  {out.name}\n".encode("ascii"))
    return out


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("zip", type=Path)
    ap.add_argument("--out-dir", type=Path, default=None)
    args = ap.parse_args(argv)
    out = write(args.zip, args.out_dir)
    m = json.loads(out.read_text(encoding="ascii"))
    print(f"{out.name}: {len(m['files'])} files, {len(m['dirs'])} dirs, every entry checked against the zip "
          f"(zip {m['zipSize']} bytes, sha256 {m['zipSha256']})")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
