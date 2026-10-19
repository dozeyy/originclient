"""Download the frame log's "with mods" set into a folder.

    python tools/frame-log/fetch_mods.py <out-dir> [pins.json]

Every jar is pinned by Modrinth version id (mods-1.21.1.json next to this
script) and checked against the SHA-512 Modrinth publishes for that file; a
mismatch deletes the file and fails. Already-present jars with the right hash
are kept. Then run the dev client with the folder added on top of its own mods:

    ./gradlew runClient -PframeLog -PextraMods=<out-dir>

Nothing is copied into run/mods, so a normal runClient afterwards is unchanged.
Standard library only.
"""
import hashlib
import json
import os
import sys
import urllib.request

API = "https://api.modrinth.com/v2/version/"
UA = "originclient-frame-log (github.com/dozeyy/originclient)"


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read()


def sha512(path):
    h = hashlib.sha512()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    out = sys.argv[1]
    pins = sys.argv[2] if len(sys.argv) > 2 else os.path.join(os.path.dirname(os.path.abspath(__file__)), "mods-1.21.1.json")
    os.makedirs(out, exist_ok=True)
    mods = json.load(open(pins, encoding="utf-8"))["mods"]
    for m in mods:
        v = json.loads(get(API + m["version"]))
        files = [f for f in v["files"] if f.get("primary")] or v["files"]
        f = files[0]
        name = os.path.basename(f["filename"])
        if not name.endswith(".jar") or "/" in f["filename"] or "\\" in f["filename"]:
            raise SystemExit(f"unexpected file name for {m['slug']}: {f['filename']!r}")
        want = f["hashes"]["sha512"]
        path = os.path.join(out, name)
        if os.path.isfile(path) and sha512(path) == want:
            print(f"ok    {m['slug']:32} {v['version_number']:28} {name}")
            continue
        data = get(f["url"])
        if hashlib.sha512(data).hexdigest() != want:
            raise SystemExit(f"SHA-512 mismatch for {m['slug']} ({f['url']})")
        with open(path, "wb") as fh:
            fh.write(data)
        print(f"fetch {m['slug']:32} {v['version_number']:28} {name} ({len(data) // 1024} KiB)")
    print(f"{len(mods)} mods in {os.path.abspath(out)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
