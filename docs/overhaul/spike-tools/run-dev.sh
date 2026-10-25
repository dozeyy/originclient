#!/bin/bash
# usage: spike-run.sh <mc> <label> <sodium-jar> <iris-jar> [shaderpack=1] [extraModsDir]
S="C:/Users/Will/AppData/Local/Temp/claude/C--Users-Will-Documents-Minecraft-Mods-Origin-Client/f0dcc439-1e1c-4429-aca1-1bb3e25913eb/scratchpad"
MC=$1; LABEL=$2; SJ=$3; IJ=$4; SP=${5:-1}; EXTRA=$6
MOD="$S/wt-spike-sodium/src/mods/versions/$MC"
OUT="$S/spike-out/$MC-$LABEL"
python -I -c "import shutil,sys; shutil.rmtree(sys.argv[1],ignore_errors=True)" "$OUT"
mkdir -p "$OUT/shots"
cd "$MOD" || exit 2
python -I -c "import shutil,sys; shutil.rmtree(sys.argv[1],ignore_errors=True)" run
mkdir -p run/config
printf 'onboardAccessibility:false\npauseOnLostFocus:false\n' > run/options.txt
if [ "$MC" = "1.21.11" ]; then
  mkdir -p libs
  cp "$S/spike-jars/$SJ" "libs/sodium-fabric-0.8.12+mc1.21.11.jar"
  cp "$S/spike-jars/$IJ" "libs/iris-fabric-1.10.7+mc1.21.11.jar"
fi
if [ "$SP" = "1" ]; then
  sp="run/shaderpacks/OriginCiPack/shaders"; mkdir -p "$sp"
  printf '#version 120\nvoid main() { gl_Position = ftransform(); }\n' > "$sp/shadow.vsh"
  printf '#version 120\nvoid main() { gl_FragData[0] = vec4(1.0); }\n' > "$sp/shadow.fsh"
  printf '#version 120\nvarying vec2 uv;\nvoid main() { gl_Position = ftransform(); uv = gl_MultiTexCoord0.xy; }\n' > "$sp/gbuffers_basic.vsh"
  printf '#version 120\nvoid main() { gl_FragData[0] = vec4(1.0); }\n' > "$sp/gbuffers_basic.fsh"
  printf '#version 120\nvarying vec2 uv;\nvarying vec4 col;\nvoid main() { gl_Position = ftransform(); uv = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy; col = gl_Color; }\n' > "$sp/gbuffers_textured.vsh"
  printf '#version 120\nuniform sampler2D texture;\nvarying vec2 uv;\nvarying vec4 col;\nvoid main() { vec4 c = texture2D(texture, uv) * col; if (c.a < 0.1) discard; gl_FragData[0] = c; }\n' > "$sp/gbuffers_textured.fsh"
  printf 'shadowMapResolution=1024\nshadowDistance=96\nshadow.enabled=true\n' > "$sp/shaders.properties"
  printf 'enableShaders=true\nshaderPack=OriginCiPack\n' > run/config/iris.properties
fi
export ORIGIN_SHOTS="$OUT/shots" ORIGIN_SHOTS_SET=world ORIGIN_SMOKE=1
export JAVA_TOOL_OPTIONS="-Dmixin.debug.verbose=true"
GA=""; [ -n "$EXTRA" ] && GA="-PextraMods=$EXTRA"
if [ "$MC" = "1.21.1" ] && [ "$SJ" != "-" ]; then GA="$GA -PaltSodium=$S/spike-jars/$SJ -PaltIris=$S/spike-jars/$IJ"; [ -n "$GLSL" ] && GA="$GA -Pglsl=$GLSL"; fi
echo "[spike] $MC $LABEL sodium=$SJ iris=$IJ" > "$OUT/run.log"
timeout 900 ./gradlew.bat runClient --no-daemon $GA >> "$OUT/run.log" 2>&1
echo "[spike] exit=$?" >> "$OUT/run.log"
cp run/logs/latest.log "$OUT/latest.log" 2>/dev/null
ls "$OUT/shots" "$OUT/shots/screenshots" 2>/dev/null | head -50 > "$OUT/shots.txt"
