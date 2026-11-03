#!/usr/bin/env bash
# Builds the four Linux downloads of the Origin Launcher for ONE architecture, plus a .sha256 next
# to each (sha256sum format: what the launcher's self-update and the website read):
#
#   linux-x64    OriginLauncher-linux-x86_64.AppImage   origin-launcher_amd64.deb
#                origin-launcher.x86_64.rpm             OriginLauncher-linux-x64.tar.gz
#   linux-arm64  OriginLauncher-linux-aarch64.AppImage  origin-launcher_arm64.deb
#                origin-launcher.aarch64.rpm            OriginLauncher-linux-arm64.tar.gz
#
# These names are fixed: the website links to releases/latest/download/<name>, installed launchers
# self-update from the AppImage and tar.gz ones (Core/Updates/UpdateService.Linux.cs), and
# tools/release/check_consistency.py fails CI if this script, UpdateService.Linux.cs and
# launcher-release.yml stop agreeing.
#
# All four carry the same self-contained .NET publish of src/OriginLauncher.Linux (no system .NET
# needed) with the Origin Client mod jars bundled exactly as on Windows (Bundled/OriginClient/
# originclient-<module>.jar for every module in src/mods/live-modules.json, Bundled/Forge/
# forge-1.8.9.json), a VERSION file, and the real logo art (src/OriginLauncher.App/Assets/origin.ico:
# its PNG frames are copied out byte for byte, never redrawn).
#
#   AppImage  one file, runs anywhere with FUSE; self-updates in place (its folder must be writable).
#   tar.gz    one top folder, OriginLauncher/; unpack anywhere writable; self-updates in place.
#   deb/rpm   /opt/origin-launcher (read-only, package-install marker) + /usr/bin/origin-launcher +
#             desktop entry + icons + AppStream metainfo. Self-updates go to a per-user copy that
#             /usr/bin/origin-launcher prefers while it is newer (UpdateService.Linux.cs).
#
# Usage (GitHub's Ubuntu runner of the same architecture; never on Will's PC):
#   tools/linux/package.sh --rid linux-x64 --version 1.0.76 --out DIR (--jars DIR | --bundled-zip ZIP)
#     --jars DIR         mod jars laid out DIR/<module>/<jar> (the release's build-mods artifacts)
#     --bundled-zip ZIP  take Bundled/OriginClient from a Windows release zip (branch CI: the jars of the
#                        latest release, so the boot test has a real Origin Client without a Gradle build)
#     --version ""       an unstamped dev build (assembly 1.0.0.0, packages say 0.0.0): never updates
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
FILES="$REPO/tools/linux/files"
APP_ID="org.originclient.Launcher"
rid="" version="" out="" jars="" bundled_zip=""
while [ $# -gt 0 ]; do
  case "$1" in
    --rid) rid="$2"; shift 2 ;;
    --version) version="$2"; shift 2 ;;
    --out) out="$2"; shift 2 ;;
    --jars) jars="$2"; shift 2 ;;
    --bundled-zip) bundled_zip="$2"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
case "$rid" in
  linux-x64)   uname_arch=x86_64;  deb_arch=amd64; nfpm_arch=amd64; tar_arch=x64 ;;
  linux-arm64) uname_arch=aarch64; deb_arch=arm64; nfpm_arch=arm64; tar_arch=arm64 ;;
  *) echo "--rid must be linux-x64 or linux-arm64" >&2; exit 2 ;;
esac
[ -n "$out" ] || { echo "--out is required" >&2; exit 2; }
[ -n "$jars$bundled_zip" ] || { echo "--jars or --bundled-zip is required" >&2; exit 2; }
pkg_version="${version:-0.0.0}"

APPIMAGE="OriginLauncher-linux-${uname_arch}.AppImage"
DEB="origin-launcher_${deb_arch}.deb"
RPM="origin-launcher.${uname_arch}.rpm"
TAR="OriginLauncher-linux-${tar_arch}.tar.gz"

mkdir -p "$out"
out="$(cd "$out" && pwd)"
work="$(mktemp -d)"
trap 'rm -r -f "$work"' EXIT
app="$work/OriginLauncher"

