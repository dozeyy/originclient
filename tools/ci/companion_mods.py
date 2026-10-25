#!/usr/bin/env python3
"""Fetch the launcher's companion mods (ImmediatelyFast, Mod Menu) for one version into a folder (CI only).

Why: boot-smoke runs a module's dev runtime, which for most modules has NO ImmediatelyFast / Mod Menu
(the launcher installs them next to the game; only 1.21.1 and a few others bundle them). So a catalog
bump of those two was never booted. This reads the module's PerformanceModCatalog entry, downloads
exactly those two pinned jars, checks each against the catalog's SHA-512, and drops them in a folder that
a module's `-PextraMods=<folder>` (Fabric's fabric.addMods) loads into runClient.

Usage: companion_mods.py <module-dir> [--out DIR]     (default DIR = <module>/run/extra-mods)
Exit 0 = fetched, 1 = missing entry / bad hash / bad host, 2 = usage.
"""
import argparse
import hashlib
import os
import re
import sys
import urllib.parse
import urllib.request

CATALOG = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))),
                       "src", "OriginLauncher.App", "Core", "Loaders", "PerformanceModCatalog.Data.cs")
PERF_MOD = re.compile(r'new PerfMod\("[^"]*", "([^"]+)", "([^"/]+\.jar)", "([0-9a-fA-F]{128})"\)')   # url, file, sha512
WANTED = ("immediatelyfast", "modmenu")
HOST = "cdn.modrinth.com"


def companions(catalog_text, mc_version):
    """{prefix: (url, filename, sha512)} for the wanted mods in one catalog key; {} when no such entry."""
    start = catalog_text.find('["%s"] = new(' % mc_version)
    if start < 0:
        return {}
    end = catalog_text.find("\n", start)
    out = {}
    for url, fn, sha in PERF_MOD.findall(catalog_text[start:end if end >= 0 else len(catalog_text)]):
        for prefix in WANTED:
            if fn.lower().startswith(prefix):
                out[prefix] = (url, fn, sha)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("module")
    ap.add_argument("--out", default="")
    a = ap.parse_args()
    name = os.path.basename(os.path.abspath(a.module))
    out = a.out or os.path.join(a.module, "run", "extra-mods")
    found = companions(open(CATALOG, encoding="utf-8").read(), name)
    missing = [p for p in WANTED if p not in found]
    if missing:
        print(f"::error title=companion-mods {name}::catalog has no {missing} for {name}")
        return 1
    os.makedirs(out, exist_ok=True)
    for prefix in WANTED:
        url, fn, sha = found[prefix]
        parts = urllib.parse.urlsplit(url)
        if parts.scheme != "https" or parts.hostname != HOST:
            print(f"::error title=companion-mods {name}::refusing {url}")
            return 1
        data = urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "originclient-ci/1.0"}), timeout=60).read()
        if hashlib.sha512(data).hexdigest().lower() != sha.lower():
            print(f"::error title=companion-mods {name}::{fn}: sha512 mismatch")
            return 1
        with open(os.path.join(out, fn), "wb") as fh:
            fh.write(data)
        print(f"companion-mods: {name}: {fn}: ok")
    return 0


if __name__ == "__main__":
    sys.exit(main())
