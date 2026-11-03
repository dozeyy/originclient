#!/bin/bash
# The Mac self-update, end to end, against a FAKE release on this machine (mac-launcher.yml's update-proof job;
# never run on Will's PC). Nothing touches GitHub: the release feed, the zip and its .sha256 are files in a scratch
# folder served by python's http.server on 127.0.0.1, and the launcher's own --update-proof switch (UpdateProof.cs)
# drives the real UpdateService against it. Each case gets its own throwaway data root.
#
#   no-hash    a release with no .sha256 and no digest: never offered, never gates Play, nothing downloaded
#   bad-hash   the .sha256 does not match the zip: offered, downloaded, REFUSED, nothing unpacked, Play released
#   refuse     the app runs from a translocated copy, then from a folder it can't write to: never offered, never
#              gates Play, nothing downloaded, and the player is told to move it to Applications
#   swap       offered, gates Play, downloads, hash matches, unpacks, writes and starts the swap script, quits; then
#              (checked here, after the launcher is gone) the new app is in place, no .previous/.incoming is left,
#              no apply-failed marker, the reopen command was issued for the app's own path, the swapped app's
#              signature verifies and it still starts (--self-test)
#   leftover   (before swap) an Origin.app.incoming from an earlier try that can't be deleted: the swap script stops
#              before moving anything, marks the update failed and reopens the old app untouched
#
# The reopen is stubbed: the swap script calls `open "$app"` through PATH, so a fake `open` first on PATH records
# its arguments instead of opening a window on the runner. The shipped script is not changed for the test.
#
# usage: update_proof.sh <rid> <publish-dir of a build stamped with <version>> <version>
set -euo pipefail

rid="$1"; publish="$2"; version="$3"
repo="$(cd "$(dirname "$0")/../.." && pwd)"
work="$(mktemp -d "${RUNNER_TEMP:-/tmp}/origin-update-proof-XXXXXX")"
summary="${GITHUB_STEP_SUMMARY:-/dev/null}"
zip="OriginLauncher-$rid.zip"   # MacReleaseAssets.ZipNameFor: the asset this arch's launcher asks for
port=8765
failures=0

fail() { echo "::error::$*"; failures=$((failures + 1)); }
row() { echo "| $1 | $2 |" >> "$summary"; }
die() { echo "::error::$*"; exit 1; }

# ---- 1. The installed app (old) and the release's app (new, with a marker file) ----
make_app() { # <dest .app>
  local app="$1"
  mkdir -p "$app/Contents/MacOS" "$app/Contents/Resources"
  ditto "$publish" "$app/Contents/MacOS"
  sed "s/__VERSION__/$version/g" "$repo/src/OriginLauncher.Mac/Packaging/Info.plist" > "$app/Contents/Info.plist"
  printf 'APPL????' > "$app/Contents/PkgInfo"
}
installed="$work/Apps/Origin.app"
make_app "$installed"
codesign --force --deep -s - "$installed"

release_app="$work/release/Origin.app"
make_app "$release_app"
echo "update-proof $(date -u +%FT%TZ)" > "$release_app/Contents/Resources/update-proof.txt"
codesign --force --deep -s - "$release_app"

# ---- 2. The fake release: the zip (made exactly like package.sh makes it), a good and a bad .sha256, three feeds ----
feed="$work/feed"
mkdir -p "$feed/good" "$feed/bad" "$feed/nohash"
(cd "$work/release" && ditto -c -k --keepParent Origin.app "$feed/$zip")
good=$(shasum -a 256 "$feed/$zip" | cut -d' ' -f1)
printf '%s  %s\n' "$good" "$zip" > "$feed/good.sha256"
printf '%s  %s\n' "$(printf 'not the zip' | shasum -a 256 | cut -d' ' -f1)" "$zip" > "$feed/bad.sha256"
base="http://127.0.0.1:$port"
asset() { printf '{"name":"%s","browser_download_url":"%s"}' "$1" "$2"; }
printf '{"tag_name":"launcher-v99.0.0","assets":[%s,%s]}' "$(asset "$zip" "$base/$zip")" "$(asset "$zip.sha256" "$base/good.sha256")" > "$feed/good/latest.json"
printf '{"tag_name":"launcher-v99.0.0","assets":[%s,%s]}' "$(asset "$zip" "$base/$zip")" "$(asset "$zip.sha256" "$base/bad.sha256")" > "$feed/bad/latest.json"
printf '{"tag_name":"launcher-v99.0.0","assets":[%s]}' "$(asset "$zip" "$base/$zip")" > "$feed/nohash/latest.json"

python3 -m http.server "$port" --bind 127.0.0.1 --directory "$feed" > "$work/http.log" 2>&1 &
server=$!
trap 'kill $server 2>/dev/null || true; chmod -R u+w "$work" 2>/dev/null || true' EXIT
for _ in $(seq 1 50); do curl -sf "$base/good/latest.json" > /dev/null && break; sleep 0.2; done
curl -sf "$base/good/latest.json" > /dev/null || die "the fake release server did not start"

echo "## Self-update proof ($rid, fake release on 127.0.0.1)" >> "$summary"
echo "| case | result |" >> "$summary"
echo "|---|---|" >> "$summary"

proof() { # <app> <feed> <expect> <data root>: runs one case, prints its log, returns its exit code
  local app="$1" which="$2" expect="$3" root="$4" code
  echo "::group::$expect ($which feed, from ${app#$work/})"
  set +e
  perl -e 'alarm 300; exec @ARGV' "$app/Contents/MacOS/OriginLauncher" --update-proof "$base/$which/latest.json" --root "$root" --expect "$expect" 2>&1 | tee "$root.log"
  code=${PIPESTATUS[0]}
  set -e
  echo "::endgroup::"
  return "$code"
}

