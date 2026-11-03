#!/bin/bash
# run-probe.sh <dmg> <proof dir>: mount the DMG, launch the app FROM it the way Finder does, read the result.
set -uo pipefail
DMG=$1; OUT=$2
mkdir -p "$OUT"
LOG="$OUT/mount.log"
MNT="$(mktemp -d)/vol"
mkdir -p "$MNT"
APP="$MNT/Origin Hello.app"

hdiutil attach "$DMG" -nobrowse -readonly -mountpoint "$MNT" 2>&1 | tee "$LOG" || exit 1
{
  echo "== volume contents"; ls -la "$MNT"
  echo "Applications link -> $(readlink "$MNT/Applications")"
  echo "== verify on the mounted image"; codesign --verify --deep --strict --verbose=2 "$APP"
} 2>&1 | tee -a "$LOG"
[ "$(readlink "$MNT/Applications")" = "/Applications" ] || { echo "drag link missing"; exit 1; }

# The job takes the whole-screen shot when the app says the page is up.
(
  for _ in $(seq 1 240); do
    if [ -f "$OUT/shot-now" ]; then
      screencapture -x "$OUT/screen.png"; touch "$OUT/shot-done"; break
    fi
    sleep 1
  done
) &
WATCH=$!

# perl alarm: macOS has no timeout(1). The app finishes by itself; this only stops a hang.
perl -e 'alarm shift; exec @ARGV' 300 open -W -n --stdout "$OUT/stdout.log" --stderr "$OUT/stderr.log" "$APP" --args --probe "$OUT"
RC=$?
echo "open exit=$RC" | tee -a "$LOG"
pkill -x MacHello 2>/dev/null
kill "$WATCH" 2>/dev/null
wait "$WATCH" 2>/dev/null

cp ~/Library/Logs/DiagnosticReports/MacHello* "$OUT/" 2>/dev/null
hdiutil detach "$MNT" 2>&1 | tee -a "$LOG" || hdiutil detach "$MNT" -force 2>&1 | tee -a "$LOG"

echo "== probe log"
tail -n 60 "$OUT/probe.log" 2>/dev/null || echo "no probe.log"
grep -q "path=$MNT/Origin Hello.app/Contents/MacOS/MacHello" "$OUT/probe.log" || { echo "FAIL: did not run from the mounted DMG"; exit 1; }
grep -q '^RESULT PASS' "$OUT/probe.log" || { echo "FAIL: probe did not pass"; exit 1; }
echo "probe passed, ran from $MNT"
