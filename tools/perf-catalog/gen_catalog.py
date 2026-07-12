#!/usr/bin/env python3
"""
Generate PerformanceModCatalog.Data.cs entries from the live Modrinth API.

WHY THIS EXISTS
---------------
The catalog pins each perf mod (Sodium, Indium, Lithium, FerriteCore, Krypton,
Iris) to an EXACT Modrinth version + direct download URL — never "latest" —
because e.g. Indium breaks silently if paired with the wrong Sodium build.
Those pins can only come from the live Modrinth API, so this must run on a
machine with network access to api.modrinth.com (the Origin dev sandbox is
firewalled off from it — that's the whole reason this is a script you run
locally rather than something baked into CI).

A version becomes shader-capable (PerfStackTier.Full, un-greyed in the picker
via HasShaderStack) exactly when BOTH Sodium and Iris resolve for it. If either
is missing for a version, it stays Partial — the script reports that so you know
the shader stack for that version genuinely doesn't exist on Modrinth yet.

USAGE
-----
    python3 tools/perf-catalog/gen_catalog.py 1.21.2 1.21.3 1.21.4 1.21.5 \
        1.21.6 1.21.7 1.21.8 1.21.9 1.21.10 1.21.11

It prints one C# dictionary entry per version, ready to paste into
src/OriginLauncher.App/Core/Loaders/PerformanceModCatalog.Data.cs (replace the
existing line for that version, or add a new one). Order the file newest-first
to match the existing layout.

Requires only the Python standard library.
"""
import json
import sys
import urllib.parse
import urllib.request

# Modrinth project slugs/ids for each mod the catalog pins. These match the
# project ids already embedded in the CDN URLs in Data.cs.
PROJECTS = {
    "sodium": "sodium",
    "indium": "indium",
    "lithium": "lithium",
    "ferrite": "ferrite-core",
    "krypton": "krypton",
    "iris": "iris",
}

API = "https://api.modrinth.com/v2"
UA = "origin-launcher-catalog-gen/1.0 (dev tooling; contact will@willhenry.me)"


def newest_version(project: str, mc_version: str):
    """Return (version_number, url, filename) for the newest Fabric release of
    `project` that lists `mc_version`, or None if there is no such build."""
    q = urllib.parse.urlencode({
        "loaders": json.dumps(["fabric"]),
        "game_versions": json.dumps([mc_version]),
    })
    req = urllib.request.Request(f"{API}/project/{project}/version?{q}",
                                 headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=30) as r:
        versions = json.load(r)
    if not versions:
        return None
    # Modrinth returns newest first, but sort by date_published to be safe.
    versions.sort(key=lambda v: v.get("date_published", ""), reverse=True)
    top = versions[0]
    # Prefer the primary file; fall back to the first .jar.
    files = top.get("files", [])
    primary = next((f for f in files if f.get("primary")), None) or (files[0] if files else None)
    if not primary:
        return None
    return top["version_number"], primary["url"], primary["filename"]


def perfmod_cs(entry):
    """Render a (version, url, filename) tuple as `new PerfMod(...)`, or `null`."""
    if entry is None:
        return "null"
    version, url, filename = entry
    return f'new PerfMod("{version}", "{url}", "{filename}")'


def main(mc_versions):
    for mc in mc_versions:
        got = {}
        for key, slug in PROJECTS.items():
            try:
                got[key] = newest_version(slug, mc)
            except Exception as e:  # network / API hiccup — report, don't guess
                print(f"// !! {mc} {key}: lookup failed ({e})", file=sys.stderr)
                got[key] = None

        full = got["sodium"] is not None and got["iris"] is not None
        tier = "PerfStackTier.Full" if full else "PerfStackTier.Partial"

        # Sodium 0.8.x+ bundles the Fabric Rendering API and `provides "indium"`,
        # so Indium is redundant/absent there. The script just emits whatever
        # Modrinth returns; if Indium is null that's correct for those lines.
        line = (
            f'        ["{mc}"] = new("{mc}", {tier}, '
            f'{perfmod_cs(got["sodium"])}, '
            f'{perfmod_cs(got["indium"])}, '
            f'{perfmod_cs(got["lithium"])}, '
            f'{perfmod_cs(got["ferrite"])}, '
            f'{perfmod_cs(got["krypton"])}'
        )
        if got["iris"] is not None:
            line += f', Iris: {perfmod_cs(got["iris"])}'
        line += "),"
        print(line)

        if not full:
            missing = [k for k in ("sodium", "iris") if got[k] is None]
            print(f"// ^ {mc} stays Partial — no {'/'.join(missing)} build on "
                  f"Modrinth for it yet (won't be offered in the picker).",
                  file=sys.stderr)


if __name__ == "__main__":
    args = sys.argv[1:]
    if not args:
        print(__doc__)
        sys.exit(2)
    main(args)
