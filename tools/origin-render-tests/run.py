"""Run Origin Render's GPU culler tests on a real OpenGL driver, twice.

    python tools/origin-render-tests/run.py [--lwjgl DIR]

TerrainCuller + TerrainShaders (1.21.1 module, render/terrain/) are plain LWJGL,
so they compile and run here without Minecraft. Three passes:
  45  - a 4.5 core context;
  mc  - the context Minecraft really gets on AMD/NVIDIA (3.2 core, where the
        4.3 features are extensions; Mesa is capped to report GL 3.2);
  old - GLSL also capped below 4.30: supported() must say no (Mesa only).
Needs a JDK 21+, a GL 4.3-class driver (Linux sandbox: Mesa llvmpipe under
xvfb-run, used automatically when present; Windows: the desktop driver) and LWJGL 3.3.3 + JOML
1.10.5 (Minecraft 1.21.1's versions) - downloaded from Maven Central into
~/.cache/origin-render-tests when --lwjgl isn't given.
"""
import argparse
import os
import platform
import shutil
import subprocess
import sys
import tempfile
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC = os.path.join(ROOT, "src/mods/versions/1.21.1/src/client/java/com/origin/client/client/render/terrain")
HERE = os.path.dirname(os.path.abspath(__file__))
MAVEN = "https://repo1.maven.org/maven2"
NATIVES = {"Linux": "natives-linux", "Windows": "natives-windows", "Darwin": "natives-macos"}[platform.system()]
JARS = [f"org/lwjgl/{a}/3.3.3/{a}-3.3.3{c}.jar"
        for a in ("lwjgl", "lwjgl-opengl", "lwjgl-glfw") for c in ("", "-" + NATIVES)]
JARS.append("org/joml/joml/1.10.5/joml-1.10.5.jar")


def fetch(dest):
    os.makedirs(dest, exist_ok=True)
    for j in JARS:
        out = os.path.join(dest, os.path.basename(j))
        if not os.path.exists(out):
            urllib.request.urlretrieve(f"{MAVEN}/{j}", out)
    return dest


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--lwjgl")
    a = ap.parse_args()
    lib = a.lwjgl or fetch(os.path.expanduser("~/.cache/origin-render-tests"))
    cp = os.pathsep.join(os.path.join(lib, f) for f in sorted(os.listdir(lib)) if f.endswith(".jar"))
    out = tempfile.mkdtemp(prefix="origin-render-")
    srcs = [os.path.join(SRC, f) for f in sorted(os.listdir(SRC)) if f.startswith("Terrain") and f.endswith(".java")]
    r = subprocess.run(["javac", "-Xlint:all", "-d", out, "-cp", cp] + srcs + [os.path.join(HERE, "OriginRenderTests.java")])
    if r.returncode:
        sys.exit("compile failed")
    worst = 0
    for mode in ("45", "mc", "old"):
        print(f"== context: {mode}", flush=True)
        cmd = ["java", "-cp", out + os.pathsep + cp, "OriginRenderTests", mode]
        if platform.system() == "Linux" and not os.environ.get("DISPLAY") and shutil.which("xvfb-run"):
            cmd = ["xvfb-run", "-a"] + cmd
        env = dict(os.environ)
        if mode != "45":
            # Mesa only: report exactly what Minecraft's context reports on AMD/NVIDIA
            env["MESA_GL_VERSION_OVERRIDE"] = "3.2"
        if mode == "old":
            env["MESA_GLSL_VERSION_OVERRIDE"] = "150"
        rc = subprocess.run(cmd, env=env).returncode
        print(f"== {mode}: exit {rc}", flush=True)
        worst = max(worst, rc)
    sys.exit(worst)


if __name__ == "__main__":
    main()
