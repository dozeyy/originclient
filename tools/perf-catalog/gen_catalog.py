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
Full-line mode — for versions NOT yet in Data.cs only. Several live entries
carry hand-fixed pins (see the EXCEPTION comments in Data.cs); regenerating a
live line throws those away, so never paste a full-line result over one:

    python3 tools/perf-catalog/gen_catalog.py 1.21.2 1.21.3 ...

Extras mode — additive only. Resolves the EXTRA_PROJECTS add-on stack
(Sodium Extra, MoreCulling + its Cloth Config dep, Cull Leaves,
ImmediatelyFast, ModernFix, ...) per version and emits ONLY the
`Extras: new PerfMod[] { ... }` fragment for each — the six core pins are
never touched. With --apply it splices the fragment into Data.cs in place
(idempotent: an existing Extras fragment on the line is replaced):

    python3 tools/perf-catalog/gen_catalog.py --extras [--apply] 1.20 1.20.1 ...

Slug lookup — search Modrinth when a project's slug is unknown:

    python3 tools/perf-catalog/gen_catalog.py --search "better render distance"

Requires only the Python standard library.
"""
import json
import re
import sys
import urllib.parse
import urllib.request
from pathlib import Path

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

# The optional add-on stack ("Extras" slot in VersionPerfProfile). Installed
# wherever a compatible Fabric release exists; a version with no build simply
# doesn't get that mod (fail-soft — extras never affect PerfStackTier or
# HasShaderStack). Required Modrinth dependencies of these (e.g. MoreCulling's
# Cloth Config) are resolved automatically, so they don't need their own rows.
EXTRA_PROJECTS = {
    "sodium-extra": "sodium-extra",       # Sodium Extra — must pair with the PINNED Sodium era (see pick_sodium_extra)
    "moreculling": "moreculling",         # More Culling (approved WITH Cloth Config, 2026-07-20; supersedes the 07-10 rejection)
    "cullleaves": "cull-leaves",          # Cull Leaves
    "immediatelyfast": "immediatelyfast", # was 1.21.1-bundle-only; parity for every version
    "modernfix": "modernfix",             # was 1.21.1-bundle-only; parity for every version
    "brd": "better-render-distance",      # "BetterRenderDi... 1.2.0" from Will's reference
    #                                       screenshot; resolved via --search 2026-07-20:
    #                                       the 123k-download Fabric optimization mod
    #                                       (the other hits were paper/neoforge or toys).
}

# Modrinth project ids that must NEVER be pulled in via an extra's dependency
# chain: the six core-slot projects (pinned separately, a second copy = the
# duplicate-mod-id boot refusal) and Fabric API (installed by FabricApiInstaller).
DEP_SKIP_IDS = {
    "AANobbMI",  # sodium
    "Orvt0mRa",  # indium
    "gvQqBUqZ",  # lithium
    "uXXizFIs",  # ferrite-core
    "fQEb0iXm",  # krypton
    "YL57xq9U",  # iris
    "P7dR8mSH",  # fabric-api
}

DATA_CS = "src/OriginLauncher.App/Core/Loaders/PerformanceModCatalog.Data.cs"

API = "https://api.modrinth.com/v2"
UA = "origin-launcher-catalog-gen/1.0 (dev tooling; contact will@willhenry.me)"


def list_versions(project: str, mc_version: str, releases_only: bool = False):
    """All Fabric builds of `project` that list `mc_version`, newest first.
    releases_only drops beta/alpha builds — the extras stack never ships a
    pre-release ("never broken" outranks coverage, same rule that keeps
    1.18.1 out of the catalog entirely). Full-line mode keeps the historical
    no-filter behavior so regenerating a NEW version's core line is unchanged."""
    q = urllib.parse.urlencode({
        "loaders": json.dumps(["fabric"]),
        "game_versions": json.dumps([mc_version]),
    })
    req = urllib.request.Request(f"{API}/project/{project}/version?{q}",
                                 headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=30) as r:
        versions = json.load(r)
    if releases_only:
        versions = [v for v in versions if v.get("version_type") == "release"]
    # Modrinth returns newest first, but sort by date_published to be safe.
    versions.sort(key=lambda v: v.get("date_published", ""), reverse=True)
    return versions


