#!/usr/bin/env python3
"""Production-like Origin 1.8.9 game for benchmarks: the stack a player runs, started by hand.

Why this exists: the dev client (gradlew runClient) has no OptiFine and OptiFine cannot load in a
dev workspace, so a dev number measures a renderer no player uses. This script builds the real
thing in a throwaway folder and starts it with the benchmark switched on:

    Forge 11.15.1.2318 universal   (src/OriginLauncher.App/Assets/Forge/forge-1.8.9.json + the same maven file)
    OptiFine HD_U_M5               (same SHA-1 pin and download chain as OptiFineInstaller.cs; never stored in git)
    TexFix 4.0 + BetterFps 1.2.0   (same CurseForge pins as LegacyStackInstaller.cs)
    the BUILT Origin jar           (gradlew remapJar -> build/libs/originclient-1.8.9-*.jar)
    a Java 8                       (CI: Temurin 8 from setup-java; Will's PC: --java, default the launcher's Temurin 8u504 if found)

Variants (what goes in the mods folder):
    origin-default   OptiFine + TexFix + BetterFps + the Origin jar, exactly as shipped
    optifine-only    the same, but Origin is inert: ORIGIN_BENCH_PROBE=1 and a jar copy with the Mixin
                     tweaker removed from its manifest, so the only Origin code that runs is the
                     benchmark itself (frame times come from the same probe in both variants)

Everything stays under --work (default artifacts/bench-189/). It never reads or writes the launcher's
data (%LocalAppData%\\OriginLauncher), worlds, accounts or options. Nothing here is a number: it only
builds, starts and collects. Numbers come from OriginBench -> summarize.py.

    prod189.py run --variant origin-default --origin-jar X.jar --java /path/java --out DIR --scenes idle,flythrough
    prod189.py run --variant optifine-only --origin-jar X.jar --java ... --out DIR --save-options
        (--save-options also leaves a fresh options.txt + optionsof.txt in DIR: the U6.0 key dump)

Tests: tools/bench/test_prod189.py (no network).
"""
import argparse
import concurrent.futures
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
FORGE_JSON = os.path.join(ROOT, "src", "OriginLauncher.App", "Assets", "Forge", "forge-1.8.9.json")

MC = "1.8.9"
FORGE_VERSION_ID = "1.8.9-forge1.8.9-11.15.1.2318-1.8.9"
FORGE_UNIVERSAL_URL = ("https://maven.minecraftforge.net/net/minecraftforge/forge/1.8.9-11.15.1.2318-1.8.9/"
                       "forge-1.8.9-11.15.1.2318-1.8.9-universal.jar")
MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
ASSET_URL = "https://resources.download.minecraft.net/"
DEFAULT_LIB_REPOS = ["https://libraries.minecraft.net/", "https://maven.minecraftforge.net/",
                     "https://repo1.maven.org/maven2/"]

# OptiFineInstaller.cs Builds["1.8.9"]: the final 1.8.9 build, SHA-1 pinned.
OPTIFINE_FILE = "OptiFine_1.8.9_HD_U_M5.jar"
OPTIFINE_SHA1 = "d362d58a28f5373b141b9e426e8e160638bfafcd"
OPTIFINE_MIRRORS = ["https://bmclapi2.bangbang93.com/optifine/1.8.9/HD_U/M5"]
OPTIFINE_OFFICIAL_PAGE = "https://optifine.net/adloadx?f=" + OPTIFINE_FILE

# LegacyStackInstaller.cs Stacks["1.8.9"]: exact CurseForge CDN files (no published hash; recorded in stack.json).
STACK_MODS = [
    ("TexFix-1.8.9-4.0.jar", "https://edge.forgecdn.net/files/2540/156/TexFix%20V-1.8.9-4.0.jar"),
    ("BetterFps-1.2.0.jar", "https://edge.forgecdn.net/files/2271/480/BetterFps-1.2.0.jar"),
]

# TemurinJava8.cs Pin (Windows x64 JRE, SHA-256 verified): what the launcher gives a 1.8.9 player on Windows.
TEMURIN8_WIN = {
    "folder": "temurin-8u504-b01",
    "url": "https://github.com/adoptium/temurin8-binaries/releases/download/jdk8u504-b01/"
           "OpenJDK8U-jre_x64_windows_hotspot_8u504b01.zip",
    "sha256": "82e2cdc6693737c5998445b31f69668fa0da77c7705121053f6508ac84961123",
}

