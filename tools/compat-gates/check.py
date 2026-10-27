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
    tools/shared-sync);
  - a group in SIG_REQUIRED (jei) has no signature table, or its `sig` lines and the methods its mixins
    inject into disagree (a hooked method with no sig row, or a stale row): CompatGates can only
    check, at mixin load, the targets the table names.

Usage: python tools/compat-gates/check.py            (exit 1 on any finding)
       python tools/compat-gates/check.py --selftest (proves each check can fail, and runs
                                                      SignatureTests.java against ClassSignatures)
       python tools/compat-gates/check.py --jars     (local: every module's sig table against the JEI
                                                      jar it pins, from the Gradle cache)
"""
from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
VERSIONS = REPO / "src" / "mods" / "versions"
SHARED_COMPAT = REPO / "src" / "mods" / "shared" / "src" / "client" / "java" / "com" / "origin" / "client" / "client" / "compat"
PLUGIN = "com.origin.client.client.compat.CompatMixinPlugin"
DATA = "originclient.compat-gates.txt"
FIRST_PARTY = ("net.minecraft.", "com.mojang.", "net.fabricmc.", "org.lwjgl", "java.", "com.origin.")
MIXIN_RE = re.compile(r"@Mixin\s*\((.*?)\)\s*(?:public|abstract|final|class|interface|@)", re.S)
ON_RE = re.compile(r"CompatGates\.(?:on|note|cardNote|pageNote)\(\s*\"([^\"]+)\"\s*\)")
# Groups whose hooks must carry a full signature table (owner + name + descriptor per hooked method).
SIG_REQUIRED = ("jei",)
SIG_RE = re.compile(r"^[\w$<>]+\([^()\s]*\)\S+$")
INJECT_RE = re.compile(r"@(?:Inject|Redirect|ModifyArg|ModifyArgs|ModifyVariable|ModifyConstant|ModifyReturnValue|"
                       r"WrapOperation|WrapWithCondition)\s*\(\s*method\s*=\s*(\{[^}]*\}|\"[^\"]+\")")


def live_fabric_modules() -> list[str]:
    mods = json.loads((REPO / "src" / "mods" / "live-modules.json").read_text(encoding="utf-8"))["modules"]
    # 1.8.9 is Forge (fixed loader, its two mixins target Forge itself): no third-party mod to gate.
    return [m["module"] for m in mods if m["module"] != "1.8.9"]


def source_set(module: Path) -> Path:
    return module / "src" / ("client" if (module / "src" / "client").is_dir() else "main")


def parse_data(text: str) -> tuple[dict, dict, dict, list[str]]:
    """groups: id -> mixins; features: id -> alternatives; sigs: group -> [(owner, [method names])]."""
    groups, features, sigs, errors = {}, {}, {}, []
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
        elif kind == "sig" and len(parts) == 3:
            owner = parts[1].strip()
            alts = [a.strip() for a in re.split(r"\s+or\s+", parts[2].strip())]
            if not re.fullmatch(r"[\w.$]+", owner) or not all(SIG_RE.match(a) for a in alts):
                errors.append(f"line {n}: sig needs '<owner class> | <name>(<params>)<return> [or ...]'")
                continue
            sigs.setdefault(gid, []).append((owner, [a[:a.index("(")] for a in alts]))
        else:
            errors.append(f"line {n}: not a group (4 fields), feature (3 fields) or sig (3 fields)")
    for fid, alts in features.items():
        for alt in alts:
            for g in alt:
                if g not in groups:
                    errors.append(f"feature {fid} names unknown group {g}")
    for gid in sigs:
        if gid not in groups:
            errors.append(f"sig names unknown group {gid}")
    ids = set(groups) | set(features)
    if len(ids) != len(groups) + len(features):
        errors.append("a group and a feature share an id")
    return groups, features, sigs, errors


def mixin_file(src: Path, config: dict, name: str) -> Path:
    pkg = config.get("package", "")
    return (src / "java" / Path(*(pkg + "." + name).split("."))).with_suffix(".java")


def mixin_targets(text: str) -> list[str]:
    """Fully qualified @Mixin target classes of a mixin source (string targets and imported .class refs)."""
    m = MIXIN_RE.search(text)
    if not m:
        return []
    imports = {i.split(".")[-1]: i for i in re.findall(r"^import\s+([\w.]+);", text, re.M)}
    targets = [t.replace("/", ".") for t in re.findall(r'"([\w.$/]+)"', m.group(1))]
    for c in re.findall(r"([\w.]+)\.class", m.group(1)):
        first = c.split(".")[0]
        targets.append(imports.get(first, c) + c[len(first):] if first in imports else c)
    return targets


def hooked_methods(text: str) -> set[str]:
    names = set()
    for grp in INJECT_RE.findall(text):
        for n in re.findall(r'"([^"]+)"', grp):
            names.add(n.split("(")[0])
    return names


def third_party_mixins(src: Path, config: dict) -> list[str]:
    out = []
    for name in config.get("client", []) + config.get("mixins", []):
        f = mixin_file(src, config, name)
        if not f.is_file():
            continue
        targets = mixin_targets(f.read_text(encoding="utf-8"))
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
    groups, features, sigs, errors = parse_data(data_override if data_override is not None else data_path.read_text(encoding="utf-8"))
    findings.extend(f"{mc}: {DATA} {e}" for e in errors)
    listed = {m: g for g, ms in groups.items() for m in ms}
    declared = set(config.get("client", []) + config.get("mixins", []))
    for m in third_party_mixins(src, config):
        if m not in listed:
            findings.append(f"{mc}: {m} targets another mod but no gate group lists it")
    for m, g in listed.items():
        if m not in declared:
            findings.append(f"{mc}: group {g} lists {m}, which the mixin config doesn't have")
    for gid in SIG_REQUIRED:
        if gid in groups:
            check_sig_coverage(mc, src, config, gid, groups[gid], sigs.get(gid, []), findings)
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


def check_sig_coverage(mc: str, src: Path, config: dict, gid: str, mixins: list[str],
                       rows: list[tuple[str, list[str]]], findings: list[str]) -> None:
    """Every method the group's mixins hook has exactly one sig row on the right owner, and no row is stale."""
    if not rows:
        findings.append(f"{mc}: group {gid} has no sig lines (its hooks need a signature table)")
        return
    hooked = set()
    for name in mixins:
        f = mixin_file(src, config, name)
        if not f.is_file():
            continue
        text = f.read_text(encoding="utf-8")
        for owner in mixin_targets(text):
            for meth in hooked_methods(text):
                hooked.add((owner, meth))
    covered: dict[tuple[str, str], int] = {}
    for owner, names in rows:
        for n in names:
            covered[(owner, n)] = covered.get((owner, n), 0) + 1
            if (owner, n) not in hooked:
                findings.append(f"{mc}: sig {gid} | {owner} | {n}: no {gid} mixin injects into it (stale row or moved class)")
    for owner, meth in sorted(hooked):
        if covered.get((owner, meth), 0) == 0:
            findings.append(f"{mc}: {owner}.{meth} is hooked by a {gid} mixin but has no sig line")
        elif covered[(owner, meth)] > 1:
            findings.append(f"{mc}: {owner}.{meth} has {covered[(owner, meth)]} sig lines in {gid}")


