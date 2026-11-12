"""Records the Origin Client launch ad's in-game clips (R2-R8) in a dev copy of the game. Dev-only, branch claude/ad-capture.

    python tools/ad-capture/capture.py R2 R3 ...          record clips into --out (default origin-client-ad/footage/raw)
    python tools/ad-capture/capture.py R2 --probe          stills along each move instead of video (check the spots)
    python tools/ad-capture/capture.py R3 --set ORIGIN_AD_PACK_X=1330 --set ORIGIN_AD_PACK_Y=330
    python tools/ad-capture/capture.py encode              raw takes -> edit/public/footage/R*.mp4 (30 fps)
    python tools/ad-capture/capture.py R11 --far 128       Distant Horizons 3.3.3 in the dev run, 128-chunk LODs; the
                                                           take starts only once DH has generated the land and settled

Rev 4 order: R10 (downloads BSL, Photon, Solas for real) -> R2 pack flights (ORIGIN_AD_FLIGHTS=bsl,photon,solas, each
ORIGIN_AD_PACK_<NAME>) -> R2 far flight and R11 with --far -> R12 (mod menu stills, stitched by v2/capture/stitch_menu.py).

Per clip: writes the 1.21.1 module's dev run folder (run/, gitignored: shader pack + options, iris.properties,
options.txt), then runs `gradlew runClient` under the machine-wide verify lock with ORIGIN_SHOTS_SET=ad and
ORIGIN_AD_CLIP=<clip>. OriginDevAd (in the mod) opens the fixed world, stages the clip, scripts every input inside the
game and records the game's own frames with ffmpeg (OriginDevAdCapture), then quits. The window opens unfocused, the
real mouse and keyboard are cut off from it, and the OS pointer is never moved or grabbed.

Never touches %LocalAppData%\\OriginLauncher, Will's account or saves: the run folder is the module's own dev game.
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

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
MDIR = os.path.join(ROOT, "src", "mods", "versions", "1.21.1")
RUN = os.path.join(MDIR, "run")
JDK21 = r"C:\Program Files\Java\jdk-21.0.10"
LOCK = os.path.join(os.path.expanduser("~"), ".claude", "scripts", "verify-lock.ps1")
AD = r"C:\Users\Will\origin-client-ad"
CACHE = os.path.join(HERE, "cache")
SPOTS = os.path.join(ROOT, "tools", "panorama", "spots.json")

# Clips that start without a shader pack (R3 downloads and applies one in game, for real).
NO_SHADER = {"R3"}   # R9 keeps the shaders on under its key colour so its FPS readout is a shaded world's real number
# Clips that wipe shaderpacks/ and Origin's download index first (their in-game downloads must be real, not "installed").
# Every other clip keeps the packs R10 downloaded (the rev 4 pack flights switch to them) and re-copies Complementary.
FRESH_PACKS = {"R3", "R10"}
# How long a clip may run before the game is killed (world creation + chunk waits included).
LIMIT = {"R2": 3600, "R8": 900, "R11": 3000}
# Rev 4 far flight (Distant Horizons): the peaks run raised well above the ridges, looking out level-ish.
FAR_RUN = "47,-47,81,-81,250,3"
# Distant Horizons for far terrain (the panorama path's pin; Voxy has no Origin 1.21.1 build: Will's port needs
# Sodium 0.6.13 / Iris 1.8.8, Origin ships Sodium 0.8.13 / Iris 1.8.14).
UA = {"User-Agent": "originclient-adcapture/1.0"}
DH_LINE = re.compile(r"(?i)(distant|world ?gen|generat).*?(\d[\d,]*)\s*(chunks?\s*)?(remaining|left|to go)")
SETTLE = 60
# Per-clip settings. R2's water run: the river stretch from the second probe (seed terrain, picked by eye: the
# scorer's best run kept ending at a lava-lit canyon wall).
# R6: the mod menu's EDIT HUD button, then Key Strokes and CPS dragged from the top-left stack to above the hotbar's
# left (grab x,y, drop x,y in window px, read off the first take: the HUD editor draws no click targets of its own).
# R2: the flights in order (OriginDevAd.flight), then the river run again with shaders off for a before/after wipe.
# Rev 3 flights: each one a straight run picked by eye from the scout stills (ORIGIN_AD_SCOUT, footage/scout/screenshots),
# RUN = start x,z -> end x,z at camera y, pitch. Montage order: jungle, badlands, ocean, lush, peaks2, cherry2.
CLIP_ENV = {"R2": {"ORIGIN_AD_FLIGHTS": "hero,peaks,cherry,coast,jungle,badlands,ocean,lush,peaks2,cherry2",
                   "ORIGIN_AD_RUN_HERO": "1292,1268,1340,1268,66.2,4",          # scout river#1: river into a forest
                   "ORIGIN_AD_RUN_PEAKS": "47,-47,81,-81,239,18",                 # scout peaks#3: jagged peaks
                   "ORIGIN_AD_RUN_CHERRY": "-1185,-873,-1151,-839,130,13",       # scout cherry#2
                   "ORIGIN_AD_RUN_COAST": "2489,-545,2455,-511,73,5",            # scout coast#2: islands at sunset
                   "ORIGIN_AD_TIME_COAST": "12300",
                   "ORIGIN_AD_RUN_JUNGLE": "-721,-769,-687,-735,108,9",          # probe 10800
                   "ORIGIN_AD_RUN_BADLANDS": "-113,-1865,-79,-1831,66.2,4",      # scout river#2: badlands over still water
                   "ORIGIN_AD_RUN_OCEAN": "-1267,-1235,-1301,-1269,68,2",        # scout ocean#4
                   "ORIGIN_AD_RUN_LUSH": "398,-165,398,-221,66.2,4",             # the first cut's river run
                   "ORIGIN_AD_RUN_PEAKS2": "-1673,129,-1639,95,165,7",           # scout peaks#4: snowy slopes, ice
                   "ORIGIN_AD_RUN_CHERRY2": "-1720,-24,-1720,24,126,13",         # scout cherry#5: cherry under a snowy peak
                   "ORIGIN_AD_VANILLA": "hero",
                   "ORIGIN_AD_TIME": "10800"},   # earlier than 11800: blue sky + warm light (probe 2026-11-11)
            "R6": {"ORIGIN_AD_MENU_ONLY": "1"},   # rev 3: the mod menu alone (the old HUD-editor drags: ORIGIN_AD_EDIT/_KEYS/_CPS)
            "R9": {"ORIGIN_AD_LOSSLESS": "1"},
            # rev 4 shot 9: Origin's Download Shaders browser over the hero vantage, three real downloads
            "R10": {"ORIGIN_AD_RUN_HERO": "1292,1268,1340,1268,66.2,4"},
            # rev 4 shot 10: FPS Coach TRY IT at the far flight's first pose, keyed FPS module (lossless for the key)
            "R11": {"ORIGIN_AD_LOSSLESS": "1", "ORIGIN_AD_RUN_FAR": FAR_RUN},
            "R12": {}}
# Film v3 (2026-11-12, v3/SCRIPT.md shots 7-17): golden hour (10800) at the hero vantage; each clip's script lives in
# OriginDevAdV3. V3_LOAD runs twice (a stage run parks the player at the hero view, then the take records the join).
V3 = ["V3_LOAD", "V3_TITLE", "V3_HUD", "V3_ITEMSIZE", "V3_WAYPOINT", "V3_SNAPLOOK", "V3_FRIENDS", "V3_SCREENSHOT"]
for _c in V3:
    CLIP_ENV[_c] = {"ORIGIN_AD_TIME": "10800", "ORIGIN_AD_RUN_HERO": "1292,1268,1340,1268,66.2,4"}
    LIMIT[_c] = 1500   # opening origin-ad took 5 min on a busy machine (2026-11-12)
# V3_HUD: the four modules the editor drags, without backgrounds; Armor Status as a column (flush left in the stack)
V3_HUD_OPTS = {"fps": {}, "cps": {}, "coords": {}, "armorhud": {"listMode": "Vertical", "durabilityPos": "Right"}}
# Rev 4 pack flights (R2 with --set): each pack in its own scene, the pack switched on before the flight.
PACK_FLIGHTS = {"ORIGIN_AD_FLIGHTS": "bsl,photon,solas", "ORIGIN_AD_VANILLA": " ",
                "ORIGIN_AD_RUN_BSL": "-1720,-24,-1720,24,126,13", "ORIGIN_AD_PACK_BSL": "bsl",
                "ORIGIN_AD_RUN_PHOTON": "-113,-1865,-79,-1831,66.2,4", "ORIGIN_AD_PACK_PHOTON": "photon",
                "ORIGIN_AD_RUN_SOLAS": "2489,-545,2455,-511,73,5", "ORIGIN_AD_PACK_SOLAS": "solas", "ORIGIN_AD_TIME_SOLAS": "12300"}
# R11: the FPS module alone, big, no background (v2/capture/key_fps.py keys it out)
R11_HUD = {"fps": [0, 10, 10, 4.0, 0]}
# Complementary settings per clip on top of the base ones. R2: clearer air for the landscapes (the default fog turned
# the first cut's peaks into a beige haze): less atmospheric fog, pushed further out.
SHADER_OPTS = {"R2": "ATM_FOG_MULT=0.65\nATM_FOG_DISTANCE=200\n"}
# R9: where each HUD module sits in the keyed take (the edit crops them by these, see v2/capture/key_hud.py)
R9_HUD = {"fps": [0, 10, 10, 2.0, 0], "cps": [0, 10, 40, 2.0, 0], "clock": [0, 10, 70, 2.0, 0], "memory": [0, 10, 100, 2.0, 0],
          "coords": [0, 10, 140, 2.0, 0], "potionhud": [0, 330, 10, 2.0, 0], "armorhud": [0, 330, 150, 2.0, 0]}
# Module options for R9: the real defaults, plus Armor Status as a column (it sits flush on the stack's left edge)
R6_ON = set(R9_HUD) | {"armorhud", "potionhud", "oldanimations", "blockoverlay"}
R9_OPTS = {"armorhud": {"listMode": "Vertical", "durabilityPos": "Right"}}
# The ad's footage names -> raw take files (R2 is three flights joined in order).
FOOTAGE = {"R3": "R3", "R4": "R4", "R5": "R5", "R6": "R6", "R7": "R7", "R8": "R8", "R10": "R10", "R11": "R11"}   # R9 is keyed


def shader_zip():
    pack = json.load(open(SPOTS, encoding="utf-8"))["shaderpack"]
    path = os.path.join(CACHE, pack["file"])
    if not os.path.exists(path):
        sys.exit(f"capture: put {pack['file']} (sha512 {pack['sha512'][:16]}...) in {CACHE}\n  (Modrinth: {pack['url']})")
    h = hashlib.sha512(open(path, "rb").read()).hexdigest()
    if h != pack["sha512"]:
        sys.exit(f"capture: {path} sha512 mismatch")
    return pack["file"], path


def fetch(url, sha512, name):
    """A pinned download into cache/, verified by sha512 (cdn.modrinth.com only)."""
    assert url.startswith("https://cdn.modrinth.com/"), url
    os.makedirs(CACHE, exist_ok=True)
    dst = os.path.join(CACHE, name)
    if os.path.isfile(dst) and hashlib.sha512(open(dst, "rb").read()).hexdigest() == sha512:
        return dst
    data = urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=120).read()
    if hashlib.sha512(data).hexdigest() != sha512:
        sys.exit(f"capture: {name}: sha512 mismatch")
    open(dst, "wb").write(data)
    print(f"capture: fetched {name} ({len(data)} bytes)")
    return dst


def dh_toml(far):
    # Only the keys that matter; Distant Horizons fills in the rest on first load (local_render.py's settings).
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


def prepare(clip, far=0, gui=2):
    for d in ("shaderpacks", "config", "mods"):
        os.makedirs(os.path.join(RUN, d), exist_ok=True)
    # Far terrain: exactly the pinned Distant Horizons jar with --far, none without.
    md = os.path.join(RUN, "mods")
    for f in os.listdir(md):
        if f.lower().startswith(("distanthorizons", "voxy")):
            os.remove(os.path.join(md, f))
    if far:
        pin = json.load(open(SPOTS, encoding="utf-8"))["farTerrain"]["1.21.1"]
        shutil.copyfile(fetch(pin["url"], pin["sha512"], pin["file"]), os.path.join(md, pin["file"]))
        open(os.path.join(RUN, "config", "DistantHorizons.toml"), "w", encoding="utf-8").write(dh_toml(far))
    sp = os.path.join(RUN, "shaderpacks")
    for f in os.listdir(sp):
        if clip in FRESH_PACKS or f.lower().startswith("complementary"):
            p = os.path.join(sp, f)
            shutil.rmtree(p) if os.path.isdir(p) else os.remove(p)
    idx = os.path.join(RUN, "config", "originclient-shaders.json")
    if clip in FRESH_PACKS and os.path.exists(idx):
        os.remove(idx)
    if clip in NO_SHADER:
        props = "enableShaders=false\n"
    else:
        name, path = shader_zip()
        shutil.copyfile(path, os.path.join(sp, name))
        # Complementary at its defaults (waving leaves, moving clouds) but the high shadow/cloud/light-shaft tiers.
        open(os.path.join(sp, name + ".txt"), "w", encoding="utf-8").write(
            "SHADOW_QUALITY=4\nCLOUD_QUALITY=3\nLIGHTSHAFT_QUALI_DEFINE=3\nVIGNETTE_R=false\n" + SHADER_OPTS.get(clip, ""))
        props = f"enableShaders=true\nshaderPack={name}\n"
    open(os.path.join(RUN, "config", "iris.properties"), "w", encoding="utf-8").write(props)
    # HUD positions back to the defaults (the top-left stack), so a take never inherits R6's drags.
    mods = os.path.join(RUN, "config", "originclient-mods.json")
    if os.path.exists(mods):
        cfg = json.load(open(mods, encoding="utf-8"))
        cfg["hud"] = {}
        if clip == "R9":
            # Rev 3's HUD layers: each module at 2x, spaced apart so each crops alone; no backgrounds (layout
            # backing opacity 0, showBackground off). [anchor TL, dx, dy, scale, backing] in GUI px (GUI scale 2).
            for mid, spot in R9_HUD.items():
                cfg["hud"][mid] = spot
                cfg["mods"][mid] = dict(R9_OPTS.get(mid, {}), enabled=True, showBackground=False)
        if clip == "R11":
            for mid, spot in R11_HUD.items():
                cfg["hud"][mid] = spot
                cfg["mods"].setdefault(mid, {}).update(enabled=True, showBackground=False)
        if clip == "V3_HUD":
            for mid, opts in V3_HUD_OPTS.items():
                cfg["mods"].setdefault(mid, {}).update(opts, enabled=True, showBackground=False)
        if clip in ("R6", "R12", "V3_TITLE"):
            # The menu as a player's setup shows it: every mod at its shipped default (Mods.java), plus the HUD modules
            # shot 11 stacks and 1.7 Animations / Block Outline on. A dev run left from older takes has them all off,
            # which reads as a wall of red DISABLED. R6 hides the HUD (F1), so none of these draw over the menu.
            src = open(os.path.join(MDIR, "src", "client", "java", "com", "origin", "client", "client", "mods", "Mods.java"),
                       encoding="utf-8").read()
            defaults = dict(re.findall(r'add\("(\w+)",\s*"[^"]*",\s*"[^"]*",\s*(true|false)', src))
            for mid, on in defaults.items():
                cfg["mods"].setdefault(mid, {})["enabled"] = on == "true" or mid in R6_ON
        json.dump(cfg, open(mods, "w", encoding="utf-8"), indent=2)
    open(os.path.join(RUN, "options.txt"), "w", encoding="utf-8").write(
        "version:3955\nonboardAccessibility:false\npauseOnLostFocus:false\nrenderDistance:20\nsimulationDistance:8\n"
        f"guiScale:{gui}\nfov:0.0\nfullscreen:false\nnarrator:0\nsoundCategory_master:0.0\ntutorialStep:none\n"
        "chatVisibility:2\nskipMultiplayerWarning:true\njoinedFirstServer:true\nenableVsync:false\nmaxFps:260\n")


def run_clip(clip, out, probe, extra, user, fps, far=0, size="1920x1080", gui=2):
    prepare(clip, far, gui)
    os.makedirs(out, exist_ok=True)
    env = dict(os.environ)
    env.update(ORIGIN_SHOTS=out, ORIGIN_SHOTS_SET="ad", ORIGIN_AD_CLIP=clip, ORIGIN_AD_MODE="probe" if probe else "take",
               ORIGIN_AD_FPS=str(fps), ORIGIN_AD_SIZE=size, ORIGIN_AD_GUI=str(gui), JAVA_HOME=JDK21)
    env.update(CLIP_ENV.get(clip, {}))
    env.update(extra)
    gate = os.path.join(out, f"{clip}.far.ok")
    if os.path.exists(gate):
        os.remove(gate)
    if far:
        env["ORIGIN_AD_FAR_GATE"] = gate
    logp = os.path.join(out, f"{clip}{'_probe' if probe else ''}.run.log")
    cmd = ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", LOCK, "-MaxMinutes", "70", "--",
           os.path.join(MDIR, "gradlew.bat"), "runClient", "--no-daemon", f"-PbootJvmArgs=-Xmx{12 if far else 8}G",
           f"-PdevUser={user}", f"-PdevSize={size}"]
    t0 = time.time()
    print(f"capture: {clip}{' (probe)' if probe else ''} -> {out}", flush=True)
    with open(logp, "w", encoding="utf-8", errors="replace") as log:
        proc = subprocess.Popen(cmd, cwd=MDIR, env=env, stdout=log, stderr=subprocess.STDOUT)
    pos = 0
    failed = False
    # Far terrain: the game logs "far wait" once it stands at the first pose; from then on DH's progress lines are
    # watched and the gate file appears when generation is done (0 left, the lines stopped, or a small count froze)
    # plus SETTLE s for DH to finish building its LODs (local_render.py's rule).
    wait_from = last_dh = last_left = last_change = settle_from = None
    last_print = 0
    while proc.poll() is None:
        time.sleep(2)
        with open(logp, encoding="utf-8", errors="replace") as f:
            f.seek(pos)
            new = f.read()
            pos = f.tell()
        for line in new.splitlines():
            if "[ad]" in line or "Exception" in line[:200] or "FATAL" in line or "verify-lock" in line:
                print(f"  {time.time() - t0:5.0f}s {line[line.find('['):][:240] if '[ad]' in line else line[:240]}", flush=True)
            if "[ad] FAILED" in line:
                failed = True
            if far and wait_from is None and "[ad] far wait" in line:
                wait_from = time.time()
            m = DH_LINE.search(line) if far else None
            if m and wait_from:
                left = int(m.group(2).replace(",", ""))
                if left != last_left:
                    last_change = time.time()
                last_left = left
                if time.time() - last_print > 60:
                    last_print = time.time()
                    print(f"  {time.time() - t0:5.0f}s DH: {left} chunks left", flush=True)
                last_dh = time.time()
        if far and wait_from and not os.path.exists(gate):
            waited = time.time() - wait_from
            # no progress line for 45 s (or none at all: the world's LODs were generated on an earlier run) after 90 s
            done = last_left == 0 or (time.time() - (last_dh or wait_from) > 45 and waited > 90) \
                or (last_change and time.time() - last_change > 90 and last_left is not None and last_left < 500)
            if (done or waited > 1800) and not settle_from:
                print(f"  far terrain {'done' if done else 'TIMED OUT'} after {waited:.0f} s (last {last_left} left); settling {SETTLE} s",
                      flush=True)
                settle_from = time.time()
            if settle_from and time.time() - settle_from > SETTLE:
                open(gate, "w").close()
                print(f"  {time.time() - t0:5.0f}s far gate open", flush=True)
        if time.time() - t0 > LIMIT.get(clip, 600):
            print(f"capture: {clip}: over {LIMIT.get(clip, 600)} s, killing the game", flush=True)
            subprocess.run(["taskkill", "/T", "/F", "/PID", str(proc.pid)], capture_output=True)
            failed = True
    print(f"capture: {clip}: game exited {proc.returncode} after {time.time() - t0:.0f} s", flush=True)
    if clip == "R2" and not probe and not failed:
        parts = sorted(f for f in os.listdir(out) if f.startswith("R2_") and f.endswith(".mp4") and "_vanilla" not in f and f[3:5].isdigit())
        lst = os.path.join(out, "R2_parts.txt")
        open(lst, "w", encoding="utf-8").write("".join(f"file '{p}'\n" for p in parts))
        subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "concat", "-safe", "0", "-i", lst,
                        "-c", "copy", os.path.join(out, "R2.mp4")], check=True, cwd=out)
        print(f"capture: R2.mp4 <- {parts}")
    return not failed


def encode(out, dest, tag="", crf=15):
    """Raw takes (60 fps, near-lossless) -> the edit's footage slots at 30 fps. tag: a name suffix (`9x16` for the
    1440x2560 flights the vertical cut uses: F_<flight>_9x16.mp4)."""
    os.makedirs(dest, exist_ok=True)
    # every R2 flight on its own too (R2_NN_<flight>.mp4, plus the shaders-off twin), for montages and the wipe
    flights = sorted(f[:-4] for f in os.listdir(out) if f.startswith("R2_") and f[3:5].isdigit() and f.endswith(".mp4"))
    for name in [*flights, *FOOTAGE]:   # (R2.mp4, the flights joined, is for review only; the edit uses F_*)
        src = os.path.join(out, name + ".mp4")
        if not os.path.exists(src):
            print(f"encode: {name}: no take")
            continue
        # a flight lands under its own name, F_<flight>[_vanilla].mp4, so the edit never depends on the take order
        dst = os.path.join(dest, ("F_" + name[6:] if name in flights else name) + (f"_{tag}" if tag else "") + ".mp4")
        subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", src, "-vf", "fps=30",
                        "-c:v", "libx264", "-preset", "slow", "-crf", str(crf), "-pix_fmt", "yuv420p", "-an",
                        "-movflags", "+faststart", dst], check=True)
        print(f"encode: {dst}")


def encode_v3(out, dest, crf=12):
    """Film v3: raw V3_*.mp4 -> the edit's v3 footage at 60 fps (no frame drop: smooth pointer and scrolls), x264
    CRF 12, plus each take's V3_*.json pointer/event sidecar next to it."""
    os.makedirs(dest, exist_ok=True)
    for f in sorted(os.listdir(out)):
        if not (f.startswith("V3_") and f.endswith(".mp4")):
            continue
        dst = os.path.join(dest, f)
        subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", os.path.join(out, f), "-r", "60",
                        "-c:v", "libx264", "-preset", "slow", "-crf", str(crf), "-pix_fmt", "yuv420p", "-an",
                        "-movflags", "+faststart", dst], check=True)
        side = os.path.join(out, f[:-4] + ".json")
        if os.path.exists(side):
            shutil.copyfile(side, os.path.join(dest, f[:-4] + ".json"))
        print(f"encode-v3: {dst}")


