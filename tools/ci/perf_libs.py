#!/usr/bin/env python3
"""Fetch a module's pinned Sodium + Iris jars into its libs/ folder (CI only).

Why: every pre-1.21 module's build.gradle adds Sodium and Iris to the dev run with
    if (file("libs/<jar>").exists()) { modRuntimeOnly files("libs/<jar>") ... }
and libs/ is gitignored, so CI never had them: dev-shots and boot-smoke ran with NO Sodium
and NO Iris, the @Pseudo Sodium/Iris mixins found no target, and require=0 hid whether a
target was right or wrong. This puts the exact jars there BEFORE Gradle runs.

SOURCE OF TRUTH = the launcher's PerformanceModCatalog (what players actually get). The catalog
entry named after the module (src/mods/versions/<mc>) gives the Sodium + Iris file names; the module's
build.gradle libs/ lines MUST name exactly those files (otherwise Gradle would not load what was
fetched, or would load a pair Fabric refuses: 1.21.4-1.21.10 once pointed at 1.21.11's Sodium 0.8.13,
1.21 at an Iris that needs Sodium 0.6.x). A mismatch fails the step and test_ci_gates.py checks every
module statically. Each file name is resolved to a Modrinth version through the
public API (project sodium / iris, newest-first listing), then:
    GET api.modrinth.com/v2/version/<id>  ->  files[].url + files[].hashes.sha512
and the downloaded bytes MUST hash to that sha512, or the step fails (exit 1).
A pin that cannot be resolved is listed and SKIPPED (never guessed); a module with no libs/
pins (1.21.1 takes Sodium/Iris from Modrinth maven through Gradle) needs nothing.

Usage: perf_libs.py <module-dir> [--out DIR]        exit 0 = fetched/nothing to do, 1 = hash mismatch or
                                                    download failure, 2 = usage.
Prints one `perf-libs: ...` line per jar, and a final `perf-libs: <module>: fetched N, skipped M`.
"""
import argparse
import hashlib
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

API = "https://api.modrinth.com/v2"
UA = "originclient-ci/1.0 (github.com/dozeyy/originclient)"
# libs/<file> inside a modRuntimeOnly line that is not a comment.
PIN = re.compile(r'^\s*modRuntimeOnly\s+files\(\s*"libs/([^"/\\]+\.jar)"\s*\)', re.M)   # no path separators in the name
CATALOG = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))),
                       "src", "OriginLauncher.App", "Core", "Loaders", "PerformanceModCatalog.Data.cs")
PERF_MOD = re.compile(r'new PerfMod\("[^"]*", "[^"]*", "([^"/\\]+\.jar)"(?:, "[0-9a-fA-F]{128}")?\)')   # optional 4th arg = pinned SHA-512
DOWNLOAD_HOST = "cdn.modrinth.com"   # the only host a jar may be fetched from
SLUGS = (("sodium", "sodium"), ("iris", "iris"))   # (file-name prefix, Modrinth project)


class PerfLibsError(Exception):
    pass


def pins(gradle_text):
    """The libs/ jar names a build.gradle loads, in order, de-duplicated. Comment lines never match."""
    seen = []
    for name in PIN.findall(gradle_text):
        if ".." in name:
            raise PerfLibsError(f"unsafe jar name in build.gradle: {name!r}")
        if name not in seen:
            seen.append(name)
    return seen


def catalog_files(catalog_text, mc_version):
    """The (sodium, iris) jar file names the launcher ships for one catalog key, or None if no such entry.
    Entries are one line each: ["<mc>"] = new("<mc>", Tier, Sodium, Indium, Lithium, Ferrite, Krypton, Iris, ...)."""
    start = catalog_text.find('["%s"] = new(' % mc_version)
    if start < 0:
        return None
    end = catalog_text.find(chr(10), start)
    names = PERF_MOD.findall(catalog_text[start:end if end >= 0 else len(catalog_text)])
    sodium = next((n for n in names if slug_of(n) == "sodium"), None)
    iris = next((n for n in names if slug_of(n) == "iris"), None)
    return (sodium, iris)


def expected_pins(catalog_text, module, gradle_pins):
    """What to fetch for a module whose build.gradle has libs/ pins: the catalog's Sodium + Iris. Raises when the
    module has no catalog entry, the entry lacks either mod, or the build.gradle pins are not exactly those files."""
    cf = catalog_files(catalog_text, module)
    if not cf or not all(cf):
        raise PerfLibsError(f"module {module} has libs/ pins but PerformanceModCatalog has no Sodium+Iris entry for it")
    if sorted(gradle_pins) != sorted(cf):
        raise PerfLibsError(f"build.gradle libs/ pins {sorted(gradle_pins)} differ from what PerformanceModCatalog ships {sorted(cf)}")
    return list(cf)