# JvmArgPresets.LegacySmallHeapFlags + RamAdvice.LegacyAutoMb (4096 MB): the flags a 1.8.9 player gets.
LAUNCHER_JVM = ("-Xms4096M -Xmx4096M -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 "
                "-XX:+UnlockExperimentalVMOptions -XX:+DisableExplicitGC -XX:G1NewSizePercent=25 "
                "-XX:G1MaxNewSizePercent=35 -XX:G1HeapRegionSize=4M -XX:G1ReservePercent=20 "
                "-XX:G1HeapWastePercent=5 -XX:G1MixedGCCountTarget=4 -XX:InitiatingHeapOccupancyPercent=15 "
                "-XX:G1MixedGCLiveThresholdPercent=90 -XX:G1RSetUpdatingPauseTimePercent=5 "
                "-XX:SurvivorRatio=32 -XX:+PerfDisableSharedMem -XX:MaxTenuringThreshold=1")

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) OriginBench"
VARIANTS = ("origin-default", "optifine-only")


class ProvisionError(Exception):
    """Something the run needs could not be fetched or verified. Never papered over."""


def log(msg):
    sys.stdout.write("[prod189] %s\n" % msg)
    sys.stdout.flush()


# ----------------------------------------------------------------------------------------------
# Pure helpers (unit-tested)
# ----------------------------------------------------------------------------------------------

def os_name(platform=None):
    p = platform or sys.platform
    if p.startswith("win"):
        return "windows"
    if p == "darwin":
        return "osx"
    return "linux"


def maven_path(coord, classifier=None, ext="jar"):
    """'group:artifact:version[:classifier]' -> 'group/dir/artifact/version/artifact-version[-classifier].ext'."""
    parts = coord.split(":")
    if len(parts) == 4 and classifier is None:
        classifier = parts[3]
    if len(parts) < 3:
        raise ProvisionError("bad library name %r" % coord)
    group, artifact, version = parts[0], parts[1], parts[2]
    name = "%s-%s%s.%s" % (artifact, version, "-" + classifier if classifier else "", ext)
    return "%s/%s/%s/%s" % (group.replace(".", "/"), artifact, version, name)


def lib_key(coord, classifier=None):
    parts = coord.split(":")
    if len(parts) == 4 and classifier is None:
        classifier = parts[3]
    return ":".join(parts[:2]) + (":" + classifier if classifier else "")


def rules_allow(rules, osn):
    """Mojang library rules: no rules = allowed; the last matching rule decides."""
    if not rules:
        return True
    allowed = False
    for rule in rules:
        osr = rule.get("os")
        if osr is not None:
            if osr.get("name") not in (None, osn):
                continue
            if "version" in osr or "arch" in osr:
                # 1.8.9 has no such rules; ignore rather than guess a Windows version match
                pass
        allowed = rule.get("action") == "allow"
    return allowed


def merge_libraries(child, parent):
    """inheritsFrom merge: the child's libraries first, then the parent's that the child does not replace."""
    seen = set()
    out = []
    for lib in list(child) + list(parent):
        k = lib_key(lib["name"])
        if k in seen:
            continue
        seen.add(k)
        out.append(lib)
    return out


def fill_args(template, values):
    """'--a ${x} --b ${y}' + {'x':1} -> list of args. A placeholder with no value is an error, not an empty string."""
    out = []
    for tok in template.split():
        m = re.fullmatch(r"\$\{(\w+)\}", tok)
        if m:
            if m.group(1) not in values:
                raise ProvisionError("no value for ${%s}" % m.group(1))
            out.append(str(values[m.group(1)]))
        else:
            out.append(tok)
    return out


