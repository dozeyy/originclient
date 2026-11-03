#!/bin/bash
# quarantine-test.sh <dmg> <proof dir>: mark the download the way a browser does and record what the
# Mac's own tools say. Only a record: nothing here can fail the step, the output is the finding.
set -uo pipefail
DMG=$1; OUT=$2
mkdir -p "$OUT"
LOG="$OUT/quarantine.log"
: > "$LOG"

WORK=$(mktemp -d)
QDMG="$WORK/OriginHello.dmg"
MNT="$WORK/vol"
APP="$WORK/Applications/Origin Hello.app"
mkdir -p "$WORK/Applications" "$MNT"
cp "$DMG" "$QDMG"
FLAG="0083;$(printf %x "$(date +%s)");Safari;$(uuidgen)"

{
  echo "== spctl --status (is Gatekeeper on, on this runner?)"; spctl --status
  echo "== sw_vers"; sw_vers

  echo "== quarantine the dmg"
  xattr -w com.apple.quarantine "$FLAG" "$QDMG"; xattr -l "$QDMG"
  echo "== spctl: dmg"
  spctl -a -vv -t open --context context:primary-signature "$QDMG"; echo "exit=$?"

  echo "== copy the app out, quarantined as a drag from that dmg would be"
  hdiutil attach "$QDMG" -nobrowse -readonly -mountpoint "$MNT" >/dev/null
  ditto "$MNT/Origin Hello.app" "$APP"
  hdiutil detach "$MNT" >/dev/null
  xattr -w com.apple.quarantine "$FLAG" "$APP"; xattr -l "$APP"
  echo "== spctl: app"
  spctl -a -vv -t execute "$APP"; echo "exit=$?"
  echo "== syspolicy_check distribution"
  syspolicy_check distribution "$APP" 2>&1 | tail -n 20; echo "exit=${PIPESTATUS[0]}"

  echo "== try to open it, 15 s, then picture of the screen"
  open "$APP"; echo "open exit=$?"
  sleep 15
  screencapture -x "$OUT/quarantine-open.png"
  echo "app process after 15 s: $(pgrep -x MacHello >/dev/null && echo running || echo 'not running')"
  echo "== syspolicyd's last words"
  log show --last 1m --style compact --predicate 'process == "syspolicyd"' 2>/dev/null | grep -iE 'hello|gatekeeper|notari|quarantine|GK ' | tail -n 15
  pkill -x MacHello
} 2>&1 | tee -a "$LOG"
exit 0
