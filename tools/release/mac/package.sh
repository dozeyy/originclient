#!/bin/bash
# Builds one architecture's Mac release files and proves them on this Mac (launcher-release.yml's
# Mac jobs; never run on Will's PC). Output in <out-dir>:
#   <zip-name>          Origin.app zipped with ditto: the Mac self-update (UpdateService.Mac.cs
#                       unpacks it with ditto -x -k and swaps the bundle)
#   <zip-name>.sha256   its SHA-256, sha256sum format, written after the zip is final
#   <dmg-name>          the download: Origin.app + an Applications link
#
# Checks, each fails the job: exactly one jar per live module (the same rule as the Windows
# publish-launcher action), every live jar in the bundle, ad-hoc signature verifies (deep, strict),
# the zip unpacks the way the self-update unpacks it and still verifies, the disk image mounts, its
# app verifies, carries the right Info.plist and jars, and its --self-test passes when started from
# the mounted image and names this arch's zip and the stamped version.
#
# No Apple Developer account: the signature is ad hoc (codesign -s -), so Gatekeeper asks the
# player to confirm the first open (docs/RELEASING.md -> Mac).
#
# usage: package.sh <rid> <version or ""> <jars-dir> <out-dir> <zip-name> <dmg-name> [<windows-zip>]
# With <windows-zip> (OriginLauncher-win-x64.zip of the same run) the bundled jars must be
# byte-identical to the ones the Windows launcher ships.
set -euo pipefail

rid="$1"; version="$2"; jars_dir="$3"; out_dir="$4"; zip_name="$5"; dmg_name="$6"; windows_zip="${7:-}"
repo="$(cd "$(dirname "$0")/../../.." && pwd)"
work="$(mktemp -d "${RUNNER_TEMP:-/tmp}/origin-mac-XXXXXX")"
mkdir -p "$out_dir"
out_dir="$(cd "$out_dir" && pwd)"

fail() { echo "::error::$*"; exit 1; }

case "$rid" in
  osx-arm64) arch=arm64 ;;
  osx-x64)   arch=x86_64 ;;
  *) fail "unknown rid $rid" ;;
esac
[ "$(uname -m)" = "$arch" ] || fail "$rid must build and test on a $arch Mac, this runner is $(uname -m) (Rosetta results say nothing about real Intel Macs)"

