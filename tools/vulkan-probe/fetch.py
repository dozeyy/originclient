#!/usr/bin/env python3
"""Vulkan probe only: download the pinned VulkanMod (and optionally VulkaIris) jars, check SHA-512,
and print each jar's fabric.mod.json id / provides / depends / breaks plus its nested jars
(and whether each nested jar carries a fabric.mod.json, the known VulkaIris sodium-api trap).

usage: fetch.py <out-dir> [--vulkairis] [--local <jar>] [--shaderpack <modrinth-version-id> <dir>]
  --local copies a jar built in this run (Origin's VulkaIris fork) and describes it the same way.
"""
import hashlib
import io
import json
import os
import sys
import urllib.request
import zipfile

UA = {"User-Agent": "origin-ci-vulkan-probe"}
# VulkanMod 0.6.7+1.21.1: hash taken from Modrinth's own API at run time.
VULKANMOD = ("2eqroDKD", None)
# VulkaIris (upstream latest): hash pinned in the brief, must equal Modrinth's.
VULKAIRIS = ("QKLglGuI", "ecdd362ee7467626d2650b420e4a587f0511c87cba8f523a5f28049b3554bd5d2f1de04a4958a0b97cc23ab4e71d0297044d2725e0af307725a2850ffcfcb779")


def get(url):
    for attempt in range(4):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=120) as r:
                return r.read()
        except Exception as e:  # network flake: retry
            print(f"fetch: {url} failed ({e}), attempt {attempt + 1}")
    raise SystemExit(f"fetch: could not download {url}")


def describe(name, data):
    z = zipfile.ZipFile(io.BytesIO(data))
    fmj = json.loads(z.read("fabric.mod.json").decode("utf-8", "replace"))
    print(f"  {name}: id={fmj.get('id')} version={fmj.get('version')}")
    for k in ("provides", "depends", "breaks", "conflicts"):
        if fmj.get(k):
            print(f"    {k}: {json.dumps(fmj[k])}")
    for j in fmj.get("jars", []):
        inner = j.get("file")
        try:
            iz = zipfile.ZipFile(io.BytesIO(z.read(inner)))
            has = "fabric.mod.json" in iz.namelist()
            extra = ""
            if has:
                ij = json.loads(iz.read("fabric.mod.json").decode("utf-8", "replace"))
                extra = f" id={ij.get('id')} version={ij.get('version')}"
            print(f"    nested {inner}: fabric.mod.json={'yes' if has else 'NO'}{extra}")
        except KeyError:
            print(f"    nested {inner}: listed but missing from the jar")


def fetch(out, vid, pinned):
    meta = json.loads(get(f"https://api.modrinth.com/v2/version/{vid}"))
    f = next(x for x in meta["files"] if x.get("primary")) if any(x.get("primary") for x in meta["files"]) else meta["files"][0]
    want = f["hashes"]["sha512"]
    if pinned and pinned != want:
        raise SystemExit(f"fetch: {vid} Modrinth sha512 {want[:16]} != pinned {pinned[:16]}")
    data = get(f["url"])
    got = hashlib.sha512(data).hexdigest()
    if got != want:
        raise SystemExit(f"fetch: {f['filename']} sha512 mismatch ({got[:16]} != {want[:16]})")
    path = os.path.join(out, f["filename"])
    with open(path, "wb") as fh:
        fh.write(data)
    print(f"fetch: {meta['name']} {meta['version_number']} -> {path} ({len(data)} bytes, sha512 OK {got[:16]})")
    describe(f["filename"], data)


def fetch_pack(vid, dest):
    """A shader pack from Modrinth by version id (SHA-512 from Modrinth's API), saved unopened into dest."""
    os.makedirs(dest, exist_ok=True)
    meta = json.loads(get(f"https://api.modrinth.com/v2/version/{vid}"))
    f = meta["files"][0]
    data = get(f["url"])
    got = hashlib.sha512(data).hexdigest()
    if got != f["hashes"]["sha512"]:
        raise SystemExit(f"fetch: {f['filename']} sha512 mismatch")
    path = os.path.join(dest, f["filename"])
    with open(path, "wb") as fh:
        fh.write(data)
    print(f"fetch: shader pack {meta['name']} {meta['version_number']} -> {path} ({len(data)} bytes, sha512 OK)")
    print(f"PACK_FILE={f['filename']}")


def main():
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    fetch(out, *VULKANMOD)
    if "--vulkairis" in sys.argv[2:]:
        fetch(out, *VULKAIRIS)
    if "--local" in sys.argv[2:]:
        src = sys.argv[sys.argv.index("--local") + 1]
        with open(src, "rb") as fh:
            data = fh.read()
        path = os.path.join(out, os.path.basename(src))
        with open(path, "wb") as fh:
            fh.write(data)
        print(f"fetch: local {src} -> {path} ({len(data)} bytes, sha512 {hashlib.sha512(data).hexdigest()[:16]})")
        describe(os.path.basename(src), data)
    if "--shaderpack" in sys.argv[2:]:
        i = sys.argv.index("--shaderpack")
        fetch_pack(sys.argv[i + 1], sys.argv[i + 2])


if __name__ == "__main__":
    main()