# ---- 3. no-hash, bad-hash (from the installed app; neither may change it) ----
for c in "nohash no-hash" "bad bad-hash"; do
  set -- $c
  if proof "$installed" "$1" "$2" "$work/data-$2" && grep -q "RESULT PASS" "$work/data-$2.log"; then row "$2" PASS
  else row "$2" FAIL; fail "$2 case failed (log above)"; fi
done
[ ! -e "$installed/Contents/Resources/update-proof.txt" ] || fail "a refused update changed the installed app"

# ---- 4. refuse: a translocated copy, then a folder this user can't write to ----
translocated="$work/private/var/folders/xx/T/AppTranslocation/0A1B2C3D/d/Origin.app"
mkdir -p "$(dirname "$translocated")"
ditto "$installed" "$translocated"
if proof "$translocated" good refuse "$work/data-translocated" && grep -q "RESULT PASS" "$work/data-translocated.log"; then row "refuse (translocated)" PASS
else row "refuse (translocated)" FAIL; fail "translocated refusal failed (log above)"; fi

locked="$work/Locked/Origin.app"
mkdir -p "$(dirname "$locked")"
ditto "$installed" "$locked"
chmod a-w "$work/Locked"
if proof "$locked" good refuse "$work/data-locked" && grep -q "RESULT PASS" "$work/data-locked.log"; then row "refuse (folder can't be written)" PASS
else row "refuse (folder can't be written)" FAIL; fail "unwritable-folder refusal failed (log above)"; fi
chmod u+w "$work/Locked"

# The reopen stub: records its arguments in $OPEN_LOG (inherited by the launcher and its swap script).
stub="$work/stub"
mkdir -p "$stub"
printf '#!/bin/sh\nprintf "%%s\\n" "$*" >> "$OPEN_LOG"\n' > "$stub/open"
chmod +x "$stub/open"
check() { if eval "$1"; then echo "PASS $2"; else echo "FAIL $2"; fail "${3:-swap}: $2"; fi; }

# ---- 5. leftover: a .incoming from an earlier try that can't be deleted. The swap must stop before it moves
# anything (no app nested inside the leftover), mark the update failed, and reopen the old app untouched. ----
failures_before=$failures
mkdir -p "$installed.incoming"
echo stuck > "$installed.incoming/stuck"
chmod a-w "$installed.incoming"
open_log="$work/open-leftover.log"
set +e
OPEN_LOG="$open_log" PATH="$stub:$PATH" proof "$installed" good swap "$work/data-leftover"
set -e
grep -q "RESULT PASS" "$work/data-leftover.log" || fail "leftover case: the launcher side failed (log above)"
for _ in $(seq 1 60); do [ -s "$open_log" ] && break; sleep 1; done
check '[ "$(head -n 1 "$open_log" 2>/dev/null)" = "$installed" ]' "the old app is reopened" leftover
check '[ ! -e "$installed/Contents/Resources/update-proof.txt" ] && [ -x "$installed/Contents/MacOS/OriginLauncher" ]' "the old app is in place, untouched" leftover
check '[ -z "$(find "$installed.incoming" -name "*.app" 2>/dev/null)" ]' "nothing was moved into the leftover" leftover
check 'ls "$work/data-leftover/updates"/apply-failed-* > /dev/null 2>&1' "the update is marked failed (not retried in a loop)" leftover
check 'codesign --verify --deep --strict "$installed"' "the old app's signature still verifies" leftover
[ "$failures" = "$failures_before" ] && row "leftover .incoming that can't be deleted: stops, reopens the old app" PASS || row "leftover .incoming that can't be deleted" FAIL
chmod u+w "$installed.incoming"
rm -rf "$installed.incoming"

# ---- 6. swap: the real thing, with `open` stubbed ----
failures_before_swap=$failures
open_log="$work/open.log"
set +e
OPEN_LOG="$open_log" PATH="$stub:$PATH" proof "$installed" good swap "$work/data-swap"
swap_code=$?
set -e
grep -q "RESULT PASS" "$work/data-swap.log" && [ "$swap_code" = "0" ] || fail "swap case: the launcher side failed (exit $swap_code, log above)"

# The script waits for the launcher to be gone, swaps, then reopens: give it up to 60 s.
for _ in $(seq 1 60); do [ -s "$open_log" ] && break; sleep 1; done
check '[ -s "$open_log" ]' "the reopen command was issued"
check '[ "$(head -n 1 "$open_log" 2>/dev/null)" = "$installed" ]' "it reopens the app's own path ($(head -n 1 "$open_log" 2>/dev/null || echo none))"
check '[ -f "$installed/Contents/Resources/update-proof.txt" ]' "the new app is in place"
check '[ ! -e "$installed.previous" ] && [ ! -e "$installed.incoming" ]' "no .previous or .incoming left behind"
check '! ls "$work/data-swap/updates"/apply-failed-* > /dev/null 2>&1' "no apply-failed marker"
check 'codesign --verify --deep --strict "$installed"' "the swapped app's signature verifies"
check '"$installed/Contents/MacOS/OriginLauncher" --self-test > "$work/after-swap-selftest.log" 2>&1' "the swapped app still starts (--self-test)"
grep "^FAIL" "$work/after-swap-selftest.log" 2>/dev/null || true
[ "$failures" = "$failures_before_swap" ] && swap_ok=PASS || swap_ok=FAIL
row "swap (download, hash, unpack, swap, reopen, app starts)" "$swap_ok"

[ "$failures" = "0" ] || die "$failures update-proof check(s) failed"
echo "OK: $rid self-update proven against a fake release: no-hash and bad-hash refused, translocated and unwritable refused, an undeletable leftover stops the swap safely, swap done and reopened."