def slug_of(filename):
    low = filename.lower()
    for prefix, slug in SLUGS:
        if low.startswith(prefix):
            return slug
    return None


def _get(url, tries=3):
    last = None
    for i in range(tries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=60) as r:
                return r.read()
        except urllib.error.HTTPError as e:
            if e.code == 404:
                raise PerfLibsError(f"404 {url}")
            last = e
        except (urllib.error.URLError, TimeoutError, ConnectionError) as e:
            last = e
        time.sleep(3 * (i + 1))
    raise PerfLibsError(f"{url}: {last}")


def resolve(filename, fetch_json=None):
    """filename -> Modrinth version id, or None when no version of that project ships a file by that exact name."""
    slug = slug_of(filename)
    if not slug:
        return None
    fetch_json = fetch_json or (lambda u: json.loads(_get(u)))
    versions = fetch_json(f"{API}/project/{slug}/version?loaders=" + urllib.parse.quote('["fabric"]'))
    for v in versions:
        if any(f.get("filename") == filename for f in v.get("files", [])):
            return v["id"]
    return None


def verify(data, expected_sha512):
    """Raises unless the bytes hash to the API's sha512."""
    got = hashlib.sha512(data).hexdigest()
    if got.lower() != (expected_sha512 or "").lower():
        raise PerfLibsError(f"sha512 mismatch: got {got[:16]}..., Modrinth says {(expected_sha512 or '')[:16]}...")


def fetch_one(filename, out_dir, fetch_json=None, fetch_bytes=None):
    """Download + verify one pinned jar. Returns 'ok' or 'skipped: <why>'. Raises PerfLibsError on mismatch."""
    if "/" in filename or "\\" in filename or ".." in filename:
        raise PerfLibsError(f"unsafe jar name: {filename!r}")
    fetch_json = fetch_json or (lambda u: json.loads(_get(u)))
    fetch_bytes = fetch_bytes or _get
    vid = resolve(filename, fetch_json)
    if not vid:
        return "skipped: no Modrinth version ships a file named exactly that"
    info = fetch_json(f"{API}/version/{vid}")
    f = next((x for x in info.get("files", []) if x.get("filename") == filename), None)
    if not f:
        return "skipped: version has no such file"
    parts = urllib.parse.urlsplit(f["url"])
    if parts.scheme != "https" or parts.hostname != DOWNLOAD_HOST:
        raise PerfLibsError(f"refusing to download from {parts.scheme}://{parts.hostname} (only https://{DOWNLOAD_HOST})")
    data = fetch_bytes(f["url"])
    verify(data, f.get("hashes", {}).get("sha512"))
    os.makedirs(out_dir, exist_ok=True)
    with open(os.path.join(out_dir, filename), "wb") as fh:
        fh.write(data)
    return f"ok (version {vid}, sha512 {f['hashes']['sha512'][:12]}...)"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("module")
    ap.add_argument("--out", default="")
    args = ap.parse_args()
    gradle = os.path.join(args.module, "build.gradle")
    if not os.path.isfile(gradle):
        print(f"perf-libs: no build.gradle in {args.module}", file=sys.stderr)
        return 2
    name = os.path.basename(os.path.abspath(args.module))
    out = args.out or os.path.join(args.module, "libs")
    with open(gradle, encoding="utf-8") as fh:
        try:
            wanted = pins(fh.read())
        except PerfLibsError as e:
            print(f"::error title=perf-libs {name}::{e}")
            return 1
    if not wanted:
        print(f"perf-libs: {name}: no libs/ pins in build.gradle (Sodium/Iris come from Gradle, if at all); nothing to fetch")
        return 0
    try:
        with open(CATALOG, encoding="utf-8") as fh:
            wanted = expected_pins(fh.read(), name, wanted)
    except (PerfLibsError, OSError) as e:
        print(f"::error title=perf-libs {name}::{e}")
        return 1
    fetched = skipped = 0
    for fn in wanted:
        try:
            res = fetch_one(fn, out)
        except PerfLibsError as e:
            print(f"::error title=perf-libs {name}::{fn}: {e}")
            return 1
        print(f"perf-libs: {name}: {fn}: {res}")
        if res.startswith("ok"):
            fetched += 1
        else:
            skipped += 1
            print(f"::warning title=perf-libs {name}::{fn} {res}")
    print(f"perf-libs: {name}: fetched {fetched}, skipped {skipped}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
