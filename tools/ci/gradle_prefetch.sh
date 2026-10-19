#!/usr/bin/env bash
# Download a module's Gradle dependencies + Minecraft assets with a bounded retry,
# BEFORE the real run (dev-shots.yml, boot-smoke.yml).
#
# Why: Maven Central answering 429, or Loom's :downloadAssets timing out, failed
# CI jobs at random -- and once let a dev-shots job go green with 0 PNGs. This
# step absorbs those: it retries only on a network/download signature (with
# backoff + jitter, since ~20 matrix jobs hit Maven at once) and fails with a
# clear "network, not Origin" message if the network never recovers. A failure
# for any OTHER reason is only a warning here -- the real run that follows is
# the judge of the build itself.
#
# usage: tools/ci/gradle_prefetch.sh <module-dir>
set -u
here="$(cd "$(dirname "$0")" && pwd)"
cd "$1" || exit 2
chmod +x gradlew
log="$(mktemp)"
# The network signatures live in ONE file shared with smoke.py.
net="$(grep -vE '^[[:space:]]*(#|$)' "$here/network_errors.txt" | tr -d '\r' | paste -sd'|' -)"
[ -n "$net" ] || { echo "::error::tools/ci/network_errors.txt is empty"; exit 2; }
attempts=4
for i in $(seq 1 $attempts); do
  start=$(date +%s)
  if ./gradlew --no-daemon -I "$here/prefetch.gradle" originPrefetch downloadAssets >"$log" 2>&1; then
    grep -E "^originPrefetch:" "$log" | head -n 20
    echo "prefetch: OK on attempt $i ($(( $(date +%s) - start ))s)"
    exit 0
  fi
  echo "--- prefetch attempt $i failed; last 25 lines ---"
  tail -n 25 "$log"
  if ! grep -qiE "$net" "$log"; then
    echo "::warning title=prefetch::$(basename "$PWD"): prefetch failed for a non-network reason; continuing (the real run judges the build)"
    exit 0
  fi
  grep -m3 -iE "$net" "$log" | cut -c1-200
  [ "$i" -lt "$attempts" ] || break
  delay=$(( 30 * (2 ** (i - 1)) + RANDOM % 30 ))
  echo "prefetch: network/download failure (above); retry $((i + 1))/$attempts in ${delay}s"
  sleep "$delay"
done
echo "::error title=prefetch::$(basename "$PWD"): dependency/asset downloads still failing after $attempts attempts (Maven 429 / timeouts) -- a network flake, not an Origin bug; re-run the job"
exit 1
