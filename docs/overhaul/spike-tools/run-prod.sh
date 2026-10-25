#!/bin/bash
# usage: spike-prod.sh <label> <mods-subdir-name or -> ; runs 1.21.1 prodClient (built jar WITH nested stack) + top-level jars in spike-mods/<dir>
S="C:/Users/Will/AppData/Local/Temp/claude/C--Users-Will-Documents-Minecraft-Mods-Origin-Client/f0dcc439-1e1c-4429-aca1-1bb3e25913eb/scratchpad"
LABEL=$1; DIR=$2
MOD="$S/wt-spike-sodium/src/mods/versions/1.21.1"
OUT="$S/spike-out/prod-1.21.1-$LABEL"
python -I -c "import shutil,sys; shutil.rmtree(sys.argv[1],ignore_errors=True)" "$OUT"
mkdir -p "$OUT/shots"
cd "$MOD" || exit 2
python -I -c "import shutil,sys; shutil.rmtree(sys.argv[1],ignore_errors=True)" run-prod
mkdir -p run-prod/config
printf 'onboardAccessibility:false\npauseOnLostFocus:false\n' > run-prod/options.txt
sp="run-prod/shaderpacks/OriginCiPack/shaders"; mkdir -p "$sp"
printf '#version 120\nvoid main() { gl_Position = ftransform(); }\n' > "$sp/shadow.vsh"
printf '#version 120\nvoid main() { gl_FragData[0] = vec4(1.0); }\n' > "$sp/shadow.fsh"
printf '#version 120\nvarying vec2 uv;\nvoid main() { gl_Position = ftransform(); uv = gl_MultiTexCoord0.xy; }\n' > "$sp/gbuffers_basic.vsh"
printf '#version 120\nvoid main() { gl_FragData[0] = vec4(1.0); }\n' > "$sp/gbuffers_basic.fsh"
printf '#version 120\nvarying vec2 uv;\nvarying vec4 col;\nvoid main() { gl_Position = ftransform(); uv = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy; col = gl_Color; }\n' > "$sp/gbuffers_textured.vsh"
printf '#version 120\nuniform sampler2D texture;\nvarying vec2 uv;\nvarying vec4 col;\nvoid main() { vec4 c = texture2D(texture, uv) * col; if (c.a < 0.1) discard; gl_FragData[0] = c; }\n' > "$sp/gbuffers_textured.fsh"
printf 'shadowMapResolution=1024\nshadowDistance=96\nshadow.enabled=true\n' > "$sp/shaders.properties"
printf 'enableShaders=true\nshaderPack=OriginCiPack\n' > run-prod/config/iris.properties
export ORIGIN_SHOTS="$OUT/shots" ORIGIN_SHOTS_SET=world ORIGIN_SMOKE=1
export JAVA_TOOL_OPTIONS="-Dmixin.debug.verbose=true"
GA="-PwithFabricApi"; [ "$DIR" != "-" ] && GA="$GA -PextraMods=$S/spike-mods/$DIR"
echo "[spike-prod] $LABEL dir=$DIR" > "$OUT/run.log"
timeout 900 ./gradlew.bat prodClient --no-daemon $GA >> "$OUT/run.log" 2>&1
echo "[spike-prod] exit=$?" >> "$OUT/run.log"
cp run-prod/logs/latest.log "$OUT/latest.log" 2>/dev/null
ls "$OUT/shots/screenshots" 2>/dev/null | wc -l > "$OUT/shots.txt"
