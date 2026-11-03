"""Checks every live module's bundled default-mods-config.json (the starter config a
brand-new player is seeded from) against that module's Mods.java schema.

Why: the starter config is a snapshot. A value in it that a mod can't use makes that mod
look broken for every new player (2026-11 bug sweep: Motion Blur seeded at strength 0 did
nothing when turned on; Time Changer seeded with "Use Real Current Time" on overwrote the
Time slider every tick). Fails when:
  - a seeded slider value sits outside its Mods.java slider range (min..max), or
  - a seeded value hits one of the known traps below.

Usage: python tools/mod-menu/check_default_config.py   (exit 1 on any problem)
"""
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
MODS_DIR = ROOT / "src" / "mods" / "versions"

# Values that are inside their range but still make the mod do nothing or fight the player.
# (mod id, option key, test, why)
TRAPS = [
    ("motionblur", "amount", lambda v: v >= 0.5, "MotionBlur skips strengths below 0.5, so turning it on does nothing"),
    ("timechanger", "useRealTime", lambda v: v is False, "real time overwrites the Time slider and the [ ] keys every tick"),
]

ADD_RE = re.compile(r'\badd\(\s*"([a-z0-9_]+)"')
SLIDER_RE = re.compile(
    r'ModOption\.slider\(\s*"([A-Za-z0-9_]+)"\s*,\s*"[^"]*"\s*,\s*(-?[0-9.]+)[fFdD]?\s*,\s*(-?[0-9.]+)[fFdD]?')


def slider_ranges(mods_java: str):
    """mod id -> {option key: (min, max)}, by walking add(...) blocks in source order."""
    ranges = {}
    events = [(m.start(), "add", m) for m in ADD_RE.finditer(mods_java)]
    events += [(m.start(), "slider", m) for m in SLIDER_RE.finditer(mods_java)]
    current = None
    for _, kind, m in sorted(events, key=lambda e: e[0]):
        if kind == "add":
            current = m.group(1)
        elif current is not None:
            ranges.setdefault(current, {})[m.group(1)] = (float(m.group(2)), float(m.group(3)))
    return ranges


def check_module(module: str):
    base = MODS_DIR / module
    configs = list(base.glob("src/*/resources/assets/originclient/default-mods-config.json"))
    mods_javas = list(base.glob("src/*/java/com/origin/client/client/mods/Mods.java"))
    if not configs or not mods_javas:
        return [f"{module}: no default-mods-config.json or Mods.java found"] if not configs and mods_javas else []
    problems = []
    ranges = slider_ranges(mods_javas[0].read_text(encoding="utf-8"))
    seeded = json.loads(configs[0].read_text(encoding="utf-8")).get("mods", {})
    for mod_id, values in seeded.items():
        for key, value in values.items():
            rng = ranges.get(mod_id, {}).get(key)
            if rng and isinstance(value, (int, float)) and not isinstance(value, bool):
                lo, hi = rng
                if not (lo - 1e-9 <= value <= hi + 1e-9):
                    problems.append(f"{module}: {mod_id}.{key} = {value} is outside its slider range {lo}..{hi}")
    for mod_id, key, ok, why in TRAPS:
        if key in seeded.get(mod_id, {}) and not ok(seeded[mod_id][key]):
            problems.append(f"{module}: {mod_id}.{key} = {seeded[mod_id][key]!r} ({why})")
    return problems


def main():
    live = json.loads((ROOT / "src" / "mods" / "live-modules.json").read_text(encoding="utf-8"))
    problems = []
    for entry in live["modules"]:
        problems += check_module(entry["module"])
    for p in problems:
        print("FAIL " + p)
    if problems:
        return 1
    print(f"default-mods-config: {len(live['modules'])} modules OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