def sha1_file(path):
    h = hashlib.sha1()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def probe_jar(src, dst):
    """Copy of the Origin jar with the Mixin tweaker entries removed from its manifest.

    Without TweakClass / MixinConfigs LaunchWrapper never starts Mixin, so none of Origin's 6 mixin
    configs apply; Forge still loads the jar as a normal mod, and with ORIGIN_BENCH_PROBE=1 the mod
    class initialises only the benchmark. That is the "Origin inert" baseline.
    """
    drop = ("TweakClass", "TweakOrder", "MixinConfigs", "ForceLoadAsMod", "FMLCorePluginContainsFMLMod")
    with zipfile.ZipFile(src) as zin:
        names = zin.namelist()
        if "META-INF/MANIFEST.MF" not in names:
            raise ProvisionError("%s has no META-INF/MANIFEST.MF" % src)
        raw = zin.read("META-INF/MANIFEST.MF").decode("utf-8")
        # continuation lines start with one space; unfold, drop, refold is unnecessary: write plain lines
        lines = raw.replace("\r\n", "\n").replace("\n ", "").split("\n")
        kept = [ln for ln in lines if ln.strip() and ln.split(":", 1)[0] not in drop]
        if not any(ln.startswith("Manifest-Version") for ln in kept):
            kept.insert(0, "Manifest-Version: 1.0")
        new_manifest = ("\r\n".join(kept) + "\r\n\r\n").encode("utf-8")
        with zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as zout:
            zout.writestr("META-INF/MANIFEST.MF", new_manifest)
            for n in names:
                if n == "META-INF/MANIFEST.MF" or n.startswith("META-INF/") and n.endswith((".SF", ".DSA", ".RSA")):
                    continue
                zout.writestr(zin.getinfo(n), zin.read(n))
    return dst


def find_origin_jar(path_or_dir):
    """The shippable jar: build/libs/originclient-1.8.9-<ver>.jar (not -sources, not intermediates)."""
    if os.path.isfile(path_or_dir):
        return path_or_dir
    cands = [os.path.join(path_or_dir, f) for f in os.listdir(path_or_dir)
             if f.startswith("originclient-1.8.9-") and f.endswith(".jar") and "sources" not in f]
    if len(cands) != 1:
        raise ProvisionError("expected exactly one originclient-1.8.9-*.jar in %s, found %d" % (path_or_dir, len(cands)))
    return cands[0]


# ----------------------------------------------------------------------------------------------
# Network
# ----------------------------------------------------------------------------------------------

