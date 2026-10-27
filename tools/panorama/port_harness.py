#!/usr/bin/env python3
"""Regenerate a capture module's OriginDevPanorama.java from 1.21.1's (the one copy to edit).

  port_harness.py <src/mods/versions dir> <module>      e.g. port_harness.py src/mods/versions 26.3

The copies differ from 1.21.1 only inside the "per-version seams" block at the bottom of the file
(world settings, build floor, window size, screenshot call, screen/HUD access) plus two renames from
1.21.11 on (GameRules package, ResourceLocation -> Identifier). Every edit below asserts it matched
exactly once, so a changed 1.21.1 fails loudly here instead of producing a half-ported copy.
Each module's OriginDevShots routes ORIGIN_SHOTS_SET=panorama to it (see 1.21.1's).
"""
import os
import sys

root, mod = sys.argv[1], sys.argv[2]
rel = os.path.join("src", "client", "java", "com", "origin", "client", "client", "OriginDevPanorama.java")
s = open(os.path.join(root, "1.21.1", rel), encoding="utf-8").read()


def rep(a, b):
    global s
    assert s.count(a) == 1, (mod, a)
    s = s.replace(a, b)


era = mod
ge_1214 = era not in ("1.21.1",)
ge_1216 = era in ("1.21.6", "1.21.8", "1.21.10", "1.21.11", "26.1", "26.2", "26.3")
ge_12110 = era in ("1.21.10", "1.21.11", "26.1", "26.2", "26.3")
ge_12111 = era in ("1.21.11", "26.1", "26.2", "26.3")
is_26 = era.startswith("26.")

if ge_1214:
    rep("return level.getMinBuildHeight();", "return level.getMinY();")
if ge_1214 and not is_26:
    rep("new GameRules(),", "new GameRules(WorldDataConfiguration.DEFAULT.enabledFeatures()),")
if ge_12111:
    rep("import net.minecraft.world.level.GameRules;", "import net.minecraft.world.level.gamerules.GameRules;")
    rep("import net.minecraft.resources.ResourceLocation;", "import net.minecraft.resources.Identifier;")
    rep("ResourceLocation.parse(dim)", "Identifier.parse(dim)")
if is_26:
    # 26.x: LevelSettings carries a DifficultySettings record and no game rules (the harness sets those by command).
    rep("""		return new LevelSettings(LEVEL, GameType.CREATIVE, false, Difficulty.NORMAL, true, new GameRules(),
				WorldDataConfiguration.DEFAULT);""", """		return new LevelSettings(LEVEL, GameType.CREATIVE, new LevelSettings.DifficultySettings(Difficulty.NORMAL, false, false),
				true, WorldDataConfiguration.DEFAULT);""")
    rep("import net.minecraft.world.level.gamerules.GameRules;\n", "")
if ge_1216:
    rep("""		Screenshot.grab(new File(dir), name, mc.getMainRenderTarget(), c -> {
		});""", """		Screenshot.grab(new File(dir), name, mc.getMainRenderTarget(), 1, c -> {
		});""")
if ge_12110:
    rep("""		mc.getWindow().setWindowed(FACE, FACE);
		mc.resizeDisplay();""", """		org.lwjgl.glfw.GLFW.glfwSetWindowSize(mc.getWindow().handle(), FACE, FACE);
		mc.resizeDisplay();""")
if era == "26.1":
    rep("""		org.lwjgl.glfw.GLFW.glfwSetWindowSize(mc.getWindow().handle(), FACE, FACE);
		mc.resizeDisplay();""", """		org.lwjgl.glfw.GLFW.glfwSetWindowSize(mc.getWindow().handle(), FACE, FACE);
		mc.resizeGui();""")
if era in ("26.2", "26.3"):
    # 26.2+: screens live on mc.gui, the HUD hides through Hud.toggle, the main target is on the GameRenderer.
    rep("""		org.lwjgl.glfw.GLFW.glfwSetWindowSize(mc.getWindow().handle(), FACE, FACE);
		mc.resizeDisplay();""", ("""		org.lwjgl.sdl.SDLVideo.SDL_SetWindowSize(mc.getWindow().handle(), FACE, FACE);
		mc.framebufferSizeChanged();""" if era == "26.3" else """		org.lwjgl.glfw.GLFW.glfwSetWindowSize(mc.getWindow().handle(), FACE, FACE);
		mc.framebufferSizeChanged();"""))
    rep("""		mc.options.hideGui = true;""", """		if (!mc.gui.hud.isHidden()) {
			mc.gui.hud.toggle();
		}""")
    rep("Screenshot.grab(new File(dir), name, mc.getMainRenderTarget(), 1,", "Screenshot.grab(new File(dir), name, mc.gameRenderer.mainRenderTarget(), 1,")
    rep("""		return mc.screen;""", """		return mc.gui.screen();""")
    rep("""		if (mc.screen instanceof PauseScreen) {
			mc.setScreen(null);""", """		if (mc.gui.screen() instanceof PauseScreen) {
			mc.gui.setScreen(null);""")
open(os.path.join(root, mod, rel), "w", encoding="utf-8", newline="").write(s)
print("ported", mod)
