#!/usr/bin/env bash
# One headless boot of a module's client with the boot timeline on
# (OriginBootLog: -PbootLog -PbootQuit), for .github/workflows/boot-time.yml.
# BOOT_TASK=prodClient boots the built jar in a production (intermediary) Fabric
# game with Fabric API from maven instead of the Mojang-named dev game (runClient,
# the default); BOOT_GRADLE adds gradle args (comma-separated, e.g. -PnoBootArgs).
#   boot.sh <checkout> <module> <log> [extra gradle args...]
# The game closes itself ~4 s after its title screen is interactive; a boot that
# never gets there is killed after 10 minutes. Same headless GL setup as
# dev-shots.yml (Xvfb + llvmpipe, GL 4.6 spoofed for Sodium).
set -u
dir="$1"; module="$2"; log="$3"; shift 3
cd "$dir/src/mods/versions/$module" || exit 1
chmod +x gradlew
task="${BOOT_TASK:-runClient}"
rundir=run
taskargs=()
if [ "$task" = "prodClient" ]; then
  rundir=run-prod
  taskargs+=("-PwithFabricApi")
elif [ "$task" != "runClient" ]; then
  echo "unknown BOOT_TASK $task"; exit 1
fi
if [ -n "${BOOT_GRADLE:-}" ]; then
  IFS=',' read -r -a more <<< "$BOOT_GRADLE"
  taskargs+=("${more[@]}")
fi
mkdir -p "$rundir"
# A returning player's boot: no one-time accessibility screen, no auto-pause.
[ -f "$rundir/options.txt" ] || printf 'onboardAccessibility:false\npauseOnLostFocus:false\n' > "$rundir/options.txt"
# BOOT_MFX: ModernFix option overrides for this variant, comma-separated
# key=value (written to <rundir>/config/modernfix-mixins.properties; empty = defaults).
mkdir -p "$rundir/config"
: > "$rundir/config/modernfix-mixins.properties"
if [ -n "${BOOT_MFX:-}" ]; then
  printf '%s\n' "$BOOT_MFX" | tr ',' '\n' > "$rundir/config/modernfix-mixins.properties"
fi
# BOOT_JVM: extra JVM args for this variant, comma-separated.
extra=()
[ -n "${BOOT_JVM:-}" ] && extra+=("-PbootJvmArgs=$BOOT_JVM")
export LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe MESA_GL_VERSION_OVERRIDE=4.6 MESA_GLSL_VERSION_OVERRIDE=460
export SDL_VIDEO_X11_NODIRECTCOLOR=1 SDL_VIDEO_FORCE_EGL=1
setsid xvfb-run -a -s "-screen 0 1920x1080x24" ./gradlew "$task" --no-daemon -PbootLog -PbootQuit "${taskargs[@]}" "${extra[@]}" "$@" > "$log" 2>&1 &
pid=$!
for i in $(seq 1 600); do kill -0 $pid 2>/dev/null || break; sleep 1; done
kill -- -$pid 2>/dev/null || true
sleep 3
kill -9 -- -$pid 2>/dev/null || true
grep -F "[OriginBoot] summary" "$log" || { echo "no boot summary in $log"; tail -n 40 "$log"; }