def http_get(url, timeout=60, tries=3):
    last = None
    for i in range(tries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return r.read()
        except (urllib.error.URLError, OSError) as e:
            last = e
            if isinstance(e, urllib.error.HTTPError) and e.code in (403, 404, 410):
                break
            time.sleep(1.5 * (i + 1))
    raise ProvisionError("GET %s failed: %s" % (url, last))


def fetch_verified(urls, dest, sha1=None, get=http_get):
    """First url whose bytes match sha1 (when given) is written to dest. Returns the url used."""
    errors = []
    for url in urls:
        try:
            data = get(url)
        except ProvisionError as e:
            errors.append(str(e))
            continue
        if sha1 and hashlib.sha1(data).hexdigest() != sha1.lower():
            errors.append("%s: SHA-1 mismatch" % url)
            continue
        os.makedirs(os.path.dirname(os.path.abspath(dest)), exist_ok=True)
        tmp = dest + ".part"
        with open(tmp, "wb") as f:
            f.write(data)
        os.replace(tmp, dest)
        return url
    raise ProvisionError("no source worked for %s:\n  %s" % (os.path.basename(dest), "\n  ".join(errors)))


def ensure_file(dest, urls, sha1=None, get=http_get):
    if os.path.isfile(dest) and (not sha1 or sha1_file(dest) == sha1.lower()):
        return dest
    fetch_verified(urls, dest, sha1, get)
    return dest


def optifine_urls(get=http_get):
    """Official download page first (one-time token link), then the mirrors; all behind the SHA-1 gate."""
    urls = []
    try:
        page = get(OPTIFINE_OFFICIAL_PAGE).decode("utf-8", "replace")
        m = re.search(r"downloadx\?f=[^'\"&]+&x=[0-9a-fA-F]+", page)
        if m:
            urls.append("https://optifine.net/" + m.group(0).replace("&amp;", "&"))
    except ProvisionError:
        pass
    return urls + OPTIFINE_MIRRORS


# ----------------------------------------------------------------------------------------------
# Provisioning the shared cache: Minecraft 1.8.9 + Forge libraries + assets + natives
# ----------------------------------------------------------------------------------------------

def load_vanilla_json(cache, get=http_get):
    path = os.path.join(cache, "versions", MC, MC + ".json")
    if os.path.isfile(path):
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    manifest = json.loads(get(MANIFEST_URL).decode("utf-8"))
    entry = next((v for v in manifest["versions"] if v["id"] == MC), None)
    if not entry:
        raise ProvisionError("Mojang manifest has no %s" % MC)
    data = get(entry["url"])
    if "sha1" in entry and hashlib.sha1(data).hexdigest() != entry["sha1"]:
        raise ProvisionError("%s version json SHA-1 mismatch" % MC)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(data)
    return json.loads(data.decode("utf-8"))


def load_forge_json():
    with open(FORGE_JSON, encoding="utf-8-sig") as f:
        return json.load(f)


def library_jobs(libs, osn):
    """-> (jobs [(relpath, [urls], sha1|None, key)], natives [(relpath, [urls], sha1|None, excludes)])."""
    jobs, natives = [], []
    for lib in libs:
        if not rules_allow(lib.get("rules"), osn):
            continue
        name = lib["name"]
        dl = lib.get("downloads", {})
        if lib.get("natives") and osn in lib["natives"]:
            classifier = lib["natives"][osn].replace("${arch}", "64")
            art = dl.get("classifiers", {}).get(classifier)
            if art:
                natives.append((art["path"], [art["url"]], art.get("sha1"),
                                lib.get("extract", {}).get("exclude", [])))
            continue
        if "artifact" in dl:
            art = dl["artifact"]
            jobs.append((art["path"], [art["url"]], art.get("sha1"), lib_key(name)))
        else:
            rel = maven_path(name)
            base = lib.get("url")
            urls = ([base.rstrip("/") + "/" + rel] if base else []) + [r + rel for r in DEFAULT_LIB_REPOS]
            if name.startswith("net.minecraftforge:forge:"):
                urls = [FORGE_UNIVERSAL_URL]      # the maven file carries -universal; the version json expects it without
            jobs.append((rel, urls, None, lib_key(name)))
    return jobs, natives


def download_assets(cache, vanilla, get=http_get, workers=16):
    idx = vanilla["assetIndex"]
    idx_path = os.path.join(cache, "assets", "indexes", idx["id"] + ".json")
    ensure_file(idx_path, [idx["url"]], idx.get("sha1"), get)
    with open(idx_path, encoding="utf-8") as f:
        objects = json.load(f)["objects"]
    todo = []
    for name, o in objects.items():
        h = o["hash"]
        dest = os.path.join(cache, "assets", "objects", h[:2], h)
        if not (os.path.isfile(dest) and os.path.getsize(dest) == o["size"]):
            todo.append((dest, ASSET_URL + h[:2] + "/" + h, h))
    log("assets: %d objects, %d to download" % (len(objects), len(todo)))
    if not todo:
        return
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as ex:
        futs = [ex.submit(fetch_verified, [u], d, h, get) for d, u, h in todo]
        for fu in futs:
            fu.result()       # raises ProvisionError on the first failure: a half-assets game is not a benchmark


def extract_natives(jar, dest, excludes):
    os.makedirs(dest, exist_ok=True)
    with zipfile.ZipFile(jar) as z:
        for n in z.namelist():
            if n.endswith("/") or any(n.startswith(e) for e in excludes):
                continue
            target = os.path.normpath(os.path.join(dest, n))
            if not target.startswith(os.path.normpath(dest) + os.sep):
                raise ProvisionError("native entry escapes folder: %s" % n)
            os.makedirs(os.path.dirname(target), exist_ok=True)
            with z.open(n) as src, open(target, "wb") as out:
                shutil.copyfileobj(src, out)


def provision_cache(cache, osn, get=http_get):
    """Everything that is the same for every run. Idempotent; second call downloads nothing."""
    vanilla = load_vanilla_json(cache, get)
    forge = load_forge_json()
    libs = merge_libraries(forge["libraries"], vanilla["libraries"])
    jobs, natives = library_jobs(libs, osn)
    lib_dir = os.path.join(cache, "libraries")
    cp = []
    for rel, urls, sha1, key in jobs:
        dest = os.path.join(lib_dir, *rel.split("/"))
        ensure_file(dest, urls, sha1, get)
        cp.append(dest)
    nat_dir = os.path.join(cache, "natives-" + osn)
    marker = os.path.join(nat_dir, ".done")
    if not os.path.isfile(marker):
        for rel, urls, sha1, excl in natives:
            jar = os.path.join(lib_dir, *rel.split("/"))
            ensure_file(jar, urls, sha1, get)
            extract_natives(jar, nat_dir, excl)
        with open(marker, "w") as f:
            f.write("ok\n")
    client = vanilla["downloads"]["client"]
    client_jar = os.path.join(cache, "versions", MC, MC + ".jar")
    ensure_file(client_jar, [client["url"]], client["sha1"], get)
    cp.append(client_jar)
    download_assets(cache, vanilla, get)
    return {"classpath": cp, "natives": nat_dir, "assets": os.path.join(cache, "assets"),
            "assetIndex": vanilla["assetIndex"]["id"], "mainClass": forge["mainClass"],
            "argsTemplate": forge["minecraftArguments"]}


# ----------------------------------------------------------------------------------------------
# One game folder + one launch
# ----------------------------------------------------------------------------------------------

def prepare_game_dir(game_dir, variant, origin_jar, optifine_jar, stack_jars, extra_files=()):
    """Fresh instance folder with this variant's mods. Returns the stack record that goes into stack.json."""
    if variant not in VARIANTS:
        raise ProvisionError("variant must be one of %s" % (VARIANTS,))
    mods = os.path.join(game_dir, "mods")
    os.makedirs(mods, exist_ok=True)
    record = {"variant": variant, "mods": []}

    def add(src, name):
        dst = os.path.join(mods, name)
        shutil.copyfile(src, dst)
        record["mods"].append({"file": name, "sha256": sha256_file(dst), "bytes": os.path.getsize(dst)})
    add(optifine_jar, "optifine.jar")         # the launcher's own file name for it
    for j in stack_jars:
        add(j, os.path.basename(j))
    if variant == "origin-default":
        add(origin_jar, os.path.basename(origin_jar))
    else:
        tmp = os.path.join(game_dir, "_probe.jar")
        probe_jar(origin_jar, tmp)
        add(tmp, os.path.basename(origin_jar))
        os.remove(tmp)
    for src, rel in extra_files:
        dst = os.path.join(game_dir, rel)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.copyfile(src, dst)
    return record


KEYS_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "optifine-keys-189.txt")