# A tag build is stamped like Windows (-p:Version); a rehearsal stays the unstamped dev build.
# The version goes into sed and Info.plist, so only a plain 1.2.3 is accepted.
[ -z "$version" ] || [[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "version '$version' is not <major>.<minor>.<patch>"
if [ -n "$version" ]; then
  plist_version="$version"; expect_version="$version.0"
else
  plist_version="1.0.0"; expect_version="1.0.0.0 (dev build)"
fi

# ---- 1. Mod jars: exactly one per live module (same rule as .github/actions/publish-launcher) ----
modules="$(python3 -c 'import json,sys; print("\n".join(m["module"] for m in json.load(open(sys.argv[1]))["modules"]))' "$repo/src/mods/live-modules.json")"
problems=""
for m in $modules; do
  n=$( (find "$jars_dir/$m" -maxdepth 1 -name '*.jar' 2>/dev/null || true) | wc -l | tr -d ' ')
  [ "$n" = "1" ] || problems="$problems $m($n jars)"
done
[ -z "$problems" ] || fail "Need exactly one jar per live module; wrong for:$problems"
live_count=$(echo "$modules" | wc -l | tr -d ' ')

# ---- 2. Publish (self-contained, same switches as .github/workflows/mac-launcher.yml) ----
publish_args=(publish "$repo/src/OriginLauncher.Mac/OriginLauncher.Mac.csproj" -c Release -r "$rid" --self-contained true -o "$work/publish")
[ -n "$version" ] && publish_args+=("-p:Version=$version")
dotnet "${publish_args[@]}"

# ---- 3. Origin.app ----
app="$work/app/Origin.app"
mkdir -p "$app/Contents/MacOS" "$app/Contents/Resources"
ditto "$work/publish" "$app/Contents/MacOS"
[ -x "$app/Contents/MacOS/OriginLauncher" ] || fail "publish made no OriginLauncher executable"
file "$app/Contents/MacOS/OriginLauncher" | grep -q "$arch" || fail "OriginLauncher is not a $arch binary: $(file "$app/Contents/MacOS/OriginLauncher")"
# Data files in Resources (OriginPaths.Mac.cs reads the bundled jars from there inside a bundle).
bundled="$app/Contents/Resources/Bundled/OriginClient"
mkdir -p "$bundled"
for m in $modules; do
  cp "$(find "$jars_dir/$m" -maxdepth 1 -name '*.jar')" "$bundled/originclient-$m.jar"
done
sed "s/__VERSION__/$plist_version/g" "$repo/src/OriginLauncher.Mac/Packaging/Info.plist" > "$app/Contents/Info.plist"
plutil -lint "$app/Contents/Info.plist" >/dev/null || fail "Info.plist does not parse"
printf 'APPL????' > "$app/Contents/PkgInfo"

# Icon: the real logo file (never redrawn), only scaled down. It is 256 px, so the iconset stops at
# 256: macOS scales that up for its biggest views rather than shipping invented detail.
logo="$repo/src/mods/shared/src/client/resources/assets/originclient/textures/ui/origin_logo.png"
iconset="$work/Origin.iconset"
mkdir -p "$iconset"
for spec in 16:icon_16x16 32:icon_16x16@2x 32:icon_32x32 64:icon_32x32@2x 128:icon_128x128 256:icon_128x128@2x 256:icon_256x256; do
  sips -z "${spec%%:*}" "${spec%%:*}" "$logo" --out "$iconset/${spec#*:}.png" >/dev/null
done
iconutil -c icns -o "$app/Contents/Resources/Origin.icns" "$iconset"

# ---- 4. Ad-hoc signature ----
codesign --force --deep -s - "$app"
codesign --verify --deep --strict --verbose=2 "$app"

check_app() { # <app> <where>: the checks every copy of the app must pass
  local a="$1" where="$2" n
  codesign --verify --deep --strict "$a" || fail "$where: signature does not verify"
  [ "$(plutil -extract CFBundleIdentifier raw "$a/Contents/Info.plist")" = "org.originclient.launcher" ] || fail "$where: wrong bundle id"
  [ "$(plutil -extract CFBundleShortVersionString raw "$a/Contents/Info.plist")" = "$plist_version" ] || fail "$where: wrong version in Info.plist"
  [ -f "$a/Contents/Resources/Origin.icns" ] || fail "$where: no icon"
  for m in $modules; do
    [ -f "$a/Contents/Resources/Bundled/OriginClient/originclient-$m.jar" ] || fail "$where: missing bundled jar originclient-$m.jar"
  done
  n=$(find "$a/Contents/Resources/Bundled/OriginClient" -name '*.jar' | wc -l | tr -d ' ')
  [ "$n" = "$live_count" ] || fail "$where: $n bundled jars, expected $live_count"
}
check_app "$app" "built app"
echo "All $live_count live mod jars are bundled."

if [ -n "$windows_zip" ]; then
  python3 - "$windows_zip" "$bundled" <<'PY' || fail "the Mac app's mod jars differ from the Windows launcher's"
import hashlib, pathlib, sys, zipfile
win_zip, mac_dir = sys.argv[1], pathlib.Path(sys.argv[2])
win = {}
with zipfile.ZipFile(win_zip) as z:
    for info in z.infolist():
        name = info.filename.replace("\\", "/")  # Compress-Archive may write either separator
        if name.startswith("Bundled/OriginClient/") and name.endswith(".jar"):
            win[name.rsplit("/", 1)[1]] = hashlib.sha256(z.read(info)).hexdigest()
mac = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in mac_dir.glob("*.jar")}
if not win:
    sys.exit(f"no Bundled/OriginClient jars found in {win_zip}")
bad = sorted(n for n in set(win) | set(mac) if win.get(n) != mac.get(n))
for n in bad:
    print(f"  {n}: windows {win.get(n, 'missing')[:12]} mac {mac.get(n, 'missing')[:12]}")
if bad:
    sys.exit(1)
print(f"Same {len(mac)} mod jars as the Windows launcher, byte for byte.")
PY
fi

# ---- 5. Self-update zip + hash ----
(cd "$work/app" && ditto -c -k --keepParent Origin.app "$out_dir/$zip_name")
# Unpacked exactly the way the launcher unpacks an update (UpdateService.Mac.cs ExtractUpdate).
mkdir -p "$work/unzipped"
ditto -x -k "$out_dir/$zip_name" "$work/unzipped"
check_app "$work/unzipped/Origin.app" "unpacked zip"
hash=$(shasum -a 256 "$out_dir/$zip_name" | cut -d' ' -f1)
printf '%s  %s\n' "$hash" "$zip_name" > "$out_dir/$zip_name.sha256"
echo "$zip_name.sha256: $hash"

# ---- 6. Disk image: the app + an Applications link ----
stage="$work/dmg"
mkdir -p "$stage"
ditto "$app" "$stage/Origin.app"
ln -s /Applications "$stage/Applications"
hdiutil create -volname Origin -srcfolder "$stage" -fs HFS+ -format UDZO -ov "$out_dir/$dmg_name" >/dev/null
hdiutil verify "$out_dir/$dmg_name"

mnt="$work/mnt"
mkdir -p "$mnt"
hdiutil attach "$out_dir/$dmg_name" -readonly -nobrowse -mountpoint "$mnt" >/dev/null
trap 'hdiutil detach "$mnt" -force >/dev/null 2>&1 || true' EXIT
[ "$(readlink "$mnt/Applications")" = "/Applications" ] || fail "disk image has no Applications link"
check_app "$mnt/Origin.app" "mounted disk image"
log="$work/selftest.log"
set +e
"$mnt/Origin.app/Contents/MacOS/OriginLauncher" --self-test 2>&1 | tee "$log"
code=${PIPESTATUS[0]}
set -e
[ "$code" = "0" ] || fail "self-test from the mounted disk image exited $code"
grep -q "SELF-TEST PASSED" "$log" || fail "self-test from the mounted disk image did not pass"
grep -Eq "^  update asset +$zip_name\$" "$log" || fail "the app looks for the wrong update zip (expected $zip_name)"
grep -Fq "  launcher version $expect_version" "$log" || fail "the app's version is not $expect_version"
grep -Eq "^  bundled files +.*/Origin\.app/Contents/Resources\$" "$log" || fail "the app did not find its bundled files in Contents/Resources"
hdiutil detach "$mnt" >/dev/null
trap - EXIT

ls -l "$out_dir"
echo "OK: $rid packaged; zip and disk image verified, self-test passed from the mounted disk image."