# --- tools, pinned by SHA-256 (tools/linux/pins.sh) --------------------------------------------
. "$REPO/tools/linux/pins.sh"
host="$(uname -m)"
fetch() { # url sha256 dest
  curl -fsSL --retry 4 --retry-delay 5 -o "$3" "$1"
  echo "$2  $3" | sha256sum -c --quiet - || { echo "::error::SHA-256 mismatch for $1" >&2; exit 1; }
}
tool_url() { eval "echo \${$1_URL_${host}}"; }
tool_sha() { eval "echo \${$1_SHA_${host}}"; }
fetch "$(tool_url APPIMAGETOOL)" "$(tool_sha APPIMAGETOOL)" "$work/appimagetool"
chmod +x "$work/appimagetool"
eval "fetch \"\$RUNTIME_URL_${uname_arch}\" \"\$RUNTIME_SHA_${uname_arch}\" \"$work/runtime\""
fetch "$(tool_url NFPM)" "$(tool_sha NFPM)" "$work/nfpm.tar.gz"
tar -xzf "$work/nfpm.tar.gz" -C "$work" nfpm

# --- the app: self-contained publish + bundled jars + VERSION -----------------------------------
publish=(publish "$REPO/src/OriginLauncher.Linux/OriginLauncher.Linux.csproj" -c Release -r "$rid"
         --self-contained true -o "$app" -nologo -v:q)