def get_version_by_id(version_id: str):
    """Fetch one exact Modrinth version object by its id."""
    req = urllib.request.Request(f"{API}/version/{version_id}",
                                 headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


def as_pin(version_obj):
    """Reduce a Modrinth version object to (version_number, url, filename)."""
    if version_obj is None:
        return None
    files = version_obj.get("files", [])
    primary = next((f for f in files if f.get("primary")), None) or (files[0] if files else None)
    if not primary:
        return None
    return version_obj["version_number"], primary["url"], primary["filename"]


def newest_version(project: str, mc_version: str):
    """Return (version_number, url, filename) for the newest Fabric release of
    `project` that lists `mc_version`, or None if there is no such build."""
    versions = list_versions(project, mc_version)
    return as_pin(versions[0]) if versions else None


def perfmod_cs(entry):
    """Render a (version, url, filename) tuple as `new PerfMod(...)`, or `null`."""
    if entry is None:
        return "null"
    version, url, filename = entry
    return f'new PerfMod("{version}", "{url}", "{filename}")'


# ---------------------------------------------------------------------------
# Extras mode
# ---------------------------------------------------------------------------

ENTRY_RE = re.compile(r'^(\s*\["(?P<mc>[^"]+)"\] = new\(.*)\),\s*$')
# The fragment --apply appends. Always last on the line, always script-written,
# so a greedy match up to the closing "})," is unambiguous for removal.
EXTRAS_FRAG_RE = re.compile(r', Extras: new PerfMod\[\] \{ .* \}\),\s*$')


def sodium_line(version_number: str):
    """The '0.X' era of a Sodium(-Extra) version string, e.g.
    'mc1.21.11-0.8.12-fabric' -> '0.8'. None if no such token."""
    m = re.search(r'\b0\.(\d+)\.', version_number)
    return f"0.{m.group(1)}" if m else None


def pinned_sodium_from_data(data_cs_text: str, mc: str):
    """The Sodium version_number already pinned in Data.cs for `mc`, or None if
    the entry's Sodium slot is null. Matched positionally — Sodium is the first
    ctor slot after the tier, so a bare `new PerfMod` search would wrongly grab
    Lithium's pin on Partial lines where Sodium is null."""
    for line in data_cs_text.splitlines():
        m = ENTRY_RE.match(line)
        if not m or m.group("mc") != mc:
            continue
        slot = re.match(
            r'\s*\["[^"]+"\] = new\("[^"]+", PerfStackTier\.\w+, '
            r'(?:null|new PerfMod\("(?P<ver>[^"]+)")', line)
        return slot.group("ver") if slot else None
    return None


def pick_sodium_extra(mc: str, pinned_sodium: str | None):
    """Sodium Extra must pair with the PINNED Sodium's 0.X era; a cross-era
    pair is the 'Incompatible mods found' boot refusal (same failure class as
    the Iris/Sodium EXCEPTION entries in Data.cs). Checked per candidate,
    newest release first, most-authoritative signal first:
      1. the candidate's own declared Modrinth Sodium dependency, when it
         names an exact version — its 0.X line must equal the pin's. (Their
         version NUMBERS decoupled in the 0.8/0.9 era: sodium-extra 0.9.x
         pairs with sodium 0.8.x on 1.21.11, so own-number matching alone
         would wrongly reject the right build.)
      2. no declared pin, but the pinned era is the ONLY Sodium era published
         for this MC version -> anything built for it targets that era.
      3. several Sodium eras coexist and nothing declared -> fall back to the
         candidate's own 0.X line.
    No candidate passes -> omit, never 'closest'. No Sodium pin -> no Extra."""
    if pinned_sodium is None:
        return None
    pin_line = sodium_line(pinned_sodium)
    candidates = list_versions(EXTRA_PROJECTS["sodium-extra"], mc, releases_only=True)
    if not candidates:
        return None
    sodium_eras = {sodium_line(v["version_number"]) for v in list_versions("sodium", mc)}
    sodium_eras.discard(None)
    for cand in candidates:
        declared = None
        for dep in cand.get("dependencies", []):
            if dep.get("project_id") == "AANobbMI" and dep.get("version_id"):
                try:
                    declared = sodium_line(get_version_by_id(dep["version_id"])["version_number"])
                except Exception:
                    declared = None
                break
        if declared is not None:
            if declared == pin_line:
                return as_pin(cand)
            continue
        if sodium_eras <= {pin_line}:
            return as_pin(cand)
        if sodium_line(cand["version_number"]) == pin_line:
            return as_pin(cand)
    print(f"// !! {mc}: no sodium-extra matches pinned Sodium line {pin_line} "
          f"(eras on Modrinth: {sorted(sodium_eras)}) — omitted", file=sys.stderr)
    return None


def resolve_required_deps(version_obj, mc: str, chosen_project_ids: set, depth=0):
    """One/two-level walk of a picked version's *required* Modrinth deps (this
    is how MoreCulling brings its Cloth Config, both-or-neither). Core-slot
    projects and Fabric API are never followed (DEP_SKIP_IDS)."""
    out = []
    if depth >= 2:
        return out
    for dep in version_obj.get("dependencies", []):
        if dep.get("dependency_type") != "required":
            continue
        pid = dep.get("project_id")
        if not pid or pid in DEP_SKIP_IDS or pid in chosen_project_ids:
            continue
        chosen_project_ids.add(pid)
        try:
            if dep.get("version_id"):
                dep_ver = get_version_by_id(dep["version_id"])
            else:
                vers = list_versions(pid, mc, releases_only=True)
                dep_ver = vers[0] if vers else None
        except Exception as e:
            print(f"// !! {mc}: dep {pid} lookup failed ({e}) — omitted", file=sys.stderr)
            continue
        if dep_ver is None:
            # The parent mod requires something with no build for this MC
            # version — shipping the parent alone would refuse to boot.
            print(f"// !! {mc}: required dep {pid} has no build — parent must be dropped", file=sys.stderr)
            return None
        out.append(dep_ver)
        nested = resolve_required_deps(dep_ver, mc, chosen_project_ids, depth + 1)
        if nested is None:
            return None
        out.extend(nested)
    return out


def resolve_extras(mc: str, data_cs_text: str):
    """The full Extras pin list for one MC version: each EXTRA_PROJECTS entry
    that has a compatible build, plus its required deps (both-or-neither)."""
    pins = []
    chosen = set()
    for key, slug in EXTRA_PROJECTS.items():
        try:
            if key == "sodium-extra":
                pin = pick_sodium_extra(mc, pinned_sodium_from_data(data_cs_text, mc))
                if pin:
                    pins.append(pin)
                continue
            candidates = list_versions(slug, mc, releases_only=True)
            if not candidates:
                print(f"// {mc}: no {key} release — skipped (fail-soft)", file=sys.stderr)
                continue
            top = candidates[0]
            deps = resolve_required_deps(top, mc, chosen)
            if deps is None:
                print(f"// !! {mc}: {key} dropped — a required dep is unavailable", file=sys.stderr)
                continue
            pins.append(as_pin(top))
            pins.extend(as_pin(d) for d in deps)
        except Exception as e:  # network / API hiccup — report, don't guess
            print(f"// !! {mc} {key}: lookup failed ({e}) — omitted", file=sys.stderr)
    # De-dupe by filename (two extras sharing a dep), preserve order.
    seen, unique = set(), []
    for p in pins:
        if p and p[2] not in seen:
            seen.add(p[2])
            unique.append(p)
    return unique


def extras_fragment(pins):
    return ", Extras: new PerfMod[] { " + ", ".join(perfmod_cs(p) for p in pins) + " }"


def run_extras(mc_versions, apply: bool):
    data_path = Path(__file__).resolve().parents[2] / DATA_CS
    text = data_path.read_text(encoding="utf-8")
    lines = text.splitlines(keepends=True)
    for mc in mc_versions:
        pins = resolve_extras(mc, text)
        if not pins:
            print(f"// {mc}: no extras resolved — line left untouched", file=sys.stderr)
            continue
        frag = extras_fragment(pins)
        print(f'// {mc} extras ({len(pins)} jars):')
        print(f"//   {frag}")
        if not apply:
            continue
        for i, line in enumerate(lines):
            m = ENTRY_RE.match(line)
            if not m or m.group("mc") != mc:
                continue
            stripped = EXTRAS_FRAG_RE.sub("),", line.rstrip("\n"))
            body = stripped.rstrip()
            assert body.endswith("),"), f"unexpected entry shape for {mc}"
            lines[i] = body[:-2] + frag + "),\n"
            break
        else:
            print(f"// !! {mc}: no entry line found in Data.cs — nothing applied", file=sys.stderr)
    if apply:
        data_path.write_text("".join(lines), encoding="utf-8")
        print(f"// applied to {data_path}", file=sys.stderr)


def run_search(query: str):
    q = urllib.parse.urlencode({"query": query, "limit": 15})
    req = urllib.request.Request(f"{API}/search?{q}", headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=30) as r:
        hits = json.load(r)["hits"]
    for h in hits:
        print(f"{h['slug']:32} {h['title']:40} downloads={h['downloads']:>10} "
              f"loaders={','.join(h.get('display_categories', []))} versions={h['versions'][-1] if h.get('versions') else '?'}")


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
    if args[0] == "--search":
        run_search(" ".join(args[1:]))
    elif args[0] == "--extras":
        rest = args[1:]
        apply = "--apply" in rest
        versions = [a for a in rest if a != "--apply"]
        if not versions:
            print(__doc__)
            sys.exit(2)
        run_extras(versions, apply)
    else:
        main(args)