def load_key_list(path=KEYS_FILE):
    """Verified OptiFine M5 optionsof.txt keys (U6.0); '#' lines are comments. Empty set if the file is missing."""
    if not os.path.isfile(path):
        return set()
    with open(path, encoding="utf-8") as f:
        return {ln.strip().split(":")[0] for ln in f if ln.strip() and not ln.lstrip().startswith("#")}


def apply_preset(game_dir, preset_path, keys_path=KEYS_FILE):
    """Write a preset's {"options": {...}, "optionsof": {...}} into the fresh game folder.

    Presets may only write OptiFine keys that are in the verified list (U6.0): an unknown key, or a
    missing list, is an error here, never a silent guess. options.txt (vanilla) keys are not checked.
    """
    with open(preset_path, encoding="utf-8") as f:
        preset = json.load(f)
    if not isinstance(preset, dict) or not isinstance(preset.get("options", {}), dict) \
            or not isinstance(preset.get("optionsof", {}), dict):
        raise ProvisionError("%s: a preset is {\"options\": {...}, \"optionsof\": {...}}" % preset_path)
    of = preset.get("optionsof", {})
    if of:
        known = load_key_list(keys_path)
        if not known:
            raise ProvisionError("%s is missing or empty, so OptiFine keys cannot be verified" % keys_path)
        bad = sorted(k for k in of if k not in known)
        if bad:
            raise ProvisionError("%s: OptiFine keys not in %s: %s" % (preset_path, os.path.basename(keys_path), ", ".join(bad)))
    for name, body in (("options.txt", preset.get("options", {})), ("optionsof.txt", of)):
        if body:
            with open(os.path.join(game_dir, name), "w", encoding="utf-8", newline="\n") as f:
                f.write("".join("%s:%s\n" % (k, v) for k, v in body.items()))


