#!/usr/bin/env bash
# Mac boot probe: watches the game process from outside while a boot runs. The game's own log can't
# prove its architecture or JVM flags, this can. Writes into <out>:
#   proc.txt     the game's full command line (is -XstartOnFirstThread there?) and, once it has loaded
#                them, its native libraries (libjvm slices; Rosetta's oah libraries = it is translated)
#   screen-*.png the real screen at about 30, 60, 120 and 240 seconds (the window, if macOS will show one)
# usage: watch.sh <out dir> &   (killed by the workflow when the boot ends; never fails anything)
out="$1"
mkdir -p "$out"
start=$(date +%s)
seen=""
libs=0
shots=" 30 60 120 240 "
while :; do
  now=$(( $(date +%s) - start ))
  pid=$(ps -axww -o pid=,command= | grep -E 'devlaunchinjector|KnotClient|launchwrapper\.Launch|GradleStart' | grep -v grep | grep -v 'sh -c' | awk '{print $1}' | head -n 1)
  if [ -n "$pid" ]; then
    if [ "$pid" != "$seen" ]; then
      seen="$pid"
      libs=0
      { echo "=== game process $pid at ${now}s"; ps -ww -o command= -p "$pid" | cut -c1-1500; } >> "$out/proc.txt"
    fi
    if [ "$libs" -lt 2 ] && [ "$now" -gt 20 ]; then
      lsof -p "$pid" 2>/dev/null | grep -E 'libjvm|oah|Rosetta|liblwjgl|libglfw|libjinput' | awk '{print $NF}' | sort -u > "$out/libs.tmp"
      if [ -s "$out/libs.tmp" ]; then
        libs=$((libs + 1))
        { echo "=== native libraries of $pid at ${now}s"
          while read -r f; do
            printf '%s  [%s]\n' "$f" "$(lipo -archs "$f" 2>/dev/null || echo '?')"
          done < "$out/libs.tmp"; } >> "$out/proc.txt"
      fi
    fi
  fi
  for s in $shots; do
    if [ "$now" -ge "$s" ]; then
      screencapture -x "$out/screen-${s}s.png" 2>> "$out/screencapture.err" || true
      shots="${shots/ $s / }"
    fi
  done
  sleep 3
done
