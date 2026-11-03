#!/bin/bash
# package.sh <rid> <publish dir> <out dir>: .app -> ad-hoc signature -> strict verify -> DMG + zip.
set -euo pipefail
RID=$1; PUB=$2; OUT=$3
HERE=$(cd "$(dirname "$0")/.." && pwd)
NAME="Origin Hello"
ROOT="$OUT/$RID"
APP="$ROOT/$NAME.app"
LOG="$OUT/package-$RID.log"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
: > "$LOG"

ditto "$PUB" "$APP/Contents/MacOS"
cp "$HERE/Info.plist" "$APP/Contents/Info.plist"
chmod +x "$APP/Contents/MacOS/MacHello"

{
  echo "== lipo"; lipo -archs "$APP/Contents/MacOS/MacHello"
  echo "== codesign --force --deep -s -"; codesign --force --deep -s - "$APP"
  echo "== codesign --verify --deep --strict"; codesign --verify --deep --strict --verbose=2 "$APP"
  echo "verify exit=$?"
  echo "== signature"; codesign -dvvv "$APP" 2>&1 | grep -E '^(Identifier|Format|CDHash=|Signature|TeamIdentifier|Sealed)'
  echo "== app size"; du -sh "$APP"
} 2>&1 | tee -a "$LOG"

# The Mac "drag to Applications" window: the app plus a link to /Applications.
STAGE=$(mktemp -d)
ditto "$APP" "$STAGE/$NAME.app"
ln -s /Applications "$STAGE/Applications"
DMG="$OUT/OriginHello-$RID.dmg"
# hdiutil create can fail with "Resource busy" on hosted runners, so it gets three tries.
for try in 1 2 3; do
  rm -f "$DMG"
  if hdiutil create -volname "$NAME" -srcfolder "$STAGE" -fs HFS+ -format UDZO -ov "$DMG" 2>&1 | tee -a "$LOG"; then
    [ -s "$DMG" ] && break
  fi
  echo "hdiutil create try $try failed" | tee -a "$LOG"
  sleep 10
done
hdiutil verify "$DMG" 2>&1 | tail -n 1 | tee -a "$LOG"

ZIP="$OUT/OriginHello-$RID.zip"
ditto -c -k --sequesterRsrc --keepParent "$APP" "$ZIP"

# The zip has to give back a bundle that still verifies, or it can't be a download.
CHECK=$(mktemp -d)
ditto -x -k "$ZIP" "$CHECK"
codesign --verify --deep --strict "$CHECK/$NAME.app" 2>&1 | tee -a "$LOG"
echo "zip round trip verify ok" | tee -a "$LOG"

ls -la "$DMG" "$ZIP" | tee -a "$LOG"
