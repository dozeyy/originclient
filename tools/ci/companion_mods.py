#!/usr/bin/env python3
"""Fetch the launcher's companion mods (ImmediatelyFast, Mod Menu) for one version into a folder (CI only).

Why: boot-smoke runs a module's dev runtime, which for most modules has NO ImmediatelyFast / Mod Menu
(the launcher installs them next to the game; only 1.21.1 and a few others bundle them). So a catalog
bump of those two was never booted. This reads the module's PerformanceModCatalog entry, downloads
exactly those two pinned jars, checks each against the catalog's SHA-512, and drops them in a folder that
a module's `-PextraMods=<folder>` (Fabric's fabric.addMods) loads into runClient.

Also the gate boot-smoke uses to PROVE they loaded: expected_versions() = the catalog's version of each
companion, verify_loaded() = problems when Fabric's "Loading N mods:" list lacks one of them at that version.
(Without it, a module whose runClient has no -PextraMods hook downloaded the jars, loaded nothing and passed.)

Usage: companion_mods.py <module-dir> [--out DIR]     (default DIR = <module>/run/extra-mods)
Exit 0 = fetched, 1 = no companion in the catalog / bad hash / bad host, 2 = usage.
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
FILE_VERSION = re.compile(r"^(?:modmenu|immediatelyfast)(?:-fabric)?-(\d[^/]*)\.jar$", re.I)
# Fabric Loader's mod list, top level only: "\t- modmenu 17.0.1". A jar-in-jar child is "\-- modmenu ..." and
# must not count (the backslash is not whitespace), or a module bundling its own copy would "pass" without the pin.
LOADED = re.compile(r"^[ \t]*- (modmenu|immediatelyfast) (\S+)", re.M | re.I)


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


def file_version(filename):
    """'modmenu-17.0.1.jar' -> '17.0.1'; 'ImmediatelyFast-Fabric-1.14.3+1.21.11.jar' -> '1.14.3+1.21.11'; else ''."""
    m = FILE_VERSION.match(filename)
    return m.group(1) if m else ""


def expected_versions(catalog_text, mc_version):
    """{'modmenu': '17.0.1', ...} the catalog bundles for one module; {} when it bundles none."""
    return {p: file_version(fn) for p, (_url, fn, _sha) in companions(catalog_text, mc_version).items()}


def _base(version):
    return version.split("+", 1)[0].lower()


def verify_loaded(log_text, expected):
    """Problems (empty = fine) when the game log's mod list lacks a companion or has another version.
    Compared on the part before '+': the jar's fabric.mod.json may or may not repeat the MC suffix."""
    have = {m.group(1).lower(): m.group(2) for m in LOADED.finditer(log_text)}
    out = []
    for prefix, want in sorted(expected.items()):
        got = have.get(prefix)
        if got is None:
            out.append(f"{prefix} {want} is not in the loaded mod list (the module's runClient did not load -PextraMods?)")
        elif _base(got) != _base(want):
            out.append(f"{prefix} loaded as {got}, catalog says {want}")
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("module")
    ap.add_argument("--out", default="")
    a = ap.parse_args()
    name = os.path.basename(os.path.abspath(a.module))
    out = a.out or os.path.join(a.module, "run", "extra-mods")
    found = companions(open(CATALOG, encoding="utf-8").read(), name)
    # Not every era has both (1.17.1 and 1.16.5 bundle no ImmediatelyFast): fetch what the catalog has.
    if not found:
        print(f"::error title=companion-mods {name}::catalog has no {list(WANTED)} for {name}")
        return 1
    os.makedirs(out, exist_ok=True)
    for prefix in WANTED:
        if prefix not in found:
            continue
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
