#!/usr/bin/env python3
"""Render title panoramas on this PC (real GPU), with far terrain, end to end.

  python tools/panorama/local_render.py <spot-id ...|all> [--far 96] [--install] [--keep-world]

Per spot (spots.json), in its capture module's dev game (src/mods/versions/<capture>/run, gitignored):
  1. fetch + verify (Modrinth sha512) the shader pack and the far-terrain mod pinned in spots.json
     ("shaderpack", "farTerrain") into tools/panorama/cache/ (gitignored; never shipped or committed);
     modules that take Sodium/Iris from libs/ get them through tools/ci/perf_libs.py;
  2. write the run folder: mods/<far-terrain jar>, shaderpacks/<pack> + <pack>.txt (spots.json options:
     the capture-safe settings), config/iris.properties, config/DistantHorizons.toml, options.txt;
     delete the old capture world;
  3. `gradlew runClient` with ORIGIN_SHOTS_SET=panorama + the spot (OriginDevPanorama builds the scene);
  4. watch the game log: once the camera is placed, Distant Horizons generates the land around it; when its
     progress log reports nothing left to generate (or --far-timeout passes) the gate file appears and the
     harness takes the 6 faces, then quits the game;
  5. finish.py (crop to exactly 90 degrees, shrink to faceSize, quantise), seam_check.py, contact sheet.
Output: tools/panorama/out/<id>/ (screenshots/, final/, run.log). --install copies final/ into the target
modules (install.py). Opens a Minecraft window per spot; nothing else on the PC is touched.
"""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
HERE = os.path.join(ROOT, "tools", "panorama")
CACHE = os.path.join(HERE, "cache")
UA = {"User-Agent": "originclient-panorama/1.0 (github.com/dozeyy/originclient)"}
JDK25 = os.path.expanduser(r"~\.jdks\jdk-25.0.3+9")
JDK21 = r"C:\Program Files\Java\jdk-21.0.10"
SETTLE = 60   # seconds between "far terrain generated" and the shots


def fetch(url, sha512, name):
    """A pinned download into cache/, verified by sha512 (cdn.modrinth.com only)."""
    assert url.startswith("https://cdn.modrinth.com/"), url
    os.makedirs(CACHE, exist_ok=True)
    dst = os.path.join(CACHE, name)
    if os.path.isfile(dst) and hashlib.sha512(open(dst, "rb").read()).hexdigest() == sha512:
        return dst
    data = urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=120).read()
    if hashlib.sha512(data).hexdigest() != sha512:
        sys.exit(f"local_render: {name}: sha512 mismatch")
    open(dst, "wb").write(data)
    print(f"local_render: fetched {name} ({len(data)} bytes)")
    return dst


def dh_toml(far):
    # Only the keys that matter; Distant Horizons fills in the rest on first load.
    return f"""[client]
\tqualityPresetSetting = "EXTREME"
\tthreadPresetSetting = "I_PAID_FOR_THE_WHOLE_CPU"
\tquickShowWorldGenProgress = false
\t[client.advanced.graphics.quality]
\t\tlodChunkRenderDistanceRadius = {far}
\t[client.advanced.graphics.fog]
\t\tenableDhFog = false
[common]
\t[common.multiThreading]
\t\tnumberOfThreads = 14
\t\tthreadRunTimeRatio = "1.0"
\t[common.worldGenerator]
\t\tenableDistantGeneration = true
\t\tchunkGeneratorMode = "FEATURES"
\t\tshowGenerationProgress = "LOG"
\t\tgenerationProgressDisplayIntervalInSeconds = 5
\t\tgenerationProgressIncludeChunksPerSecond = true
"""


def wants_far(table, spot):
    # Underground scenes (nether floor, caves) see no horizon: far terrain only costs time there (the nether's last
    # 16 chunks never finished, 2026-10-27).
    return spot.get("shader", True) and spot.get("ground") != "floor" and spot["capture"] in table["farTerrain"]


def prepare(table, spot, far, keep_world):
    mod = spot["capture"]
    mdir = os.path.join(ROOT, "src", "mods", "versions", mod)
    run = os.path.join(mdir, "run")
    for d in ("mods", "shaderpacks", "config"):
        os.makedirs(os.path.join(run, d), exist_ok=True)
    # Far terrain: exactly one pinned jar in run/mods.
    for f in os.listdir(os.path.join(run, "mods")):
        if f.lower().startswith(("distanthorizons", "voxy")):
            os.remove(os.path.join(run, "mods", f))
    far_pin = table["farTerrain"].get(mod) if wants_far(table, spot) else None
    if far_pin:
        jar = fetch(far_pin["url"], far_pin["sha512"], far_pin["file"])
        shutil.copyfile(jar, os.path.join(run, "mods", far_pin["file"]))
        open(os.path.join(run, "config", "DistantHorizons.toml"), "w", encoding="utf-8").write(dh_toml(far))
    pack = table["shaderpack"]
    if spot.get("shader", True):
        zp = fetch(pack["url"], pack["sha512"], pack["file"])
        shutil.copyfile(zp, os.path.join(run, "shaderpacks", pack["file"]))
        opts = "".join(f"{k}={v}\n" for k, v in pack.get("options", {}).items())
        open(os.path.join(run, "shaderpacks", pack["file"] + ".txt"), "w", encoding="utf-8").write(opts)
        open(os.path.join(run, "config", "iris.properties"), "w", encoding="utf-8").write(
            f"enableShaders=true\nshaderPack={pack['file']}\n")
    open(os.path.join(run, "options.txt"), "w", encoding="utf-8").write(
        "onboardAccessibility:false\npauseOnLostFocus:false\nrenderDistance:16\nsimulationDistance:12\n"
        "fullscreen:false\nnarrator:0\nsoundCategory_master:0.0\n")
    if not keep_world:
        shutil.rmtree(os.path.join(run, "saves"), ignore_errors=True)
    if os.path.isdir(os.path.join(mdir, "libs")) or mod != "1.21.1":
        subprocess.run([sys.executable, os.path.join(ROOT, "tools", "ci", "perf_libs.py"), mdir], check=False)
    return mdir, run


