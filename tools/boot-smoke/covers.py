#!/usr/bin/env python3
"""Prepare a boot of a COVERED Minecraft version, the way players run it (CI only, boot-smoke covers mode).

Why: one module jar serves several Minecraft versions (live-modules.json "versions": 1.21.4 serves 1.21.2,
1.21.3 and 1.21.4). boot-smoke's normal mode runs the module's own runClient, i.e. its source compiled for its
PRIMARY version (gradle.properties minecraft_version) with that version's Sodium + Iris. A covered version was
never booted: different Minecraft, different Fabric API, different Sodium/Iris from the launcher catalog.

What players actually run on a covered version is:
    the covering module's BUILT jar  +  that Minecraft version  +  Fabric API (newest release for it,
    FabricApiInstaller)  +  every mod PerformanceModCatalog lists for it (Sodium, Iris, Lithium, ...)
So this builds exactly that, production-style: a tiny Loom project with NO sources of its own
(<out>/build.gradle) that runs the covered Minecraft version with fabric.addMods=<out>/mods, and <out>/mods
holds the production jars. Fabric Loader remaps intermediary jars to the dev namespace at launch (the same
path a jar dropped into run/mods takes), so the built Origin jar's own refmap is what applies -- not a dev
recompile. 26.x is unobfuscated: nothing is remapped at all.

The project copies the covering module's Gradle wrapper, settings.gradle, plugins block, mappings line and
loader pin, so it runs on the same Gradle + Loom as the module. tools/boot-smoke/smoke.py then boots <out>
like any module (same READY marker, same failure patterns, --perf, --companions, --run-java); name the out
folder after the Minecraft version so smoke.py's catalog lookups (--companions) use the covered version's row.

Every download is checked: catalog jars against the catalog's own SHA-512 (what the launcher checks), Fabric
API against Modrinth's SHA-512; only https://cdn.modrinth.com is accepted.

Usage: covers.py <module-dir> <mc-version> --out <dir>
Exit 0 = project ready, 1 = a check failed (printed as ::error), 2 = usage.
"""
import argparse
import glob
import hashlib
import json
import os
import re
import shutil
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CATALOG = os.path.join(ROOT, "src", "OriginLauncher.App", "Core", "Loaders", "PerformanceModCatalog.Data.cs")
LIVE = os.path.join(ROOT, "src", "mods", "live-modules.json")
API = "https://api.modrinth.com/v2"
UA = "originclient-ci/1.0 (github.com/dozeyy/originclient)"
HOST = "cdn.modrinth.com"
# new PerfMod("<version>", "<url>", "<file>", "<sha512>") -- the SHA is optional in the record, required here.
PERF_MOD = re.compile(r'new PerfMod\("[^"]*", "([^"]+)", "([^"/\\]+\.jar)"(?:, "([0-9a-fA-F]{128})")?\)')
SAFE_FILE = re.compile(r"^[A-Za-z0-9._+\-]+\.jar$")


class CoverError(Exception):
    pass


def covering_module(live_json_text, mc_version):
    """The live module whose 'versions' list holds mc_version, or None."""
    for m in json.loads(live_json_text)["modules"]:
        if mc_version in m.get("versions", []):
            return m["module"]
    return None


def catalog_mods(catalog_text, mc_version):
    """[(url, file, sha512)] for every mod the launcher catalog installs on mc_version. Raises when the entry is
    missing, a jar has no SHA-512 pin, or Sodium / Iris is absent (the boot would prove nothing about shaders)."""
    start = catalog_text.find('["%s"] = new(' % mc_version)
    if start < 0:
        raise CoverError(f"PerformanceModCatalog has no entry for {mc_version}")
    end = catalog_text.find("\n", start)
    out = []
    for url, fn, sha in PERF_MOD.findall(catalog_text[start:end if end >= 0 else len(catalog_text)]):
        if not sha:
            raise CoverError(f"{mc_version}: catalog jar {fn} has no SHA-512 pin")
        out.append((url, fn, sha.lower()))
    names = [fn.lower() for _u, fn, _s in out]
    for need in ("sodium", "iris"):
        if not any(n.startswith(need) for n in names):
            raise CoverError(f"{mc_version}: catalog entry has no {need} jar")
    return out


