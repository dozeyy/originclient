#!/usr/bin/env python3
"""Mac boot probe: Mojang's published facts, and Mojang's own Java for a player's Mac.

    mojang_runtime.py facts
        Prints what Mojang's version JSONs really say for 1.8.9 / 1.16.5 / 1.21.1 (Java component,
        the macOS -XstartOnFirstThread rule, LWJGL entries on 1.8.9) and which Java builds the
        mac-os-arm64 and mac-os platforms have. The Mac launcher design relies on these.

    mojang_runtime.py fetch <mc version> <platform> <dest dir>
        Downloads the Java the version names, from the same all.json the launcher uses
        (Core/Import/GameFiles/MetadataClient.cs), SHA-1 checked, and prints java=<path>.
"""
import hashlib
import json
import os
import stat
import sys
import urllib.request

MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
RUNTIMES = ("https://launchermeta.mojang.com/v1/products/java-runtime/"
            "2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json")


def get_json(url):
    with urllib.request.urlopen(url, timeout=60) as r:
        return json.load(r)


def version_json(mc):
    for v in get_json(MANIFEST)["versions"]:
        if v["id"] == mc:
            return get_json(v["url"])
    raise SystemExit("no such version: " + mc)


def facts():
    for mc in ("1.8.9", "1.16.5", "1.21.1"):
        j = version_json(mc)
        jvm = j.get("arguments", {}).get("jvm", [])
        rule = [a for a in jvm if isinstance(a, dict) and "XstartOnFirstThread" in json.dumps(a)]
        print("facts: %s javaVersion=%s XstartOnFirstThread=%s" % (mc, j.get("javaVersion"), json.dumps(rule)))
        if mc == "1.8.9":
            for lib in j["libraries"]:
                if "lwjgl" in lib["name"] or "jinput" in lib["name"]:
                    print("facts: 1.8.9 lib %s rules=%s natives=%s"
                          % (lib["name"], json.dumps(lib.get("rules")), json.dumps(lib.get("natives"))))
        if mc == "1.21.1":
            print("facts: 1.21.1 lwjgl-glfw arm64 natives: %s" % sorted(
                {lib["downloads"]["artifact"]["path"].rsplit("/", 1)[-1] for lib in j["libraries"]
                 if "lwjgl-glfw" in lib["name"] and "macos" in lib["name"]}))
    runtimes = get_json(RUNTIMES)
    for plat in ("mac-os-arm64", "mac-os"):
        print("facts: %s %s" % (plat, {k: (v[0]["version"]["name"] if v else None) for k, v in runtimes[plat].items()}))


def fetch(mc, platform, dest):
    component = version_json(mc)["javaVersion"]["component"]
    builds = get_json(RUNTIMES)[platform].get(component)
    if not builds:
        raise SystemExit("Mojang has no %s for %s" % (component, platform))
    print("runtime: %s %s for %s, %s" % (component, builds[0]["version"]["name"], mc, platform))
    files = get_json(builds[0]["manifest"]["url"])["files"]
    java = None
    for rel, entry in files.items():
        path = os.path.join(dest, rel)
        if entry["type"] == "directory":
            os.makedirs(path, exist_ok=True)
        elif entry["type"] == "link":
            os.makedirs(os.path.dirname(path), exist_ok=True)
            if not os.path.lexists(path):
                os.symlink(entry["target"], path)
        elif entry["type"] == "file":
            raw = entry["downloads"]["raw"]
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with urllib.request.urlopen(raw["url"], timeout=120) as r:
                data = r.read()
            if hashlib.sha1(data).hexdigest() != raw["sha1"]:
                raise SystemExit("SHA-1 mismatch: " + rel)
            with open(path, "wb") as f:
                f.write(data)
            if entry.get("executable"):
                os.chmod(path, os.stat(path).st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
            if rel.endswith("/bin/java"):
                java = path
    if not java:
        raise SystemExit("no bin/java in the manifest")
    print("java=" + os.path.abspath(java))


if __name__ == "__main__":
    if sys.argv[1:2] == ["facts"]:
        facts()
    elif sys.argv[1:2] == ["fetch"] and len(sys.argv) == 5:
        fetch(sys.argv[2], sys.argv[3], sys.argv[4])
    else:
        raise SystemExit(__doc__)