[ -n "$version" ] && publish+=("-p:Version=$version")
dotnet "${publish[@]}"
rm -f "$app"/*.pdb
[ -x "$app/OriginLauncher" ] || { echo "::error::publish produced no OriginLauncher executable" >&2; exit 1; }

python3 - "$REPO" "$app" "${jars}" "${bundled_zip}" <<'PY'
# Put exactly one Origin Client jar per live module where Core looks for it
# (OriginPaths.BundledOriginClientJar), like .github/actions/publish-launcher does on Windows.
import json, os, shutil, sys, zipfile
repo, app, jars, bundled_zip = sys.argv[1:5]
live = [m["module"] for m in json.load(open(os.path.join(repo, "src/mods/live-modules.json")))["modules"]]
dest = os.path.join(app, "Bundled", "OriginClient")
os.makedirs(dest, exist_ok=True)
problems = []
if jars:
    for m in live:
        d = os.path.join(jars, m)
        found = [f for f in (os.listdir(d) if os.path.isdir(d) else []) if f.endswith(".jar")]
        if len(found) != 1:
            problems.append(f"{m} ({len(found)} jars)")
            continue
        shutil.copyfile(os.path.join(d, found[0]), os.path.join(dest, f"originclient-{m}.jar"))
else:
    with zipfile.ZipFile(bundled_zip) as z:
        names = {os.path.basename(n): n for n in z.namelist() if n.replace("\\", "/").startswith("Bundled/OriginClient/")}
        for m in live:
            name = f"originclient-{m}.jar"
            if name not in names:
                problems.append(f"{m} (not in {os.path.basename(bundled_zip)})")
                continue
            with z.open(names[name]) as src, open(os.path.join(dest, name), "wb") as dst:
                shutil.copyfileobj(src, dst)
if problems:
    print("::error::need exactly one Origin Client jar per live module; wrong for: " + ", ".join(problems))
    sys.exit(1)
forge = os.path.join(app, "Bundled", "Forge")
os.makedirs(forge, exist_ok=True)
shutil.copyfile(os.path.join(repo, "src/OriginLauncher.App/Assets/Forge/forge-1.8.9.json"), os.path.join(forge, "forge-1.8.9.json"))
print(f"bundled {len(live)} Origin Client jars + the 1.8.9 Forge profile")
PY
echo "$pkg_version" > "$app/VERSION"

# --- icons: the PNG frames inside origin.ico, copied out unchanged ------------------------------
icons="$work/icons"
python3 - "$REPO/src/OriginLauncher.App/Assets/origin.ico" "$icons" <<'PY'
import os, struct, sys
ico, out = sys.argv[1:3]
data = open(ico, "rb").read()
count = struct.unpack_from("<H", data, 4)[0]
sizes = []
for i in range(count):
    w, h = data[6 + 16 * i] or 256, data[7 + 16 * i] or 256
    size, offset = struct.unpack_from("<II", data, 6 + 16 * i + 8)
    frame = data[offset:offset + size]
    if w != h or not frame.startswith(b"\x89PNG"):
        continue  # only square PNG frames are usable as-is
    os.makedirs(out, exist_ok=True)
    open(os.path.join(out, f"{w}.png"), "wb").write(frame)
    sizes.append(w)
if 256 not in sizes:
    sys.exit("origin.ico has no 256x256 PNG frame")
print("icons from origin.ico:", sorted(sizes))
PY

# --- tar.gz: one top folder, executable bits kept -----------------------------------------------
tar -C "$work" -czf "$out/$TAR" OriginLauncher

# --- AppImage ------------------------------------------------------------------------------------
appdir="$work/AppDir"
mkdir -p "$appdir/usr/lib" "$appdir/usr/share/applications" "$appdir/usr/share/metainfo"
cp -a "$app" "$appdir/usr/lib/origin-launcher"
install -m 0755 "$FILES/AppRun" "$appdir/AppRun"
cp "$FILES/$APP_ID.desktop" "$appdir/$APP_ID.desktop"
cp "$FILES/$APP_ID.desktop" "$appdir/usr/share/applications/$APP_ID.desktop"
sed "s/@VERSION@/$pkg_version/; s/@DATE@/$(date -u +%Y-%m-%d)/" "$FILES/$APP_ID.metainfo.xml" > "$work/$APP_ID.metainfo.xml"
cp "$work/$APP_ID.metainfo.xml" "$appdir/usr/share/metainfo/$APP_ID.metainfo.xml"
cp "$icons/256.png" "$appdir/$APP_ID.png"
ln -s "$APP_ID.png" "$appdir/.DirIcon"
for f in "$icons"/*.png; do
  s="$(basename "$f" .png)"
  mkdir -p "$appdir/usr/share/icons/hicolor/${s}x${s}/apps"
  cp "$f" "$appdir/usr/share/icons/hicolor/${s}x${s}/apps/$APP_ID.png"
done
# --no-appstream: the metainfo ships, but the runner has no appstreamcli to lint it with.
ARCH="$uname_arch" APPIMAGE_EXTRACT_AND_RUN=1 \
  "$work/appimagetool" --no-appstream --runtime-file "$work/runtime" "$appdir" "$out/$APPIMAGE"
chmod 0755 "$out/$APPIMAGE"

# --- deb + rpm (nfpm, one config) -----------------------------------------------------------------
pkgroot="$work/pkgroot"
cp -a "$app" "$pkgroot"
# Tells UpdateService.Linux.cs this is a package install: updates go to the per-user copy.
printf 'Installed by the origin-launcher package. Self-updates go to ~/.local/share/OriginLauncher/app;\n/usr/bin/origin-launcher starts that copy while it is newer than this one.\n' > "$pkgroot/package-install"
export NFPM_ARCH="$nfpm_arch" NFPM_VERSION="$pkg_version" PKG_APP="$pkgroot" PKG_FILES="$FILES" PKG_ICONS="$icons" \
       PKG_METAINFO="$work/$APP_ID.metainfo.xml"
# nfpm expands ${VAR} only in some fields (not contents src), so every ${VAR} is filled in here.
python3 - "$REPO/tools/linux/nfpm.yaml" "$work/nfpm.yaml" <<'PY'
import os, re, sys
src, dst = sys.argv[1:3]
text = re.sub(r"\$\{(\w+)\}", lambda m: os.environ[m.group(1)], open(src, encoding="utf-8").read())
open(dst, "w", encoding="utf-8").write(text)
PY
"$work/nfpm" package --config "$work/nfpm.yaml" --packager deb --target "$out/$DEB"
"$work/nfpm" package --config "$work/nfpm.yaml" --packager rpm --target "$out/$RPM"

# --- hashes ---------------------------------------------------------------------------------------
cd "$out"
for f in "$APPIMAGE" "$DEB" "$RPM" "$TAR"; do
  [ -s "$f" ] || { echo "::error::$f was not built" >&2; exit 1; }
  sha256sum "$f" > "$f.sha256"
  echo "$(cat "$f.sha256")  ($(du -h "$f" | cut -f1))"
done