def game_command(java, prov, game_dir, jvm_flags, width, height):
    values = {"auth_player_name": "OriginBench", "version_name": FORGE_VERSION_ID, "game_directory": game_dir,
              "assets_root": prov["assets"], "assets_index_name": prov["assetIndex"],
              "auth_uuid": "00000000-0000-0000-0000-000000000000", "auth_access_token": "0",
              "user_properties": "{}", "user_type": "legacy"}
    args = fill_args(prov["argsTemplate"], values) + ["--width", str(width), "--height", str(height)]
    sep = ";" if os.name == "nt" else ":"
    return ([java] + jvm_flags.split() +
            ["-Djava.library.path=" + prov["natives"], "-Dorg.lwjgl.librarypath=" + prov["natives"],
             "-Dminecraft.launcher.brand=origin-bench", "-Dminecraft.launcher.version=1",
             "-cp", sep.join(prov["classpath"]), prov["mainClass"]] + args)


def java_version(java):
    try:
        p = subprocess.run([java, "-version"], capture_output=True, text=True, timeout=30)
        return (p.stderr or p.stdout).strip().splitlines()[0]
    except Exception as e:                         # noqa: BLE001 - reported, not hidden
        raise ProvisionError("cannot run %s: %s" % (java, e))


def _find_java_exe(base):
    for dp, _, files in os.walk(base):
        if "java.exe" in files and os.path.basename(dp) == "bin":
            return os.path.join(dp, "java.exe")
    return None


def ensure_temurin8_windows(cache, get=http_get):
    """The launcher's pinned Temurin 8u504 JRE, downloaded into OUR cache (never the launcher's runtimes folder)."""
    base = os.path.join(cache, "java", TEMURIN8_WIN["folder"])
    found = _find_java_exe(base) if os.path.isdir(base) else None
    if found:
        return found
    zpath = os.path.join(cache, "java", TEMURIN8_WIN["folder"] + ".zip")
    os.makedirs(os.path.dirname(zpath), exist_ok=True)
    data = get(TEMURIN8_WIN["url"], timeout=300)
    if hashlib.sha256(data).hexdigest() != TEMURIN8_WIN["sha256"]:
        raise ProvisionError("Temurin 8u504 download failed its SHA-256 check")
    with open(zpath, "wb") as f:
        f.write(data)
    with zipfile.ZipFile(zpath) as z:
        z.extractall(base)
    os.remove(zpath)
    found = _find_java_exe(base)
    if not found:
        raise ProvisionError("no java.exe inside the Temurin 8 zip")
    return found


def run_game(cmd, env, game_dir, out_dir, timeout):
    os.makedirs(out_dir, exist_ok=True)
    game_log = os.path.join(out_dir, "game.log")
    with open(game_log, "wb") as lf:
        p = subprocess.Popen(cmd, cwd=game_dir, env=env, stdout=lf, stderr=subprocess.STDOUT)
        try:
            code = p.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            p.kill()
            p.wait()
            code = "timeout"
    return code, game_log


def bench_env(args, variant, out_dir, build_label):
    env = dict(os.environ)
    env.update({
        "ORIGIN_BENCH": args.scenes,
        "ORIGIN_BENCH_OUT": out_dir,
        "ORIGIN_BENCH_WARMUP": str(args.warmup),
        "ORIGIN_BENCH_DURATION": str(args.duration),
        "ORIGIN_BENCH_SIZE": "%dx%d" % (args.width, args.height),
        "ORIGIN_BENCH_BUILD": build_label,
        "ORIGIN_BENCH_VARIANT": variant,
        "ORIGIN_BENCH_PROBE": "1" if variant == "optifine-only" else "0",
        "ORIGIN_BENCH_SHOTS": "1" if args.shots else "0",
        "ORIGIN_BENCH_SAVE_OPTIONS": "1" if getattr(args, "save_options", False) else "0",
    })
    return env