def java_tools() -> tuple[str, str]:
    """(javac, java) from JAVA_HOME, Will's JDK 21, or PATH (on Will's PC PATH java is a JRE shim)."""
    exe = ".exe" if os.name == "nt" else ""
    for home in (os.environ.get("JAVA_HOME"), r"C:\Program Files\Java\jdk-21.0.10"):
        if home and (Path(home) / "bin" / ("javac" + exe)).is_file():
            b = Path(home) / "bin"
            return str(b / ("javac" + exe)), str(b / ("java" + exe))
    if shutil.which("javac"):
        return "javac", "java"
    raise SystemExit("javac not found (set JAVA_HOME to a JDK)")


def signature_tests(extra_args: list[str]) -> int:
    """Compiles the shared ClassSignatures + SignatureTests.java and runs them (exit code 1 on any failure)."""
    javac, java = java_tools()
    out = tempfile.mkdtemp(prefix="sigtests-")
    here = Path(__file__).resolve().parent
    r = subprocess.run([javac, "-d", out, str(SHARED_COMPAT / "ClassSignatures.java"), str(here / "SignatureTests.java")])
    if r.returncode:
        print("SignatureTests: compile failed")
        return 1
    return subprocess.run([java, "-cp", out, "SignatureTests"] + extra_args).returncode


def pinned_jei_jar(mc: str) -> Path | None:
    props = (VERSIONS / mc / "gradle.properties").read_text(encoding="utf-8")
    ids = re.findall(r"^jei_(?:version|modrinth_id)\s*=\s*(\S+)", props, re.M)
    if not ids:
        return None
    root = Path.home() / ".gradle" / "caches" / "modules-2" / "files-2.1" / "maven.modrinth" / "jei" / ids[-1]
    jars = [j for j in sorted(root.glob("*/*.jar")) if not j.name.endswith("-sources.jar")]
    return jars[0] if jars else None


def jar_check() -> int:
    """Local proof that each module's sig table matches the JEI jar it pins (the jar holds the names players run)."""
    pairs = []
    for mc in live_fabric_modules():
        data = source_set(VERSIONS / mc) / "resources" / DATA
        if not data.is_file() or not re.search(r"(?m)^sig ", data.read_text(encoding="utf-8")):
            continue
        jar = pinned_jei_jar(mc)
        if jar is None:
            print(f"skip {mc}: pinned JEI jar not in the Gradle cache (build the module once)")
            continue
        pairs.append(f"{mc}={data}={jar}")
    return signature_tests(["--jars"] + pairs)


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
        "malformed sig": data + "sig jei | mezz.jei.X | noDescriptor\n",
        "sig for unknown group": data + "sig nosuchgroup | mezz.jei.X | a()V\n",
        "hooked method with no sig line": re.sub(r"(?m)^sig jei [^\n]*onGuiMouseScroll[^\n]*\n", "", data),
        "stale sig row (moved class)": data.replace("| mezz.jei.gui.input.ClientInputHandler | onGuiMouseScroll",
                                                     "| mezz.jei.gui.input.MovedInputHandler | onGuiMouseScroll"),
        "jei group without a sig table": re.sub(r"(?m)^sig jei [^\n]*\n", "", data),
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
    # The runtime half: ClassSignatures must report a wrong descriptor and a missing class as missing.
    if signature_tests([]):
        bad += 1
    return 1 if bad else 0


def main() -> int:
    if "--selftest" in sys.argv[1:]:
        return selftest()
    if "--jars" in sys.argv[1:]:
        return jar_check()
    findings = run()
    for f in findings:
        print("FAIL " + f)
    if findings:
        return 1
    print(f"OK: compat gates cover every third-party mixin in {len(live_fabric_modules())} live Fabric modules.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
