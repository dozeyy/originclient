#!/bin/bash
# keychain-test.sh <dmg> <proof dir>: store a test key from the app, replace the app with a re-signed
# build (a real update: different code hash), read the key back. The findings are the output; only a
# broken test (control read fails, code hash didn't change) fails the step.
set -uo pipefail
DMG=$1; OUT=$2
mkdir -p "$OUT"
LOG="$OUT/keychain.log"
: > "$LOG"
say() { echo "$*" | tee -a "$LOG"; }

WORK=$(mktemp -d)
APP="$WORK/Applications/Origin Hello.app"
BIN="$APP/Contents/MacOS/MacHello"
VALUE="$WORK/value.txt"
MNT="$WORK/vol"
mkdir -p "$WORK/Applications" "$MNT"

install_app() {
  hdiutil attach "$DMG" -nobrowse -readonly -mountpoint "$MNT" >/dev/null || exit 1
  rm -rf "$APP"
  ditto "$MNT/Origin Hello.app" "$APP"
  hdiutil detach "$MNT" >/dev/null
}
cdhash() { codesign -dvvv "$APP" 2>&1 | grep '^CDHash=' | cut -d= -f2; }

# A throwaway keychain as the default, so the runner's own keychain stays out of it. The access-list
# rules are the same as a login keychain's; the difference is that this one was unlocked by password.
KC="$WORK/probe.keychain-db"
OLD_DEFAULT=$(security default-keychain -d user | tr -d ' "')
security create-keychain -p probe "$KC"
security set-keychain-settings "$KC"
security unlock-keychain -p probe "$KC"
security list-keychains -d user -s "$KC"
security default-keychain -d user -s "$KC"
say "keychain: default=$(security default-keychain -d user | tr -d ' "')"

install_app
HASH1=$(cdhash)
say "== build 1 cdhash=$HASH1 version=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleVersion' "$APP/Contents/Info.plist")"
"$BIN" --keychain set "$VALUE" 2>&1 | tee -a "$LOG"

say "== control: same build reads its own keys (interaction off)"
"$BIN" --keychain get "$VALUE" 2>&1 | tee "$WORK/control.txt" | tee -a "$LOG"

# The replacement a self-update would do: delete, copy new, sign. Bumping the version changes the
# code hash the way any new build does (an identical rebuild would prove nothing).
install_app
/usr/libexec/PlistBuddy -c 'Set :CFBundleVersion 2' "$APP/Contents/Info.plist"
codesign --force --deep -s - "$APP"
HASH2=$(cdhash)
codesign --verify --deep --strict "$APP" 2>&1 | tee -a "$LOG"
say "== build 2 cdhash=$HASH2 version=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleVersion' "$APP/Contents/Info.plist")"

say "== after swap (interaction off: -25308 means it would have prompted)"
"$BIN" --keychain get "$VALUE" 2>&1 | tee "$WORK/after.txt" | tee -a "$LOG"

say "== after swap, prompts allowed, 25 s limit: does a dialog appear?"
perl -e 'alarm shift; exec @ARGV' 25 "$BIN" --keychain get "$VALUE" --allow-prompt 2>&1 | tee -a "$LOG" &
PROMPT=$!
sleep 12
say "SecurityAgent running: $(pgrep -x SecurityAgent >/dev/null && echo yes || echo no)"
screencapture -x "$OUT/keychain-prompt.png"
wait "$PROMPT"
pkill -x MacHello 2>/dev/null
say "prompt run finished (a line 'api find' above means it did not hang)"

security default-keychain -d user -s "$OLD_DEFAULT"
security list-keychains -d user -s "$OLD_DEFAULT"
security delete-keychain "$KC"

say "== verdict"
grep -q 'cli find exit=0 match=yes' "$WORK/control.txt" || { say "BROKEN TEST: cli control read failed"; exit 1; }
[ "$HASH1" != "$HASH2" ] || { say "BROKEN TEST: code hash did not change"; exit 1; }
if grep -q 'cli find exit=0 match=yes' "$WORK/after.txt"; then say "VERDICT cli: survived the swap, no prompt"; else say "VERDICT cli: did NOT survive"; fi
if ! grep -q 'api find status=0 match=yes' "$WORK/control.txt"; then say "VERDICT api: control failed, nothing shown"
elif grep -q 'api find status=0 match=yes' "$WORK/after.txt"; then say "VERDICT api: survived the swap, no prompt"
else say "VERDICT api: did NOT survive, $(grep 'api find' "$WORK/after.txt")"; fi
exit 0