# Distant Horizons' LOG progress line. Seen forms differ by version, so match loosely: any line from DH's
# world generator that carries a "remaining"/"left" count; 0 (or the line stopping) = done.
DH_LINE = re.compile(r"(?i)(distant|world ?gen|generat).*?(\d[\d,]*)\s*(chunks?\s*)?(remaining|left|to go)")


def render(table, spot, out, far, far_timeout, keep_world):
    sid = spot["id"]
    mdir, run = prepare(table, spot, far, keep_world)
    sdir = os.path.join(out, sid)
    shutil.rmtree(sdir, ignore_errors=True)
    os.makedirs(os.path.join(sdir, "screenshots"))
    gate = os.path.join(sdir, "far.ok")
    env = dict(os.environ)
    env.update(ORIGIN_SHOTS=sdir, ORIGIN_SHOTS_SET="panorama", ORIGIN_PANO_WAIT=gate,
               ORIGIN_PANO_SPOT=json.dumps(spot, separators=(",", ":")))
    java = JDK25 if spot["capture"].startswith("26.") else JDK21
    env["JAVA_HOME"] = java
    if not wants_far(table, spot):
        open(gate, "w").close()
    logp = os.path.join(sdir, "run.log")
    t0 = time.time()
    with open(logp, "w", encoding="utf-8", errors="replace") as log:
        proc = subprocess.Popen([os.path.join(mdir, "gradlew.bat"), "runClient", "-PbootJvmArgs=-Xmx12G"],
                                cwd=mdir, env=env, stdout=log, stderr=subprocess.STDOUT)
    camera_at = None
    last_dh = None
    last_left = None
    last_print = 0
    last_change = None
    settle_from = None
    pos = 0
    while proc.poll() is None:
        time.sleep(2)
        with open(logp, encoding="utf-8", errors="replace") as f:
            f.seek(pos)
            new = f.read()
            pos = f.tell()
        for line in new.splitlines():
            if "[panorama]" in line or "[dev-shots]" in line:
                print(f"  {sid} {time.time() - t0:5.0f}s {line[line.find('['):][:200]}")
            if "[panorama] camera at" in line:
                camera_at = time.time()
            m = DH_LINE.search(line)
            if m and camera_at:
                left = int(m.group(2).replace(",", ""))
                if left != last_left:
                    last_change = time.time()
                last_left = left
                if not last_dh or time.time() - last_print > 60:
                    last_print = time.time()
                    print(f"  {sid} {time.time() - t0:5.0f}s DH: {last_left} chunks left", flush=True)
                last_dh = time.time()
        if camera_at and not os.path.exists(gate):
            waited = time.time() - camera_at
            # Done: nothing left, the progress lines stopped, or the count froze for 90 s (a few chunks DH never finishes).
            done = last_left == 0 or (last_dh and time.time() - last_dh > 45 and waited > 90)                 or (last_change and time.time() - last_change > 90 and last_left is not None and last_left < 500)
            if (done or waited > far_timeout) and not settle_from:
                print(f"  {sid}: far terrain {'done' if done else 'TIMED OUT'} after {waited:.0f}s; settling {SETTLE}s")
                settle_from = time.time()
            # DH keeps building and uploading LODs after generation ends: faces shot right away showed half-built
            # mountain "curtains" that differed face to face (1.20 seam 12.7, 2026-10-27). Let it settle first.
            if settle_from and time.time() - settle_from > SETTLE:
                open(gate, "w").close()
        if time.time() - t0 > far_timeout + 1800:
            print(f"  {sid}: giving up, killing the game")
            subprocess.run(["taskkill", "/T", "/F", "/PID", str(proc.pid)], capture_output=True)
    print(f"local_render: {sid}: game exited {proc.returncode} after {time.time() - t0:.0f}s")
    shots = os.path.join(sdir, "screenshots")
    fin = os.path.join(sdir, "final")
    subprocess.run([sys.executable, os.path.join(HERE, "finish.py"), shots, fin, "--size", str(table["faceSize"])])
    subprocess.run([sys.executable, os.path.join(HERE, "seam_check.py"), fin])
    subprocess.run([sys.executable, os.path.join(ROOT, "tools", "lean", "contact_sheet.py"), fin])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("spots", nargs="+")
    ap.add_argument("--far", type=int, default=96, help="Distant Horizons radius in chunks")
    ap.add_argument("--far-timeout", type=int, default=900, help="max seconds to wait for far terrain")
    ap.add_argument("--out", default=os.path.join(HERE, "out"))
    ap.add_argument("--install", action="store_true")
    ap.add_argument("--keep-world", action="store_true")
    a = ap.parse_args()
    table = json.load(open(os.path.join(HERE, "spots.json"), encoding="utf-8"))
    spots = {s["id"]: s for s in table["spots"]}
    ids = list(spots) if a.spots == ["all"] else a.spots
    for sid in ids:
        render(table, spots[sid], a.out, a.far, a.far_timeout, a.keep_world)
        if a.install:
            inst = os.path.join(a.out, "_install", f"panorama-{sid}")
            shutil.rmtree(inst, ignore_errors=True)
            shutil.copytree(os.path.join(a.out, sid), inst)
            subprocess.run([sys.executable, os.path.join(HERE, "install.py"), os.path.join(a.out, "_install"), sid])


if __name__ == "__main__":
    main()