def gradle_property(props_text, key):
    m = re.search(r"^\s*%s\s*=\s*(\S+)\s*$" % re.escape(key), props_text, re.M)
    return m.group(1) if m else None


def _strip_comments(gradle_text):
    return re.sub(r"/\*.*?\*/|//[^\n]*", "", gradle_text, flags=re.S)


def build_gradle(module_gradle_text):
    """The cover project's build.gradle, derived from the covering module's: same plugins block, same mappings
    line (none on unobfuscated 26.x), same configuration for fabric-loader. No sources, no Origin dependency:
    every mod (Origin included) comes in through fabric.addMods as a production jar."""
    code = _strip_comments(module_gradle_text)
    m = re.search(r"^plugins\s*\{.*?^\}", code, re.S | re.M)
    if not m:
        raise CoverError("covering module's build.gradle has no plugins { } block")
    plugins = m.group(0)
    mappings = "\tmappings loom.officialMojangMappings()\n" if re.search(r"^\s*mappings\s+loom\.officialMojangMappings\(\)", code, re.M) else ""
    if not mappings and re.search(r"^\s*mappings\s", code, re.M):
        raise CoverError("covering module uses a mappings line other than officialMojangMappings(); not handled")
    cfg = re.search(r'^\s*(modImplementation|implementation)\s+"net\.fabricmc:fabric-loader:', code, re.M)
    if not cfg:
        raise CoverError("covering module's build.gradle declares no fabric-loader dependency")
    return (
        "// GENERATED by tools/boot-smoke/covers.py (CI only): boots a covered Minecraft version with the\n"
        "// covering module's BUILT jar + the launcher catalog's mods, all loaded from mods/ (fabric.addMods).\n"
        f"{plugins}\n\n"
        "repositories {\n\tmavenCentral()\n}\n\n"
        "dependencies {\n"
        '\tminecraft "com.mojang:minecraft:${project.minecraft_version}"\n'
        f"{mappings}"
        f'\t{cfg.group(1)} "net.fabricmc:fabric-loader:${{project.loader_version}}"\n'
        "}\n\n"
        "loom {\n\truns {\n\t\tclient {\n"
        '\t\t\tproperty "fabric.addMods", file("mods").absolutePath\n'
        "\t\t}\n\t}\n}\n"
    )


def gradle_properties(module_props_text, mc_version):
    """The module's gradle.properties with minecraft_version swapped for the covered version."""
    if gradle_property(module_props_text, "minecraft_version") is None:
        raise CoverError("covering module's gradle.properties has no minecraft_version")
    return re.sub(r"^(\s*minecraft_version\s*=\s*)\S+", lambda m: m.group(1) + mc_version, module_props_text, flags=re.M)


def built_jar(module_dir):
    """The module's release jar in build/libs (not -sources / -dev). Exactly one, or raise."""
    jars = [j for j in glob.glob(os.path.join(module_dir, "build", "libs", "*.jar"))
            if not re.search(r"-(sources|dev|javadoc)\.jar$", j)]
    if len(jars) != 1:
        raise CoverError(f"expected exactly one built jar in {module_dir}/build/libs, found {[os.path.basename(j) for j in jars]}"
                         " (run `gradlew build` in the module first)")
    return jars[0]


def pick_newest_release(versions):
    """FabricApiInstaller's rule (ModrinthReleasePicker): version_type == release, newest date_published."""
    rel = [v for v in versions if v.get("version_type") == "release"]
    return max(rel, key=lambda v: v.get("date_published", "")) if rel else None


def _get(url, tries=4):
    last = None
    for i in range(tries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=90) as r:
                return r.read()
        except urllib.error.HTTPError as e:
            if e.code == 404:
                raise CoverError(f"404 {url}")
            last = e
        except (urllib.error.URLError, TimeoutError, ConnectionError) as e:
            last = e
        time.sleep(5 * (i + 1))
    raise CoverError(f"{url}: {last}")


