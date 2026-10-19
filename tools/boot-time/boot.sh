#!/usr/bin/env bash
# One headless boot of a module's dev client with the boot timeline on
# (OriginBootLog: -PbootLog -PbootQuit), for .github/workflows/boot-time.yml.
#   boot.sh <checkout> <module> <log> [extra gradle args...]
# The game closes itself ~4 s after its title screen is interactive; a boot that
# never gets there is killed after 10 minutes. Same headless GL setup as
# dev-shots.yml (Xvfb + llvmpipe, GL 4.6 spoofed for Sodium).
set -u
dir="$1"; module="$2"; log="$3"; shift 3
cd "$dir/src/mods/versions/$module" || exit 1
chmod +x gradlew
mkdir -p run
# A returning player's boot: no one-time accessibility screen, no auto-pause.
[ -f run/options.txt ] || printf 'onboardAccessibility:false\npauseOnLostFocus:false\n' > run/options.txt
# BOOT_MFX: ModernFix option overrides for this variant, comma-separated
# key=value (written to run/config/modernfix-mixins.properties; empty = defaults).
mkdir -p run/config
: > run/config/modernfix-mixins.properties
if [ -n "${BOOT_MFX:-}" ]; then
  printf '%s\n' "$BOOT_MFX" | tr ',' '\n' > run/config/modernfix-mixins.properties
fi
# BOOT_JVM: extra JVM args for this variant, comma-separated.
extra=()
[ -n "${BOOT_JVM:-}" ] && extra+=("-PbootJvmArgs=$BOOT_JVM")
export LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe MESA_GL_VERSION_OVERRIDE=4.6 MESA_GLSL_VERSION_OVERRIDE=460
export SDL_VIDEO_X11_NODIRECTCOLOR=1 SDL_VIDEO_FORCE_EGL=1
setsid xvfb-run -a -s "-screen 0 1920x1080x24" ./gradlew runClient --no-daemon -PbootLog -PbootQuit "${extra[@]}" "$@" > "$log" 2>&1 &
pid=$!
for i in $(seq 1 600); do kill -0 $pid 2>/dev/null || break; sleep 1; done
kill -- -$pid 2>/dev/null || true
sleep 3
kill -9 -- -$pid 2>/dev/null || true
grep -F "[OriginBoot] summary" "$log" || { echo "no boot summary in $log"; tail -n 40 "$log"; }
