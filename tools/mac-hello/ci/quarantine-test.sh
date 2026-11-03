#!/bin/bash
# quarantine-test.sh <dmg> <proof dir>: mark the download the way a browser does and record what the
# Mac's own tools say. Only a record: nothing here can fail the step, the output is the finding.
# Every command runs under a time limit: the first run hung this step for 30 minutes (likely a
# Gatekeeper dialog holding `open`, or a network check), and took the whole job's logs with it.
set -uo pipefail
DMG=$1; OUT=$2
mkdir -p "$OUT"
LOG="$OUT/quarantine.log"
: > "$LOG"
t() { local s=$1; shift; perl -e 'alarm shift; exec @ARGV' "$s" "$@"; local rc=$?; [ $rc -eq 142 ] && echo "(timed out after ${s}s: $1)"; return $rc; }

WORK=$(mktemp -d)
QDMG="$WORK/OriginHello.dmg"
MNT="$WORK/vol"
APP="$WORK/Applications/Origin Hello.app"
mkdir -p "$WORK/Applications" "$MNT"
cp "$DMG" "$QDMG"
FLAG="0083;$(printf %x "$(date +%s)");Safari;$(uuidgen)"

{
  echo "== spctl --status (is Gatekeeper on, on this runner?)"; t 20 spctl --status
  echo "== sw_vers"; sw_vers

  echo "== quarantine the dmg"
  xattr -w com.apple.quarantine "$FLAG" "$QDMG"; xattr -l "$QDMG"
  echo "== spctl: dmg"
  t 60 spctl -a -vv -t open --context context:primary-signature "$QDMG"; echo "exit=$?"

  echo "== copy the app out, quarantined as a drag from that dmg would be"
  t 60 hdiutil attach "$QDMG" -nobrowse -readonly -mountpoint "$MNT" >/dev/null
  ditto "$MNT/Origin Hello.app" "$APP"
  t 60 hdiutil detach "$MNT" >/dev/null
  xattr -w com.apple.quarantine "$FLAG" "$APP"; xattr -l "$APP"
  echo "== spctl: app"
  t 60 spctl -a -vv -t execute "$APP"; echo "exit=$?"
  echo "== syspolicy_check distribution"
  t 90 syspolicy_check distribution "$APP" 2>&1 | tail -n 20

  echo "== try to open it (open itself limited to 20 s), then a picture of the screen"
  t 20 open "$APP"; echo "open exit=$?"
  sleep 10
  screencapture -x "$OUT/quarantine-open.png"
  echo "app process after the wait: $(pgrep -x MacHello >/dev/null && echo running || echo 'not running')"
  echo "== syspolicyd's last words"
  t 60 log show --last 2m --style compact --predicate 'process == "syspolicyd"' 2>/dev/null | grep -iE 'hello|gatekeeper|notari|quarantine|GK ' | tail -n 15
  pkill -x MacHello
  pkill -x open
} 2>&1 | tee -a "$LOG"
exit 0
