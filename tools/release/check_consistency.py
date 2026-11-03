#!/usr/bin/env python3
"""Fail CI when facts that must live in two places drift apart.

Each pair below exists in two files for a real reason (different languages or
runtimes), so instead of pretending there is one copy, this checks they agree:

1. Playable versions — src/mods/live-modules.json "versions" (read by the
   installer's default-version picker, .NET Framework, no access to launcher
   code) must equal the launcher's VersionManager.OriginBuilds entries, jar by jar.
2. The installer -> launcher hand-off file name — src/OriginSetup/Engine/
   Handoff.cs and the launcher's Core/Install/SetupChoices.cs.
3. Theme tokens — the installer owns its Workbench token and type files
   (src/OriginSetup/Theme/Tokens.xaml, Typography.xaml), so they are not copies of the launcher's. Both sets are
   read at run time and every shared key (Color.*, Size.Control*, Radius.*, Font.*, FontSize.*, LineHeight.*, Space.*,
   plus the PAIRED_KEYS role names) must agree, except the keys listed in KNOWN_DIVERGENCE (each with a reason; an
   entry that starts to agree must be deleted). The face (Inter) and Body/Caption sizes are always required.
4. The release feed — the installer downloads the launcher from the same
   GitHub release asset the launcher self-updates from: owner, repo, tag
   prefix and zip name in src/OriginSetup/Engine/ReleaseFeed.cs must match
   the launcher's Core/Updates/UpdateService.cs.
5. The version picker — every version in the launcher's Core/Versions/
   VersionCatalog.cs grid must be in VersionManager.OriginBuilds and vice
   versa (both C#, but hand-authored separately). A version without a build
   must never be shown (no greyed "Coming Soon" entries — Will, 2026-10-12),
   and a shipped version must never be missing from the picker.

6. The self-update hash — the launcher's UpdateService looks for the zip's
   ReleaseAssetName + ".sha256" release asset (on Windows ReleaseAssetName is AssetName) and the release workflow must publish
   exactly that (both in the `gh release create` list and the rehearsal artifact).
7. The Linux downloads — the names tools/linux/package.sh builds must include the AppImage
   and tar.gz names the Linux self-update reads (Core/Updates/UpdateService.Linux.cs), and
   launcher-release.yml must attach the x64 four (and any arm64 one it lists) plus their .sha256
   and assert them; the deb/rpm
   per-user copy (OriginPaths.UserAppCopy) and the /usr/bin wrapper must name the same folder.

8. The small self-update (delta) — tools/release/delta.py builds it and the launcher's Core/Updates/UpdateDelta.cs
   applies it: the asset name (<win zip prefix>.delta-from-<version>.zip), its source zip, and the manifest and
   payload names inside, plus the binary patch format (magic and ops), must agree, and launcher-release.yml must build, keep (rehearsal) and upload the deltas.

7. The Mac release files — the Mac launcher's self-update zip per arch (Core/Updates/MacReleaseAssets.cs, used by
   UpdateService.Mac.cs) must be the zip launcher-release.yml builds for that arch, and every Mac file (both zips,
   their .sha256, both disk images) must be in the Mac upload and the Mac rehearsal artifact.

Run from anywhere: python3 tools/release/check_consistency.py
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
LAUNCHER = REPO / "src" / "OriginLauncher.App"
SETUP = REPO / "src" / "OriginSetup"


def origin_builds() -> dict[str, set[str]]:
    """VersionManager.OriginBuilds as {jar module: {Minecraft versions}}, commented-out entries skipped."""
    source = (LAUNCHER / "Core/Versions/VersionManager.cs").read_text(encoding="utf-8")
    builds: dict[str, set[str]] = {}
    for line in source.splitlines():
        if line.strip().startswith("//"):
            continue
        m = re.search(r'\["([^"]+)"\]\s*=\s*new\("originclient-([^"]+)\.jar"', line)
        if m:
            builds.setdefault(m.group(2), set()).add(m.group(1))
    return builds


def check_versions() -> list[str]:
    live = json.loads((REPO / "src/mods/live-modules.json").read_text(encoding="utf-8"))["modules"]
    builds = origin_builds()

    problems = []
    listed = {m["module"]: set(m.get("versions", [])) for m in live}
    for module in sorted(set(builds) | set(listed)):
        a, b = listed.get(module), builds.get(module)
        if a != b:
            problems.append(f"versions for module {module}: live-modules.json says {sorted(a or [])}, "
                            f"VersionManager.OriginBuilds says {sorted(b or [])}")
    return problems


def check_handoff() -> list[str]:
    pattern = r'FileName\s*=\s*"([^"]+)"'
    a = re.search(pattern, (SETUP / "Engine/Handoff.cs").read_text(encoding="utf-8"))
    b = re.search(pattern, (LAUNCHER / "Core/Install/SetupChoices.cs").read_text(encoding="utf-8"))
    if not a or not b or a.group(1) != b.group(1):
        return [f"hand-off file name: installer {a and a.group(1)!r} vs launcher {b and b.group(1)!r}"]
    return []


# ---- Theme token parity (typography + the tokens the two apps share) ----
# The installer keeps its own Theme/Tokens.xaml and Typography.xaml (it is a separate .NET Framework app), so the
# files cannot be one copy. Instead both sides are READ and every key defined in both is compared. Nothing is
# hard-coded here except the list of keys that are allowed to differ and why: a key that differs and is NOT listed
# fails the build, and a listed key that has started to agree fails too ("remove it"), so the list cannot rot.
# Empty since Phase 3 U3.0: the launcher and the installer share the Workbench tokens, same names, same values.
# A key may only be listed again with a real, lasting reason.
THEME_FAMILIES = ("Color.", "Size.Control", "Radius.", "Font.", "FontSize.", "LineHeight.", "Space.")

# Exact key -> why the two files differ. The check fails on an entry that already agrees and on a key that no longer
# exists in both files, so the list cannot outlive the difference.
KNOWN_DIVERGENCE: dict[str, str] = {}   # Phase 3 U3.0: the launcher moved to the Workbench tokens, nothing diverges

# (launcher key, installer key): the launcher names corner radii by role, the installer by size.
PAIRED_KEYS: list[tuple[str, str]] = []   # both apps now use the same Radius.* names (U3.0)


def _theme_values(paths: list[Path]) -> dict[str, str]:
    """Every `x:Key` in the given XAML files -> its value (element text, Color= attribute, or a Style's setters),
    whitespace-collapsed, with the assembly name in font paths normalised so the two apps can match."""
    out: dict[str, str] = {}
    for path in paths:
        text = re.sub(r"<!--.*?-->", "", path.read_text(encoding="utf-8"), flags=re.S)
        for m in re.finditer(r'<([\w:.]+)\s+([^<>]*?\bx:Key="([^"]+)"[^<>]*?)(?:/>|>(.*?)</\1>)', text, flags=re.S):
            attrs, key, body = m.group(2), m.group(3), m.group(4)
            if body is None or not body.strip():
                color = re.search(r'\bColor="([^"]*)"', attrs)
                body = color.group(1) if color else re.sub(r'x:Key="[^"]*"', "", attrs)
            value = re.sub(r"\s+", " ", body).strip()
            out[key] = re.sub(r"/[A-Za-z.]+;component/", "/<assembly>;component/", value)
    return out


def check_theme_parity() -> list[str]:
    launcher = _theme_values([LAUNCHER / "Theme" / n for n in ("Colors.xaml", "Spacing.xaml", "Typography.xaml")])
    setup = _theme_values([SETUP / "Theme" / n for n in ("Tokens.xaml", "Typography.xaml")])
    problems: list[str] = []
    common = sorted(k for k in set(launcher) & set(setup) if k.startswith(THEME_FAMILIES))
    if len(common) < 10:  # the parser lost the files: refuse to pass on an empty comparison
        return [f"theme parity found only {len(common)} shared keys; the XAML layout changed, update check_theme_parity"]

    for key in ("Font.Primary", "FontSize.Body", "FontSize.Caption"):
        if key not in common:
            problems.append(f"{key} must be defined in both launcher and installer theme files")
    if "#Inter" not in launcher.get("Font.Primary", "") or "#Inter" not in setup.get("Font.Primary", ""):
        problems.append(f"Font.Primary must be Inter in both (launcher {launcher.get('Font.Primary')!r}, installer {setup.get('Font.Primary')!r})")

    differing = [k for k in common if launcher[k] != setup[k]]
    for key in differing:
        if key not in KNOWN_DIVERGENCE:
            problems.append(f"{key}: launcher {launcher[key]!r} vs installer {setup[key]!r} "
                            f"(the two apps share this token; make them agree, or list it in KNOWN_DIVERGENCE with the reason)")
    for key, why in KNOWN_DIVERGENCE.items():
        if key not in common:
            problems.append(f"KNOWN_DIVERGENCE {key!r} is no longer defined in both files; delete the entry")
        elif key not in differing:
            problems.append(f"KNOWN_DIVERGENCE {key!r} ({why}) now agrees; delete the entry")

    # Same job under a different name: launcher key -> installer key. The names are listed, the values are read.
    for lk, sk in PAIRED_KEYS:
        if lk not in launcher or sk not in setup:
            problems.append(f"PAIRED_KEYS {lk} <-> {sk}: one of them no longer exists, update the pair")
        elif launcher[lk] != setup[sk]:
            problems.append(f"{lk} (launcher {launcher[lk]!r}) vs {sk} (installer {setup[sk]!r}) must agree")
    return problems


def check_release_feed() -> list[str]:
    def consts(path: Path) -> dict[str, str]:
        return dict(re.findall(r'const string (Owner|Repo|TagPrefix|AssetName)\s*=\s*"([^"]*)"',
                               path.read_text(encoding="utf-8")))
    a = consts(SETUP / "Engine/ReleaseFeed.cs")
    b = consts(LAUNCHER / "Core/Updates/UpdateService.cs")
    names = ("Owner", "Repo", "TagPrefix", "AssetName")
    if any(n not in a or n not in b for n in names):
        return [f"release feed constants not found (installer {sorted(a)}, launcher {sorted(b)})"]
    return [f"release feed {n}: installer {a[n]!r} vs launcher {b[n]!r}" for n in names if a[n] != b[n]]


def check_update_hash() -> list[str]:
    svc = (LAUNCHER / "Core/Updates/UpdateService.cs").read_text(encoding="utf-8")
    wf = (REPO / ".github/workflows/launcher-release.yml").read_text(encoding="utf-8")
    win = (LAUNCHER / "Core/Updates/UpdateService.Windows.cs").read_text(encoding="utf-8")
    name = re.search(r'const string AssetName\s*=\s*"([^"]*)"', svc)
    # The lookup really used: ReleaseAssetName + ".sha256", where Windows' ReleaseAssetName is AssetName.
    if not name or not re.search(r'a\.Name,\s*ReleaseAssetName\s*\+\s*"\.sha256"', svc) \
            or not re.search(r'ReleaseAssetName\s*=\s*AssetName\s*;', win):
        return ["UpdateService no longer looks up ReleaseAssetName + \".sha256\" with Windows' ReleaseAssetName = AssetName (update check_update_hash)"]
    hash_name = name.group(1) + ".sha256"
    n = len(re.findall(r"^\s+" + re.escape(hash_name) + r"\s*$", wf, re.M))
    return [] if n >= 2 else [f"launcher-release.yml must list {hash_name} in the release and the rehearsal artifact (found {n}x)"]


def check_update_delta() -> list[str]:
    """The small self-update: tools/release/delta.py builds it, Core/Updates/UpdateDelta.cs applies it. The asset
    name (<win zip prefix>.delta-from-<version>.zip), the zip it is built from, and the manifest/payload names inside
    must agree, and launcher-release.yml must build the deltas, keep them in the rehearsal artifact and upload them
    with their .sha256."""
    py = (REPO / "tools/release/delta.py").read_text(encoding="utf-8")
    cs = (LAUNCHER / "Core/Updates/UpdateDelta.cs").read_text(encoding="utf-8")
    svc = (LAUNCHER / "Core/Updates/UpdateService.cs").read_text(encoding="utf-8")
    win = (LAUNCHER / "Core/Updates/UpdateService.Windows.cs").read_text(encoding="utf-8")
    wf = (REPO / ".github/workflows/launcher-release.yml").read_text(encoding="utf-8")

    def py_const(name: str) -> str | None:
        m = re.search(r'^' + name + r'\s*=\s*"([^"]*)"', py, re.M)
        return m.group(1) if m else None

    def cs_const(text: str, name: str) -> str | None:
        m = re.search(r'const string ' + name + r'\s*=\s*"([^"]*)"', text)
        return m.group(1) if m else None

    problems: list[str] = []
    pairs = [
        ("full zip", py_const("ASSET"), cs_const(svc, "AssetName")),
        ("zip prefix", py_const("ZIP_PREFIX"), cs_const(win, "ReleaseZipPrefix")),
        ("manifest entry", py_const("MANIFEST"), cs_const(cs, "ManifestEntry")),
        ("payload folder", py_const("FILES_PREFIX"), cs_const(cs, "FilesPrefix")),
        ("patch folder", py_const("PATCHES_PREFIX"), cs_const(cs, "PatchesPrefix")),
        ("patch magic", py_const("PATCH_MAGIC"), cs_const(cs, "PatchMagic")),
    ]
    # The patch ops: delta.py OP_COPY = "C" etc. vs UpdateDelta.cs OpCopy = (byte)'C' etc.
    for py_name, cs_name in (("OP_COPY", "OpCopy"), ("OP_LITERAL", "OpLiteral"), ("OP_END", "OpEnd")):
        m = re.search(r"const byte " + cs_name + r"\s*=\s*\(byte\)'(.)'", cs)
        pairs.append((f"patch op {py_name}", py_const(py_name), m.group(1) if m else None))
    for what, a, b in pairs:
        if a is None or b is None:
            problems.append(f"delta {what}: constant not found (delta.py {a!r}, launcher {b!r}; update check_update_delta)")
        elif a != b:
            problems.append(f"delta {what}: delta.py {a!r} vs launcher {b!r}")
    if 'f"{ZIP_PREFIX}.delta-from-{from_version}.zip"' not in py or 'zipPrefix + ".delta-from-"' not in cs:
        problems.append("delta asset name: delta.py delta_name and UpdateDelta.IsDeltaFor must both be <prefix>.delta-from-<version>.zip")
    if not re.search(r'UpdateDelta\.IsDeltaFor\(a\.Name,\s*ReleaseZipPrefix,', svc) \
            or not re.search(r'a\.Name,\s*delta\.Name\s*\+\s*"\.sha256"', svc):
        problems.append("UpdateService.FindDelta no longer matches deltas by ReleaseZipPrefix with a <delta>.sha256 sidecar")
    if "tools/release/delta.py release" not in wf:
        problems.append("launcher-release.yml must build the deltas with tools/release/delta.py release")
    if not re.search(r"^\s+deltas/\s*$", wf, re.M):
        problems.append("launcher-release.yml must keep deltas/ in the rehearsal artifact")
    if "'OriginLauncher-win-x64.delta-from-*.zip'" not in wf or not re.search(r'gh release upload[^\n]*\.sha256', wf):
        problems.append("launcher-release.yml must upload each delta and its .sha256 to the release")
    return problems


# The Mac disk images are downloads only (the launcher never looks them up), but the website links them by
# these exact names, so the workflow must keep publishing them under these names.
MAC_DMGS = {"osx-arm64": "Origin-Mac-AppleSilicon.dmg", "osx-x64": "Origin-Mac-Intel.dmg"}


def check_mac_release() -> list[str]:
    """The Mac self-update zips the launcher looks for (Core/Updates/MacReleaseAssets.cs, used by
    UpdateService.Mac.cs) must be exactly the ones launcher-release.yml builds per arch and publishes,
    each with its .sha256, plus the two disk images."""
    assets = (LAUNCHER / "Core/Updates/MacReleaseAssets.cs").read_text(encoding="utf-8")
    mac_svc = (LAUNCHER / "Core/Updates/UpdateService.Mac.cs").read_text(encoding="utf-8")
    wf = (REPO / ".github/workflows/launcher-release.yml").read_text(encoding="utf-8")
    prefixes = dict(re.findall(r'const string (AppleSiliconZipPrefix|IntelZipPrefix)\s*=\s*"([^"]*)"', assets))
    if set(prefixes) != {"AppleSiliconZipPrefix", "IntelZipPrefix"}:
        return ["MacReleaseAssets.cs: AppleSiliconZipPrefix / IntelZipPrefix constants not found (update check_mac_release)"]
    want_zip = {"osx-arm64": prefixes["AppleSiliconZipPrefix"] + ".zip", "osx-x64": prefixes["IntelZipPrefix"] + ".zip"}
    if not re.search(r'Architecture\.Arm64\s*=>\s*AppleSiliconZipPrefix', assets) \
            or not re.search(r'Architecture\.X64\s*=>\s*IntelZipPrefix', assets):
        return ["MacReleaseAssets.ZipPrefixFor no longer maps Arm64 -> AppleSiliconZipPrefix and X64 -> IntelZipPrefix"]
    if not re.search(r'ReleaseZipPrefix\s*=\s*MacReleaseAssets\.ZipPrefixFor\(RuntimeInformation\.ProcessArchitecture\)', mac_svc) \
            or not re.search(r'ReleaseAssetName\s*=\s*ReleaseZipPrefix\s*\+\s*"\.zip"', mac_svc):
        return ["UpdateService.Mac.cs no longer takes its zip name from MacReleaseAssets.ZipPrefixFor (update check_mac_release)"]

    problems: list[str] = []
    # The build matrix: each rid builds exactly the zip and disk image named for it.
    matrix = {m.group(1): (m.group(2), m.group(3)) for m in re.finditer(
        r'rid:\s*(osx-[\w]+)\s*\n\s*zip:\s*(\S+)\s*\n\s*dmg:\s*(\S+)', wf)}
    for rid in ("osx-arm64", "osx-x64"):
        if rid not in matrix:
            problems.append(f"launcher-release.yml: no Mac matrix entry for {rid} (rid/zip/dmg lines)")
            continue
        zip_name, dmg = matrix[rid]
        if zip_name != want_zip[rid]:
            problems.append(f"launcher-release.yml builds {zip_name} for {rid}, but the launcher looks for {want_zip[rid]}")
        if dmg != MAC_DMGS[rid]:
            problems.append(f"launcher-release.yml builds {dmg} for {rid}, expected {MAC_DMGS[rid]}")
    # Published AND kept in the rehearsal artifact: each file on its own line at least twice.
    for name in [*want_zip.values(), *(z + ".sha256" for z in want_zip.values()), *MAC_DMGS.values()]:
        n = len(re.findall(r"^\s+(?:mac/)?" + re.escape(name) + r"\s*$", wf, re.M))
        if n < 2:
            problems.append(f"launcher-release.yml must list {name} in the Mac release upload and the rehearsal artifact (found {n}x)")
    if not re.search(r"gh release upload\b", wf):
        problems.append("launcher-release.yml: the Mac files must be ADDED with gh release upload (never a second gh release create)")
    # The Windows job has two mutually exclusive publish steps (with or without the Linux files); every
    # create is in it, and nothing from the Mac jobs on can create a release.
    before_mac, _, mac_jobs = wf.partition("\n  mac:\n")
    if not re.search(r"gh release create\b", before_mac) or re.search(r"gh release create\b", mac_jobs):
        problems.append("launcher-release.yml must create the release only in the Windows job, never in the Mac jobs")
    return problems


def check_picker() -> list[str]:
    # Each card is Group("key", "title", "tagline", "description", "ver", "ver", ...):
    # the versions are every string literal after the first four.
    source = (LAUNCHER / "Core/Versions/VersionCatalog.cs").read_text(encoding="utf-8-sig")
    code = "\n".join(l for l in source.splitlines() if not l.strip().startswith("//"))
    body = code[code.index("Build() =>"):]
    picker: list[str] = []
    for call in re.findall(r'\bGroup\((.*?)\),\s*$', body, flags=re.S | re.M):
        picker += re.findall(r'"((?:[^"\\]|\\.)*)"', call)[4:]
    built = set().union(*origin_builds().values())
    if not picker:
        return ["version picker: no Group(...) versions found in VersionCatalog.cs (did its shape change?)"]
    problems = [f"version picker lists {v} twice" for v in sorted({v for v in picker if picker.count(v) > 1})]
    problems += [f"version picker shows {v}, which has no VersionManager.OriginBuilds entry "
                 f"(an unplayable version must not appear in the picker)" for v in picker if v not in built]
    problems += [f"VersionManager.OriginBuilds ships {v}, but VersionCatalog's picker does not list it"
                 for v in sorted(built - set(picker))]
    return problems


def linux_package_names() -> list[str]:
    """The eight download names tools/linux/package.sh builds: its four name templates expanded with the
    arch table of its `case "$rid"` (linux-x64 / linux-arm64)."""
    sh = (REPO / "tools/linux/package.sh").read_text(encoding="utf-8")
    templates = re.findall(r'^(?:APPIMAGE|DEB|RPM|TAR)="([^"]+)"$', sh, re.M)
    arches = [dict(re.findall(r'(\w+_arch)=(\w+)', line))
              for line in re.findall(r'^\s*linux-(?:x64|arm64)\)(.*);;\s*$', sh, re.M)]
    if len(templates) != 4 or len(arches) != 2:
        return []
    return [re.sub(r'\$\{(\w+)\}', lambda m: a.get(m.group(1), "?"), t) for a in arches for t in templates]


def check_linux_assets() -> list[str]:
    names = linux_package_names()
    if len(names) != 8:
        return ["tools/linux/package.sh: could not read its 4 asset-name templates and 2 arch rows (update check_linux_assets)"]
    svc = (LAUNCHER / "Core/Updates/UpdateService.Linux.cs").read_text(encoding="utf-8")
    wf = (REPO / ".github/workflows/launcher-release.yml").read_text(encoding="utf-8")
    problems: list[str] = []
    # The launcher self-updates from the AppImage and tar.gz names: package.sh must build exactly those.
    updates = re.findall(r'"(OriginLauncher-linux-[^"]+)"', svc)
    if len(updates) != 4:
        problems.append(f"UpdateService.Linux.cs: expected 4 update asset names, found {updates}")
    problems += [f"UpdateService.Linux.cs updates from {n}, which tools/linux/package.sh does not build"
                 for n in updates if n not in names]
    # The release publishes the x64 four (arm64 joins only once a game boots there: launcher-release.yml),
    # each with its .sha256 (the Linux self-update refuses an asset without one), and its assert step refuses
    # to publish when one is missing. An arm64 name, once released, gets the same checks.
    def attached(asset: str) -> bool:
        return bool(re.search(r"^\s+" + re.escape(asset) + r"\s*$", wf, re.M))
    released = [n for n in names if attached(n) or attached(n + ".sha256") or f"'{n}'" in wf]
    problems += [f"launcher-release.yml: the x64 download {n} is not released" for n in names[:4] if n not in released]
    for n in released:
        for asset in (n, n + ".sha256"):
            if not attached(asset):
                problems.append(f"launcher-release.yml: `gh release create` does not attach {asset}")
        if f"'{n}'" not in wf:
            problems.append(f"launcher-release.yml: the Linux assert step does not check {n}")
    # deb/rpm self-updates: the launcher writes the per-user copy where /usr/bin/origin-launcher looks.
    paths = (LAUNCHER / "Core/OriginPaths.Linux.cs").read_text(encoding="utf-8")
    wrapper = (REPO / "tools/linux/files/origin-launcher").read_text(encoding="utf-8")
    copy = re.search(r'UserAppCopy\s*=>\s*Path\.Combine\(Root,\s*"([^"]+)"\)', paths)
    root = re.search(r'DefaultRoot\s*=>.*"(\w+)"\);', paths)
    if not copy or not root:
        problems.append("OriginPaths.Linux.cs: DefaultRoot / UserAppCopy shape changed (update check_linux_assets)")
    elif f'user="$data/{root.group(1)}/{copy.group(1)}"' not in wrapper:
        problems.append(f"tools/linux/files/origin-launcher must look for the per-user copy in "
                        f"$data/{root.group(1)}/{copy.group(1)} (OriginPaths.UserAppCopy)")
    # Which install this is (Core/Install/LinuxInstall.Linux.cs) must match how package.sh lays each one out.
    kind = (LAUNCHER / "Core/Install/LinuxInstall.Linux.cs").read_text(encoding="utf-8")
    sh = (REPO / "tools/linux/package.sh").read_text(encoding="utf-8")
    marker = re.search(r'PackageMarkerName\s*=\s*"([^"]+)"', kind)
    if not marker or f'"$pkgroot/{marker.group(1)}"' not in sh:
        problems.append("the deb/rpm marker file: LinuxInstall.Linux.cs PackageMarkerName and package.sh must name the same file")
    # An AppImage run is trusted only from $APPDIR/<this folder>: package.sh must copy the app there and AppRun start it.
    inner = re.search(r'AppImageAppFolder\s*=\s*"([^"]+)"', kind)
    apprun = (REPO / "tools/linux/files/AppRun").read_text(encoding="utf-8")
    if not inner or f'"$appdir/{inner.group(1)}"' not in sh or f'"$here/{inner.group(1)}/OriginLauncher"' not in apprun:
        problems.append("the AppImage app folder: LinuxInstall.Linux.cs AppImageAppFolder, package.sh and AppRun must agree")
    # The login entry of a deb/rpm starts the PATH wrapper: nfpm must install it at exactly that path.
    launcher = re.search(r'PackageLauncher\s*=\s*"([^"]+)"', kind)
    nfpm = (REPO / "tools/linux/nfpm.yaml").read_text(encoding="utf-8")
    if not launcher or not re.search(r'dst:\s*' + re.escape(launcher.group(1)) + r'\s*$', nfpm, re.M):
        problems.append("the deb/rpm PATH launcher: LinuxInstall.Linux.cs PackageLauncher must be where nfpm.yaml installs it")
    return problems


def main() -> int:
    problems = (check_versions() + check_handoff() + check_theme_parity() + check_release_feed()
                + check_update_hash() + check_update_delta() + check_mac_release() + check_picker()
                + check_linux_assets())
    for p in problems:
        print(f"::error::{p}")
    if not problems:
        print("OK: version list, version picker, hand-off file name, theme tokens, release feed, update hash, small "
              "updates, Mac release files and Linux downloads agree across launcher, installer and release.")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