def _download(url, filename, sha512, out_dir, fetch=_get):
    parts = urllib.parse.urlsplit(url)
    if parts.scheme != "https" or parts.hostname != HOST:
        raise CoverError(f"refusing {url}: only https://{HOST}")
    if not SAFE_FILE.match(filename):
        raise CoverError(f"unsafe jar name {filename!r}")
    data = fetch(url)
    got = hashlib.sha512(data).hexdigest()
    if got != (sha512 or "").lower():
        raise CoverError(f"{filename}: sha512 {got[:16]}... != pinned {(sha512 or '')[:16]}...")
    with open(os.path.join(out_dir, filename), "wb") as f:
        f.write(data)


def fabric_api(mc_version, fetch_json=None):
    """(url, filename, sha512, version_number) of the Fabric API release a fresh install of mc_version gets now."""
    fetch_json = fetch_json or (lambda u: json.loads(_get(u)))
    q = "game_versions=" + urllib.parse.quote(json.dumps([mc_version])) + "&loaders=" + urllib.parse.quote('["fabric"]')
    v = pick_newest_release(fetch_json(f"{API}/project/fabric-api/version?{q}"))
    if not v:
        raise CoverError(f"Modrinth lists no Fabric API release for {mc_version}")
    f = next((x for x in v.get("files", []) if x.get("primary")), None) or (v.get("files") or [None])[0]
    if not f:
        raise CoverError(f"Fabric API {v.get('version_number')} has no file")
    return f["url"], f["filename"], f.get("hashes", {}).get("sha512", ""), v.get("version_number", "?")


def prepare(module_dir, mc_version, out):
    module_dir = os.path.abspath(module_dir)
    name = os.path.basename(module_dir)
    with open(LIVE, encoding="utf-8") as f:
        owner = covering_module(f.read(), mc_version)
    if owner != name:
        raise CoverError(f"{mc_version} is covered by module {owner!r}, not {name!r} (live-modules.json)")
    with open(CATALOG, encoding="utf-8") as f:
        mods = catalog_mods(f.read(), mc_version)
    jar = built_jar(module_dir)

    if os.path.exists(out):
        shutil.rmtree(out)
    os.makedirs(os.path.join(out, "mods"))
    # Same Gradle (wrapper), plugin repositories and Loom as the covering module.
    shutil.copytree(os.path.join(module_dir, "gradle"), os.path.join(out, "gradle"))
    for fn in ("gradlew", "gradlew.bat", "settings.gradle"):
        if os.path.exists(os.path.join(module_dir, fn)):
            shutil.copy2(os.path.join(module_dir, fn), os.path.join(out, fn))
    os.chmod(os.path.join(out, "gradlew"), 0o755)
    with open(os.path.join(module_dir, "gradle.properties"), encoding="utf-8") as f:
        props = gradle_properties(f.read(), mc_version)
    with open(os.path.join(out, "gradle.properties"), "w", encoding="utf-8", newline="\n") as f:
        f.write(props)
    with open(os.path.join(module_dir, "build.gradle"), encoding="utf-8") as f:
        gradle = build_gradle(f.read())
    with open(os.path.join(out, "build.gradle"), "w", encoding="utf-8", newline="\n") as f:
        f.write(gradle)

    loaded = []
    shutil.copy2(jar, os.path.join(out, "mods", os.path.basename(jar)))
    loaded.append(f"{os.path.basename(jar)} (built from src/mods/versions/{name})")
    for url, fn, sha in mods:
        _download(url, fn, sha, os.path.join(out, "mods"))
        loaded.append(f"{fn} (catalog, sha512 ok)")
    url, fn, sha, ver = fabric_api(mc_version)
    _download(url, fn, sha, os.path.join(out, "mods"))
    loaded.append(f"{fn} (Fabric API {ver}, newest release, sha512 ok)")
    return loaded


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("module")
    ap.add_argument("mc_version")
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    if not os.path.isfile(os.path.join(a.module, "build.gradle")):
        print(f"covers: no build.gradle in {a.module}", file=sys.stderr)
        return 2
    try:
        loaded = prepare(a.module, a.mc_version, os.path.abspath(a.out))
    except (CoverError, OSError) as e:
        print(f"::error title=covers {a.mc_version}::{e}")
        return 1
    print(f"covers: {a.mc_version} via module {os.path.basename(os.path.abspath(a.module))}: project at {a.out}, mods/:")
    for l in loaded:
        print(f"covers:   {l}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
