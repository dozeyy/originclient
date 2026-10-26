#!/usr/bin/env python3
"""Static check of Origin's compat gates (compat/CompatGates + originclient.compat-gates.txt).

Every Origin mixin into another mod (Sodium, Iris, JEI, ...) must apply only on the versions it was proven
on (DECISIONS 2026-10-26). This fails when, in any live Fabric module:
  - the client mixin config doesn't use compat.CompatMixinPlugin, or the data file is missing / malformed;
  - a mixin whose @Mixin target is outside Minecraft/Fabric/Java isn't listed in a gate group
    (a new Sodium hook added without a version gate);
  - a gate lists a mixin the config doesn't have (stale data);
  - code asks CompatGates.on("x") / note("x") for an id the data doesn't define (unknown ids read as ON,
    so a typo would silently skip the gate);
  - a no-sync module's copy of compat/*.java differs from src/mods/shared (pre-1.20 modules are outside
    tools/shared-sync).

Usage: python tools/compat-gates/check.py            (exit 1 on any finding)
       python tools/compat-gates/check.py --selftest (proves each check can fail)
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
VERSIONS = REPO / "src" / "mods" / "versions"
SHARED_COMPAT = REPO / "src" / "mods" / "shared" / "src" / "client" / "java" / "com" / "origin" / "client" / "client" / "compat"
PLUGIN = "com.origin.client.client.compat.CompatMixinPlugin"
DATA = "originclient.compat-gates.txt"
FIRST_PARTY = ("net.minecraft.", "com.mojang.", "net.fabricmc.", "org.lwjgl", "java.", "com.origin.")
MIXIN_RE = re.compile(r"@Mixin\s*\((.*?)\)\s*(?:public|abstract|final|class|interface|@)", re.S)
ON_RE = re.compile(r"CompatGates\.(?:on|note|cardNote|pageNote)\(\s*\"([^\"]+)\"\s*\)")


def live_fabric_modules() -> list[str]:
    mods = json.loads((REPO / "src" / "mods" / "live-modules.json").read_text(encoding="utf-8"))["modules"]
    # 1.8.9 is Forge (fixed loader, its two mixins target Forge itself): no third-party mod to gate.
    return [m["module"] for m in mods if m["module"] != "1.8.9"]


def source_set(module: Path) -> Path:
    return module / "src" / ("client" if (module / "src" / "client").is_dir() else "main")


def parse_data(text: str) -> tuple[dict, dict, list[str]]:
    groups, features, errors = {}, {}, []
    for n, raw in enumerate(text.splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split("|")
        head = parts[0].split()
        if len(head) != 2:
            errors.append(f"line {n}: bad head")
            continue
        kind, gid = head
        if kind == "group" and len(parts) == 4:
            needs = [x.strip() for x in parts[2].split(";") if x.strip()]
            if not needs or any(" " not in x for x in needs):
                errors.append(f"line {n}: group {gid} needs '<mod> <range>'")
            groups[gid] = parts[3].split()
        elif kind == "feature" and len(parts) == 3:
            features[gid] = [alt.split() for alt in parts[2].split("/")]
        else:
            errors.append(f"line {n}: not a group (4 fields) or feature (3 fields)")
    for fid, alts in features.items():
        for alt in alts:
            for g in alt:
                if g not in groups:
                    errors.append(f"feature {fid} names unknown group {g}")
    ids = set(groups) | set(features)
    if len(ids) != len(groups) + len(features):
        errors.append("a group and a feature share an id")
    return groups, features, errors


def third_party_mixins(src: Path, config: dict) -> list[str]:
    pkg = config.get("package", "")
    out = []
    for name in config.get("client", []) + config.get("mixins", []):
        f = src / "java" / Path(*(pkg + "." + name).split("."))
        f = f.with_suffix(".java")
        if not f.is_file():
            continue
        text = f.read_text(encoding="utf-8")
        m = MIXIN_RE.search(text)
        if not m:
            continue
        imports = {i.split(".")[-1]: i for i in re.findall(r"^import\s+([\w.]+);", text, re.M)}
        targets = [t.replace("/", ".") for t in re.findall(r'"([\w.$/]+)"', m.group(1))]
        for c in re.findall(r"([\w.]+)\.class", m.group(1)):
            first = c.split(".")[0]
            targets.append(imports.get(first, c) + c[len(first):] if first in imports else c)
        if any("." in t and not t.startswith(FIRST_PARTY) for t in targets):
            out.append(name)
    return out


def check_module(mc: str, findings: list[str], data_override: str | None = None) -> None:
    module = VERSIONS / mc
    src = source_set(module)
    cfg_path = src / "resources" / "originclient.client.mixins.json"
    config = json.loads(cfg_path.read_text(encoding="utf-8"))
    if config.get("plugin") != PLUGIN:
        findings.append(f"{mc}: originclient.client.mixins.json plugin is {config.get('plugin')!r}, not {PLUGIN}")
    data_path = src / "resources" / DATA
    if data_override is None and not data_path.is_file():
        findings.append(f"{mc}: {DATA} missing")
        return
    groups, features, errors = parse_data(data_override if data_override is not None else data_path.read_text(encoding="utf-8"))
    findings.extend(f"{mc}: {DATA} {e}" for e in errors)
    listed = {m: g for g, ms in groups.items() for m in ms}
    declared = set(config.get("client", []) + config.get("mixins", []))
    for m in third_party_mixins(src, config):
        if m not in listed:
            findings.append(f"{mc}: {m} targets another mod but no gate group lists it")
    for m, g in listed.items():
        if m not in declared:
            findings.append(f"{mc}: group {g} lists {m}, which the mixin config doesn't have")
    ids = set(groups) | set(features)
    for f in (src / "java").rglob("*.java"):
        if f.parent.name == "compat":
            continue
        for gid in ON_RE.findall(f.read_text(encoding="utf-8")):
            if gid not in ids:
                findings.append(f"{mc}: {f.name} asks CompatGates for '{gid}', which {DATA} doesn't define")
    if (module / ".no-shared-sync").exists():
        for s in SHARED_COMPAT.glob("*.java"):
            copy = src / "java" / "com" / "origin" / "client" / "client" / "compat" / s.name
            if not copy.is_file() or copy.read_bytes().replace(b"\r\n", b"\n") != s.read_bytes().replace(b"\r\n", b"\n"):
                findings.append(f"{mc}: compat/{s.name} missing or differs from src/mods/shared")


def run() -> list[str]:
    findings: list[str] = []
    for mc in live_fabric_modules():
        check_module(mc, findings)
    return findings


def selftest() -> int:
    """Each check must be able to fail: feed it a planted defect and expect a finding."""
    mc = "1.21.1"
    data = (source_set(VERSIONS / mc) / "resources" / DATA).read_text(encoding="utf-8")
    cases = {
        "unlisted third-party mixin": data.replace(" SodiumWalkEndMixin", ""),
        "stale listed mixin": data.replace("SodiumWalkEndMixin", "SodiumWalkEndMixin SodiumGoneMixin"),
        "feature names unknown group": data + "feature x | X | nosuchgroup\n",
        "undefined id asked for": data.replace("feature smartTerrainUpdates", "feature smartTerrainUpdatez"),
        "malformed line": data + "group broken | Broken | sodium\n",
    }
    bad = 0
    for name, planted in cases.items():
        found: list[str] = []
        check_module(mc, found, data_override=planted)
        print(("caught " if found else "MISSED ") + name + (": " + found[0] if found else ""))
        bad += 0 if found else 1
    clean: list[str] = []
    check_module(mc, clean, data_override=data)
    if clean:
        print("clean data reported findings: " + clean[0])
        bad += 1
    return 1 if bad else 0


def main() -> int:
    if "--selftest" in sys.argv[1:]:
        return selftest()
    findings = run()
    for f in findings:
        print("FAIL " + f)
    if findings:
        return 1
    print(f"OK: compat gates cover every third-party mixin in {len(live_fabric_modules())} live Fabric modules.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
