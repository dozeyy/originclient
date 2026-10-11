#!/usr/bin/env python3
"""Build OriginLauncher-Setup.exe = OriginSetup.exe + packed launcher payload.

The custom installer (src/OriginSetup) carries the whole launcher inside
itself. This appends that payload to the compiled setup app, in the "OPK1"
format src/OriginSetup/Engine/Payload.cs reads — keep the two in step:

  [OriginSetup.exe][payload][int64 payloadOffset]["OPKEND01"]

  payload = "OPK1", int32 1, str version,
            int32 fileCount, (str relPath, int64 size)*,
            int32 chunkCount, (int64 rawSize, int64 packedSize, uint32 crc32)*,
            chunk data (each chunk an independent .lzma stream)
  str = int32 byte length + UTF-8; all integers little-endian.

Every file under --source is laid end to end (sorted by path, so builds are
reproducible) and the run is cut into --chunk-mb pieces compressed in
parallel, one per CPU — LZMA is slow single-threaded (~2.3 MB/s at level 6),
and independent chunks also let the installer decode on every core.

Measured on the v1.0.39 launcher (221 MB): 32 MB chunks at level 6 -> 85.0 MB
setup in 28 s on 4 cores (the old Inno Setup exe was 86 MB); 16 MB chunks
cost +2 MB, 48 MB chunks save 1.6 MB but raise installer peak memory.

Usage:
  pack_payload.py --setup-exe OriginSetup.exe --source publish --version 1.0.40 \
                  --out OriginLauncher-Setup.exe [--level 6] [--chunk-mb 32]
"""
from __future__ import annotations

import argparse
import lzma
import os
import struct
import sys
import time
import zlib
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

TRAILER_MAGIC = b"OPKEND01"


def compress(args: tuple[bytes, int]) -> bytes:
    data, level = args
    return lzma.compress(data, format=lzma.FORMAT_ALONE, preset=level)


def pack_str(s: str) -> bytes:
    b = s.encode("utf-8")
    return struct.pack("<i", len(b)) + b


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--setup-exe", type=Path, required=True)
    ap.add_argument("--source", type=Path, required=True, help="folder to install (dotnet publish output)")
    ap.add_argument("--version", default="0.0.0")
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--level", type=int, default=6, help="LZMA preset 0-9 (CI checks use 1 for speed)")
    ap.add_argument("--chunk-mb", type=int, default=32)
    ap.add_argument("--exclude", action="append", default=["*.pdb", "*.xml"],
                    help="glob of files to leave out (default: debug symbols + API docs, "
                         "which the old Inno installer never shipped either)")
    args = ap.parse_args()

    started = time.time()
    files = sorted(p for p in args.source.rglob("*")
                   if p.is_file() and not any(p.match(g) for g in args.exclude))
    if not files:
        print(f"error: nothing to pack in {args.source}", file=sys.stderr)
        return 1
    rels = [p.relative_to(args.source).as_posix() for p in files]
    blob = b"".join(p.read_bytes() for p in files)
    sizes = [p.stat().st_size for p in files]

    step = args.chunk_mb * 1024 * 1024
    pieces = [blob[i:i + step] for i in range(0, len(blob), step)]
    with ProcessPoolExecutor(max_workers=os.cpu_count()) as pool:
        packed = list(pool.map(compress, [(p, args.level) for p in pieces]))

    header = b"OPK1" + struct.pack("<i", 1) + pack_str(args.version)
    header += struct.pack("<i", len(files))
    for rel, size in zip(rels, sizes):
        header += pack_str(rel) + struct.pack("<q", size)
    header += struct.pack("<i", len(pieces))
    for raw, comp in zip(pieces, packed):
        header += struct.pack("<qqI", len(raw), len(comp), zlib.crc32(raw) & 0xFFFFFFFF)

    setup = args.setup_exe.read_bytes()
    with args.out.open("wb") as out:
        out.write(setup)
        out.write(header)
        for comp in packed:
            out.write(comp)
        out.write(struct.pack("<q", len(setup)) + TRAILER_MAGIC)

    total = args.out.stat().st_size
    print(f"Packed {len(files)} files ({len(blob) / 1e6:.1f} MB) into {len(pieces)} chunks "
          f"-> {args.out} {total / 1e6:.1f} MB in {time.time() - started:.0f}s "
          f"(setup app {len(setup) / 1e6:.2f} MB, LZMA level {args.level})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