def cmd_run(args):
    """Provision (cached) -> fresh game folder -> start -> collect bench.json etc. into --out."""
    osn = os_name()
    cache = os.path.abspath(args.cache)
    os.makedirs(cache, exist_ok=True)
    origin_jar = find_origin_jar(args.origin_jar)
    java = args.java or (ensure_temurin8_windows(cache) if osn == "windows" else None)
    if not java:
        raise ProvisionError("no Java 8 given (--java); only Windows can fetch the pinned Temurin 8 itself")
    jver = java_version(java)
    if '"1.8.' not in jver:
        raise ProvisionError("the game needs Java 8, got: %s" % jver)

    prov = provision_cache(cache, osn)
    # downloads (OptiFine!) live beside the work folder, never inside the cache a CI job may save
    mods_dir = os.path.join(os.path.abspath(args.work), "downloads")
    optifine = os.path.join(mods_dir, OPTIFINE_FILE)
    if not (os.path.isfile(optifine) and sha1_file(optifine) == OPTIFINE_SHA1):
        ensure_file(optifine, optifine_urls(), OPTIFINE_SHA1)
    stack = [ensure_file(os.path.join(mods_dir, n), [u]) for n, u in STACK_MODS]

    out = os.path.abspath(args.out)
    game_dir = os.path.join(os.path.abspath(args.work), "game-%s-%d" % (args.variant, os.getpid()))
    if os.path.isdir(game_dir):
        shutil.rmtree(game_dir)
    record = prepare_game_dir(game_dir, args.variant, origin_jar, optifine, stack)
    if args.preset:
        apply_preset(game_dir, args.preset)
        record["preset"] = os.path.basename(args.preset)
    record.update({"java": jver, "javaPath": java, "os": osn, "optifineSha1": sha1_file(optifine),
                   "forge": "11.15.1.2318", "jvmFlags": args.jvm, "label": args.label,
                   "originJar": os.path.basename(origin_jar)})
    build_label = "%s (%s)" % (os.path.basename(origin_jar), args.label)
    cmd = game_command(java, prov, game_dir, args.jvm, args.width, args.height)
    log("start %s -> %s" % (args.variant, out))
    code, game_log = run_game(cmd, bench_env(args, args.variant, out, build_label), game_dir, out, args.timeout)
    os.makedirs(out, exist_ok=True)
    with open(os.path.join(out, "stack.json"), "w", encoding="utf-8") as f:
        json.dump(record, f, indent=2)
    # a fresh optionsof.txt / options.txt (U6.0) goes next to the results when the run wrote them
    for n in ("optionsof.txt", "options.txt"):
        src = os.path.join(game_dir, n)
        if os.path.isfile(src):
            shutil.copyfile(src, os.path.join(out, n))
    if not args.keep_game and os.path.isdir(game_dir):
        shutil.rmtree(game_dir, ignore_errors=True)
    ok = os.path.isfile(os.path.join(out, "bench.json"))
    log("game exit %s, bench.json %s" % (code, "written" if ok else "MISSING (see %s)" % game_log))
    return 0 if ok else 1


def build_parser():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("run", help="provision (cached) and run one bench launch")
    r.add_argument("--variant", choices=VARIANTS, default="origin-default")
    r.add_argument("--origin-jar", required=True, help="the jar, or build/libs")
    r.add_argument("--java", help="Java 8 executable (default on Windows: the launcher's pinned Temurin 8u504, fetched into the cache)")
    r.add_argument("--work", default=os.path.join(ROOT, "artifacts", "bench-189", "work"))
    r.add_argument("--cache", default=os.path.join(ROOT, "artifacts", "bench-189", "cache"))
    r.add_argument("--out", required=True, help="where bench.json / frames-*.csv / game.log land")
    r.add_argument("--scenes", default="idle,particles,menu,flythrough")
    r.add_argument("--warmup", type=float, default=15)
    r.add_argument("--duration", type=float, default=45)
    r.add_argument("--width", type=int, default=1920)
    r.add_argument("--height", type=int, default=1080)
    r.add_argument("--jvm", default=LAUNCHER_JVM)
    r.add_argument("--label", default="")
    r.add_argument("--timeout", type=int, default=900)
    r.add_argument("--shots", action="store_true")
    r.add_argument("--save-options", action="store_true", help="write options.txt/optionsof.txt before quitting (U6.0)")
    r.add_argument("--preset", help="preset JSON (options / optionsof); OptiFine keys must be in optifine-keys-189.txt")
    r.add_argument("--keep-game", action="store_true")
    return ap


def main(argv=None):
    args = build_parser().parse_args(argv)
    try:
        return cmd_run(args)
    except ProvisionError as e:
        sys.stderr.write("prod189: FAILED: %s\n" % e)
        return 2


if __name__ == "__main__":
    sys.exit(main())