def main():
    # game log lines carry arrows and the like; a cp1252 console must not kill the run (and orphan the game) over one
    sys.stdout.reconfigure(errors="replace")
    ap = argparse.ArgumentParser()
    ap.add_argument("clips", nargs="+", help="R2..R8, or 'encode'")
    ap.add_argument("--out", default=os.path.join(AD, "footage", "raw"))
    ap.add_argument("--probe", action="store_true")
    ap.add_argument("--fps", type=int, default=60)
    ap.add_argument("--user", default="Rowan", help="offline dev name (never a real account)")
    ap.add_argument("--set", action="append", default=[], help="extra ORIGIN_AD_* env, K=V")
    ap.add_argument("--far", type=int, default=0, help="Distant Horizons LOD radius in chunks (0 = no far terrain)")
    ap.add_argument("--packs", action="store_true", help="R2 as the rev 4 pack flights (bsl, photon, solas)")
    # 1440 masters (2026-11-11): 2560x1440 at GUI 3 (the UI the same relative size as 1920x1080 at GUI 2, a touch
    # bigger), and the flights again at 1440x2560 for the 9:16 cut (same vertical FOV = exactly the 16:9 frame's centre
    # crop, at native density). The deck screens (Download Shaders, FPS Coach) scale with the window height on their own.
    ap.add_argument("--size", default="1920x1080", help="window / take size WxH (e.g. 2560x1440, 1440x2560)")
    ap.add_argument("--gui", type=int, default=2, help="Minecraft GUI scale for the take")
    ap.add_argument("--tag", default="", help="encode: suffix for the footage names (9x16 for the portrait flights)")
    ap.add_argument("--crf", type=int, default=15, help="encode: x264 CRF (12 for the 1440 masters)")
    ap.add_argument("--suffix", default="", help="V3 clips: appended to the take name (_9x16 for the portrait takes)")
    a = ap.parse_args()
    if a.clips == ["encode"]:
        encode(a.out, os.path.join(AD, "edit", "public", "footage"), a.tag, a.crf)
        return
    if a.clips == ["encode-v3"]:
        encode_v3(a.out, os.path.join(AD, "edit", "public", "v3", "footage"), 12)
        return
    extra = dict(PACK_FLIGHTS) if a.packs else {}
    extra.update(s.split("=", 1) for s in a.set)
    if a.suffix:
        extra["ORIGIN_AD_SUFFIX"] = a.suffix
    ok = True
    for c in a.clips:
        if c == "V3_LOAD":   # the stage run first: the take must land on the hero view, not where the last clip left off
            ok = run_clip(c, a.out, a.probe, dict(extra, ORIGIN_AD_LOAD_STAGE="1"), a.user, a.fps, a.far, a.size, a.gui) and ok
        ok = run_clip(c, a.out, a.probe, extra, a.user, a.fps, a.far, a.size, a.gui) and ok
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
