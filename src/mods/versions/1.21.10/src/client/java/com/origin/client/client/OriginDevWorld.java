package com.origin.client.client;

import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.resources.language.I18n;
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Set;

/**
 * DEV-ONLY in-world captures for dev-shots.yml (ORIGIN_SHOTS_SET=world): the
 * design set never enters a world, so world-render bugs (Color Saturation's
 * black world / white vignette, 2026-10-12) were invisible to it. This walks
 * the game's own screens -- Select World -> "Create New World" -> "Create New
 * World" -- by pressing the buttons by their translated label (the same keys
 * on every version), waits for the world to draw, then captures it with Color
 * Saturation off and on, then your player in third person wearing head, face
 * and back items from every side (with and without the skin's outer layer:
 * the cosmetics fit over it), the pause menu, the Cosmetics window and a demo
 * party (Party HUD, markers, a ping), and quits.
 * With a shader pack active it first measures the shader speed-ups on a fixed
 * test scene (shadowLimitStep, smartTerrainStep).
 *
 * PER-VERSION DELTA (1.21.10), the same swaps OriginDevShots makes:
 *   * the window is sized with GLFW.glfwSetWindowSize on Window.handle();
 *   * AbstractButton.onPress takes the triggering input (1.21.9): an Enter
 *     KeyEvent, as this module's PauseScreenMixin synthesises;
 *   * Screenshot.grab is the dated-file form, written on the IO thread, so each
 *     capture waits for its new PNG and renames it to the shot's name before
 *     the next step runs.
 */
final class OriginDevWorld {
	private OriginDevWorld() {
	}

	private static int phase = 0;
	private static long nextAt = 0;
	private static long loadedAt = 0;
	// the capture waiting for its PNG: its name, the PNGs that existed before it, tries
	private static String pending = null;
	private static Set<String> before = new HashSet<>();
	private static int renameTries = 0;

	static void tick(Minecraft mc, String dir) {
		long now = System.currentTimeMillis();
		if (terrainSpin && mc.player != null) {
			// Smart Terrain Updates "moving camera" window: turn 3 degrees every client tick.
			mc.player.setYRot(mc.player.getYRot() + 3f);
		}
		if (hrSlide && mc.player != null) {
			// Half-Rate Shadows "moving camera" window: slide sideways 0.1 block every client tick, height fixed.
			mc.player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
			mc.player.setPos(mc.player.getX() + hrDx, hrY, mc.player.getZ() + hrDz);
		}
		if (now < nextAt) {
			return;
		}
		nextAt = now + 1000;
		try {
			if (pending != null && !finishGrab(dir)) {
				nextAt = now + 250;   // the PNG isn't written yet
				return;
			}
			if (phase == 0) {
				// Title is up (OriginDevShots waited for it): open Select World.
				GLFW.glfwSetWindowSize(mc.getWindow().handle(), 1920, 1080);
				mc.resizeDisplay();
				mc.setScreen(new SelectWorldScreen(new TitleScreen()));
				phase = 1;
				return;
			}
			if (phase == 1) {
				if (mc.level == null) {
					// Select World / Create World: press "Create New World" wherever it is.
					if (mc.screen != null && press(mc, I18n.get("selectWorld.create"))) {
						log("[dev-shots] pressed Create New World on " + mc.screen.getClass().getSimpleName());
					}
					return;
				}
				if (mc.screen instanceof PauseScreen) {
					mc.setScreen(null);   // lost focus under Xvfb
				}
				if (mc.player == null || mc.screen != null) {
					return;
				}
				if (loadedAt == 0) {
					loadedAt = now;
					return;
				}
				if (now - loadedAt < 20000) {
					return;   // let the chunks around spawn build and draw
				}
				grab(mc, dir, "world_off_1920x1080.png");
				Mods.setOn("colorsaturation", true);
				Mods.set("colorsaturation", "saturation", 1.8);
				phase = 2;
				nextAt = now + 2500;
				return;
			}
			if (phase == 2) {
				if (mc.screen instanceof PauseScreen) {
					mc.setScreen(null);
				}
				grab(mc, dir, "world_sat_1920x1080.png");
				Mods.set("colorsaturation", "saturation", 1.0);
				Mods.set("colorsaturation", "brightness", 1.4);
				phase = 3;
				nextAt = now + 2500;
				return;
			}
			if (phase == 3) {
				grab(mc, dir, "world_bright_1920x1080.png");
				Mods.setOn("colorsaturation", false);
				// With a shader pack on (dev-shots shaderpack=true), measure the shader speed-ups first.
				phase = com.origin.client.client.shaders.IrisBridge.currentPack() != null ? 40 : 30;
				return;
			}
			if (phase >= 40 && phase < 70) {
				if (phase < 45 ? shadowLimitStep(mc, dir) : smartTerrainStep(mc, dir)) {
					return;
				}
				phase = 30;
				return;
			}
			if (phase == 30) {
				if (fitStep(mc, dir)) {
					return;
				}
				// The pause menu (its Cosmetics button sits among vanilla's buttons).
				mc.setScreen(new PauseScreen(true));
				phase = 4;
				nextAt = now + 1500;
				return;
			}
			if (phase == 4) {
				grab(mc, dir, "pause_1920x1080.png");
				// The Cosmetics window over the world: its preview is the real player here.
				mc.setScreen(new com.origin.client.client.gui.CosmeticsScreen(null));
				phase = 5;
				nextAt = now + 2500;
				return;
			}
			if (phase == 5) {
				grab(mc, dir, "cosmetics_world_1920x1080.png");
				phase = 6;
				return;
			}
			if (phase == 6) {
				// Friends & Party: a demo party standing in front of the camera, with a ping
				mc.setScreen(null);
				com.origin.client.client.social.SocialDemo.install(true);
				phase = 7;
				nextAt = now + 2500;
				return;
			}
			if (phase == 7) {
				if (mc.screen instanceof PauseScreen) {
					mc.setScreen(null);
				}
				grab(mc, dir, "party_world_1920x1080.png");
				phase = 8;
				return;
			}
			if (phase == 8) {
				com.origin.client.OriginClient.LOGGER.info("[dev-shots] done — world set in {}", dir);
				phase = 9;
				mc.stop();
			}
		} catch (Throwable t) {
			com.origin.client.OriginClient.LOGGER.warn("[dev-shots] world step {} failed", phase, t);
			phase = 9;
			mc.stop();
		}
	}

	// ---- In-world smoke step (boot-smoke + the dev-shots design set) ----
	// The screen sets never enter a world, so a mod that boots to the title and then breaks the moment a
	// world loads (HUD, mod menu over a world, Save and Quit) passed every gate. This is the one in-world
	// route both gates share, turned on per module by "inworld": true in src/mods/live-modules.json:
	//   boot-smoke.yml -> smoke.py --inworld DIR -> env ORIGIN_SMOKE_WORLD=DIR (OriginClientMod.onEndTick)
	//   dev-shots.yml design set -> env ORIGIN_INWORLD=1 (OriginDevShots.finish hands over to it)
	// Steps, each with a deadline: make (or reopen) a flat test world straight through WorldOpenFlows (no
	// button pressing: the world set's label route stays its own), wait until Origin's HUD has really drawn
	// (HudElements.passes climbing), shoot it, open the Mod Menu over the world, shoot it, Save and Quit
	// back to the title. One verdict line, gated by smoke.py and check_shots.py:
	//   [origin-smoke] in-world: PASS      or      [origin-smoke] in-world: FAIL <reason>
	// A throw or a missed deadline is a FAIL, never a hang. It always ends with onEnd + mc.stop().
	// Ported from 1.21.1 (cf4c658da).
	// PER-VERSION (1.21.10, javap): GameRules is still net.minecraft.world.level.GameRules (1.21.11 moved it to
	// world.level.gamerules) with a (FeatureFlagSet) constructor; the registry lookup is
	// HolderLookup.Provider.lookupOrThrow(..).getOrThrow(..); Save and Quit is
	// Minecraft.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE), exactly what PauseScreen's button calls.
	static final String SMOKE_WORLD = "origin-smoke";
	private static final long IW_LOAD_MS = 240_000, IW_QUIT_MS = 90_000;
	private static int iw = 0;
	private static long iwAt = 0, iwFrom = 0, iwHudFrom = -1, iwHudAt = 0;
	private static Boolean iwLimitUnfocused;
	private static boolean iwDone;

	static void inWorldTick(Minecraft mc, String dir, Runnable onEnd) {
		if (iwDone) {
			return;
		}
		long now = System.currentTimeMillis();
		if (now < iwAt) {
			return;
		}
		try {
			switch (iw) {
				case 0 -> {
					log("[origin-smoke] in-world: starting (" + (mc.getLevelSource().levelExists(SMOKE_WORLD) ? "reopening" : "creating")
							+ " flat world '" + SMOKE_WORLD + "')");
					// Xvfb has no window manager, so the window is never "active": with Limit Unfocused FPS on,
					// Origin's HUD skips itself (FrameWatch.idle). Off for the step, restored at the end.
					iwLimitUnfocused = Mods.bool(Mods.PERFORMANCE_ID, "limitUnfocusedFps");
					Mods.set(Mods.PERFORMANCE_ID, "limitUnfocusedFps", false);
					if (mc.getWindow().getWidth() < 1280) {
						mc.getWindow().setWindowed(1280, 720);   // boot-smoke's 854x480 default is too small to read
					}
					var flows = mc.createWorldOpenFlows();
					if (mc.getLevelSource().levelExists(SMOKE_WORLD)) {
						flows.openWorld(SMOKE_WORLD, () -> mc.setScreen(new TitleScreen()));
					} else {
						var settings = new net.minecraft.world.level.LevelSettings("Origin Smoke",
								net.minecraft.world.level.GameType.SURVIVAL, false, net.minecraft.world.Difficulty.PEACEFUL, true,
								new net.minecraft.world.level.GameRules(net.minecraft.world.level.WorldDataConfiguration.DEFAULT.enabledFeatures()), net.minecraft.world.level.WorldDataConfiguration.DEFAULT);
						var options = new net.minecraft.world.level.levelgen.WorldOptions(20261027L, false, false);
						flows.createFreshLevel(SMOKE_WORLD, settings, options, ra -> ra
								.lookupOrThrow(net.minecraft.core.registries.Registries.WORLD_PRESET)
								.getOrThrow(net.minecraft.world.level.levelgen.presets.WorldPresets.FLAT)
								.value().createWorldDimensions(), new TitleScreen());
					}
					iw = 1;
					iwFrom = now;
				}
				case 1 -> {
					if (now - iwFrom > IW_LOAD_MS) {
						inWorldEnd(mc, false, "world not playable after " + IW_LOAD_MS / 1000 + " s (screen: "
								+ (mc.screen == null ? "none" : mc.screen.getClass().getSimpleName()) + ", player: "
								+ (mc.player != null) + ", HUD passes: " + com.origin.client.client.hud.HudElements.passes + ")", onEnd);
						return;
					}
					if (mc.screen instanceof PauseScreen) {
						mc.setScreen(null);   // lost focus under Xvfb
					}
					if (mc.level == null || mc.player == null || mc.screen != null) {
						iwAt = now + 250;
						return;
					}
					if (iwHudFrom < 0) {
						iwHudFrom = com.origin.client.client.hud.HudElements.passes;
						iwHudAt = now;
						log("[origin-smoke] in-world: world loaded after " + (now - iwFrom) / 1000 + " s");
					}
					long passes = com.origin.client.client.hud.HudElements.passes - iwHudFrom;
					if (passes < 60 || now - iwHudAt < 8000) {
						iwAt = now + 250;   // 60 real HUD frames, and 8 s for the spawn chunks to draw
						return;
					}
					grab(mc, dir, "inworld_hud_" + mc.getWindow().getWidth() + "x" + mc.getWindow().getHeight() + ".png");
					log("[origin-smoke] in-world: HUD up (" + passes + " HUD passes)");
					iw = 2;
					iwAt = now + 1500;
				}
				case 2 -> {
					mc.setScreen(new com.origin.client.client.gui.OriginModMenuScreen());
					iw = 3;
					iwAt = now + 2500;
				}
				case 3 -> {
					if (!(mc.screen instanceof com.origin.client.client.gui.OriginModMenuScreen)) {
						inWorldEnd(mc, false, "mod menu did not open over the world (screen: "
								+ (mc.screen == null ? "none" : mc.screen.getClass().getSimpleName()) + ")", onEnd);
						return;
					}
					grab(mc, dir, "inworld_modmenu_" + mc.getWindow().getWidth() + "x" + mc.getWindow().getHeight() + ".png");
					log("[origin-smoke] in-world: mod menu open");
					iw = 4;
					iwAt = now + 1500;
				}
				case 4 -> {
					// Vanilla's Save and Quit: the call PauseScreen's disconnect button makes (it saves the
					// integrated server behind a saving screen, then opens the title).
					mc.setScreen(null);
					mc.disconnectFromWorld(net.minecraft.client.multiplayer.ClientLevel.DEFAULT_QUIT_MESSAGE);
					iw = 5;
					iwFrom = now;
				}
				case 5 -> {
					if (mc.level == null && mc.getSingleplayerServer() == null && mc.screen instanceof TitleScreen) {
						log("[origin-smoke] in-world: quit clean (saved, back on the title)");
						inWorldEnd(mc, true, "", onEnd);
						return;
					}
					if (now - iwFrom > IW_QUIT_MS) {
						inWorldEnd(mc, false, "Save and Quit did not reach the title in " + IW_QUIT_MS / 1000 + " s", onEnd);
						return;
					}
					iwAt = now + 250;
				}
				default -> {
				}
			}
		} catch (Throwable t) {
			com.origin.client.OriginClient.LOGGER.error("[origin-smoke] in-world step {} threw", iw, t);
			inWorldEnd(mc, false, "step " + iw + " threw " + t, onEnd);
		}
	}

	private static void inWorldEnd(Minecraft mc, boolean pass, String reason, Runnable onEnd) {
		iwDone = true;
		try {
			if (iwLimitUnfocused != null) {
				Mods.set(Mods.PERFORMANCE_ID, "limitUnfocusedFps", iwLimitUnfocused);
			}
		} catch (Throwable ignored) {
		}
		if (pass) {
			log("[origin-smoke] in-world: PASS");
		} else {
			com.origin.client.OriginClient.LOGGER.error("[origin-smoke] in-world: FAIL {}", reason);
		}
		try {
			onEnd.run();
		} finally {
			mc.stop();
		}
	}

	// ---- Limit Far Shadows measurement (only with a shader pack active) ----
	// A fixed test scene -- 8 still pigs and 8 chests on each ring at 8/24/40/56/72 blocks --
	// then the shadow-pass caster counts per frame with the limit OFF, and ON at 32 blocks.
	// These are CPU-side counts of what Iris asked to draw into the shadow map; no timings.
	private static final int[] RINGS = {8, 24, 40, 56, 72};
	private static long snapFrames, snapSeenE, snapSkipE, snapSeenBE, snapSkipBE;
	private static String offResult;

	private static boolean shadowLimitStep(Minecraft mc, String dir) {
		if (mc.screen instanceof PauseScreen) {
			mc.setScreen(null);
		}
		if (phase == 40) {
			var server = mc.getSingleplayerServer();
			if (server == null || mc.player == null) {
				return false;
			}
			net.minecraft.core.BlockPos p = mc.player.blockPosition();
			server.execute(() -> {
				var src = server.createCommandSourceStack().withSuppressedOutput();
				for (int r : RINGS) {
					for (int i = 0; i < 8; i++) {
						double a = Math.PI * 2 * i / 8;
						int x = p.getX() + (int) Math.round(Math.cos(a) * r);
						int z = p.getZ() + (int) Math.round(Math.sin(a) * r);
						server.getCommands().performPrefixedCommand(src, "setblock " + x + " " + (p.getY() + 1) + " " + z + " minecraft:chest");
						server.getCommands().performPrefixedCommand(src, "summon minecraft:pig " + x + " " + (p.getY() + 3) + " " + z
								+ " {NoAI:1b,Invulnerable:1b,Silent:1b,Tags:[\"origin_shadowtest\"]}");
					}
				}
			});
			log("[shadow-limit] test scene: 40 pigs + 40 chests on rings " + java.util.Arrays.toString(RINGS) + " around " + p.toShortString());
			Mods.set(Mods.PERFORMANCE_ID, com.origin.client.client.render.ShadowCasterLimit.KEY_ON, false);
			phase = 41;
			nextAt = System.currentTimeMillis() + 5000;   // let the client receive and draw the scene
			return true;
		}
		if (phase == 41 || phase == 43) {
			snapshot();
			phase++;
			nextAt = System.currentTimeMillis() + 4000;   // average over ~4 s of frames
			return true;
		}
		if (phase == 42) {
			offResult = measured();
			grab(mc, dir, "shadowlimit_off_1920x1080.png");
			Mods.set(Mods.PERFORMANCE_ID, com.origin.client.client.render.ShadowCasterLimit.KEY_DISTANCE, 32.0);
			Mods.set(Mods.PERFORMANCE_ID, com.origin.client.client.render.ShadowCasterLimit.KEY_ON, true);
			phase = 43;
			nextAt = System.currentTimeMillis() + 1500;
			return true;
		}
		if (phase == 44) {
			String on = measured();
			grab(mc, dir, "shadowlimit_on_1920x1080.png");
			log("[shadow-limit] OFF: " + offResult);
			log("[shadow-limit] ON @32 blocks: " + on);
			Mods.set(Mods.PERFORMANCE_ID, com.origin.client.client.render.ShadowCasterLimit.KEY_ON, false);
			var server = mc.getSingleplayerServer();
			if (server != null) {
				server.execute(() -> server.getCommands().performPrefixedCommand(
						server.createCommandSourceStack().withSuppressedOutput(), "kill @e[tag=origin_shadowtest]"));
			}
			phase = 45;   // next: Smart Terrain Updates
			return true;
		}
		return false;
	}

	// ---- Smart Terrain Updates measurement (only with a shader pack active) ----
	// Time, weather, ticking and clouds frozen, HUD hidden, camera fixed: chunk-graph walks per
	// frame and their CPU time with the feature OFF and ON, standing still and turning. Two OFF
	// frames give the noise floor for the OFF-vs-ON pixel diff. A block placed in view while ON
	// proves an edit forces a fresh walk (it must appear in terrain_on_edit).
	// Entity culling: pigs in a sealed underground pocket (a stone section, hollowed, with its 6
	// face-neighbour sections filled with stone) in view but behind terrain, so Sodium culls them, plus pigs on the surface in
	// view. The per-frame entity culling results must be identical OFF and ON (same visible set).
	private static boolean terrainSpin;
	private static long stFrames, stWalks, stReused, stMainNs, stShadowWalks, stShadowNs, stRestoreNs;
	private static net.minecraft.core.BlockPos stEdit;
	private static net.minecraft.client.CloudStatus stClouds;
	private static int stRenderDistance = -1;
	private static String stOffStill, stOnStill, stOnEdit, stOnMove;

	private static boolean smartTerrainStep(Minecraft mc, String dir) {
		if (mc.screen instanceof PauseScreen) {
			mc.setScreen(null);
		}
		var server = mc.getSingleplayerServer();
		if (server == null || mc.player == null) {
			return false;
		}
		String key = com.origin.client.client.render.SmartTerrainUpdates.KEY_ON;
		switch (phase) {
			case 45 -> {
				server.execute(() -> {
					var src = server.createCommandSourceStack().withSuppressedOutput();
					for (String c : new String[]{"gamerule doDaylightCycle false", "gamerule doWeatherCycle false",
							"time set 6000", "weather clear", "tick freeze"}) {
						server.getCommands().performPrefixedCommand(src, c);
					}
				});
				stClouds = mc.options.cloudStatus().get();
				mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
				mc.options.hideGui = true;
				mc.player.setXRot(25f);
				Mods.set(Mods.PERFORMANCE_ID, key, false);
				stEntityScene(mc, server);
				// CI renders at a few fps, so a 12-chunk world is still loading and lighting minutes in
				// (every frame adds sections and rebuilds: nothing to reuse). A 3-chunk view settles.
				stRenderDistance = mc.options.renderDistance().get();
				mc.options.renderDistance().set(3);
				nextAt = System.currentTimeMillis() + 25000;
			}
			case 46 -> {
				grab(mc, dir, "terrain_off_a_1920x1080.png");
				stSnap();
				nextAt = System.currentTimeMillis() + 4000;
			}
			case 47 -> {
				stOffStill = stMeasured();
				grab(mc, dir, "terrain_off_b_1920x1080.png");
				Mods.set(Mods.PERFORMANCE_ID, key, true);
				nextAt = System.currentTimeMillis() + 2000;
			}
			case 48 -> {
				stSnap();
				nextAt = System.currentTimeMillis() + 4000;
			}
			case 49 -> {
				stOnStill = stMeasured();
				grab(mc, dir, "terrain_on_1920x1080.png");
				// A gold block 4 blocks along the view (center of the shot): Sodium schedules a rebuild, so the next
				// main frame must walk again and the block must show up.
				net.minecraft.world.phys.Vec3 look = mc.player.getLookAngle();
				net.minecraft.world.phys.Vec3 eye = mc.player.getEyePosition();
				stEdit = net.minecraft.core.BlockPos.containing(eye.add(look.scale(4)));
				net.minecraft.core.BlockPos at = stEdit;
				server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(),
						"setblock " + at.getX() + " " + at.getY() + " " + at.getZ() + " minecraft:gold_block"));
				stSnap();
				nextAt = System.currentTimeMillis() + 3000;
			}
			case 50 -> {
				stOnEdit = stMeasured();
				grab(mc, dir, "terrain_on_edit_1920x1080.png");
				terrainSpin = true;
				stSnap();
				nextAt = System.currentTimeMillis() + 4000;
			}
			case 51 -> {
				stOnMove = stMeasured();
				Mods.set(Mods.PERFORMANCE_ID, key, false);
				stSnap();
				nextAt = System.currentTimeMillis() + 4000;
			}
			case 52 -> {
				String offMove = stMeasured();
				terrainSpin = false;
				log("[smart-terrain] OFF still: " + stOffStill);
				log("[smart-terrain] ON still: " + stOnStill);
				log("[smart-terrain] ON block edit: " + stOnEdit);
				log("[smart-terrain] ON turning: " + stOnMove);
				log("[smart-terrain] OFF turning: " + offMove);
				log("[smart-terrain] hooks seen: " + com.origin.client.client.render.SmartTerrainUpdates.hooksLine()
						+ " | player alive=" + mc.player.isAlive());
				net.minecraft.core.BlockPos at = stEdit;
				server.execute(() -> {
					var src = server.createCommandSourceStack().withSuppressedOutput();
					if (at != null) {
						server.getCommands().performPrefixedCommand(src, "setblock " + at.getX() + " " + at.getY() + " " + at.getZ() + " minecraft:air");
					}
					server.getCommands().performPrefixedCommand(src, "kill @e[tag=origin_stutest]");
				});
				com.origin.client.client.render.SmartTerrainUpdates.hiddenSection = Long.MIN_VALUE;
				omScene(mc, server);
				nextAt = System.currentTimeMillis() + 6000;   // let the client receive the test pigs
			}
			// ---- Off-Map Shadows (same frozen scene): shadow-pass sections/entities OFF vs ON, plus a
			// readback of Iris's shadow depth texture so the shadow map itself is compared texel by texel.
			case 53 -> {
				omSnap();
				omDepthA = omReadShadowDepth();
				grab(mc, dir, "offmap_off_a_1920x1080.png");
				nextAt = System.currentTimeMillis() + 4000;
			}
			case 54 -> {
				omOff = omMeasured();
				omDepthB = omReadShadowDepth();
				grab(mc, dir, "offmap_off_b_1920x1080.png");
				Mods.set(Mods.PERFORMANCE_ID, com.origin.client.client.render.OffMapShadows.KEY_ON, true);
				nextAt = System.currentTimeMillis() + 2000;
			}
			case 55 -> {
				omSnap();
				nextAt = System.currentTimeMillis() + 4000;
			}
			case 56 -> {
				String on = omMeasured();
				float[] depthOn = omReadShadowDepth();
				grab(mc, dir, "offmap_on_1920x1080.png");
				log("[off-map] OFF: " + omOff);
				log("[off-map] ON: " + on);
				log("[off-map] shadow depth OFF a vs OFF b: " + omDepthDiff(omDepthA, omDepthB));
				log("[off-map] shadow depth OFF b vs ON: " + omDepthDiff(omDepthB, depthOn));
				log("[off-map] F3: " + com.origin.client.client.render.OffMapShadows.debugLine());
				Mods.set(Mods.PERFORMANCE_ID, com.origin.client.client.render.OffMapShadows.KEY_ON, false);
				omDepthA = omDepthB = null;
				// Creative so the slide below can hover without the server's floating check.
				server.execute(() -> server.getCommands().performPrefixedCommand(
						server.createCommandSourceStack().withSuppressedOutput(), "gamemode creative @a"));
				nextAt = System.currentTimeMillis() + 3000;
			}
			// ---- Half-Rate Shadows (same frozen scene, pigs kept as shadow casters): shadow maps kept vs
			// drawn OFF vs ON, the shadow depth texture after a KEPT frame vs OFF, a slow sideways slide
			// (kept/drawn + the swim check), then a block edit and a teleport must each force a redraw.
			case 57 -> {
				hrSnap();
				omDepthA = omReadShadowDepth();
				grab(mc, dir, "halfrate_off_a_1920x1080.png");
				nextAt = System.currentTimeMillis() + 4000;
			}
			case 58 -> {
				hrOffStill = hrMeasured();
				omDepthB = omReadShadowDepth();
				grab(mc, dir, "halfrate_off_b_1920x1080.png");
				Mods.set(Mods.PERFORMANCE_ID, com.origin.client.client.render.HalfRateShadows.KEY_ON, true);
				nextAt = System.currentTimeMillis() + 2000;
			}
			case 59 -> {
				hrSnap();
				nextAt = System.currentTimeMillis() + 4000;
			}
			case 60 -> {
				// Read the shadow map right after a frame that KEPT it (no clear, no draw): a skipped clear
				// that was not really skipped would show here as an empty map.
				if (!com.origin.client.client.render.HalfRateShadows.devLastKept && hrTries++ < 100) {
					nextAt = System.currentTimeMillis() + 20;
					return true;
				}
				hrOnStill = hrMeasured() + " readAfterKeptFrame=" + com.origin.client.client.render.HalfRateShadows.devLastKept;
				float[] depthOn = omReadShadowDepth();
				hrDepthOffOff = omDepthDiff(omDepthA, omDepthB);
				hrDepthOffOn = omDepthDiff(omDepthB, depthOn);
				grab(mc, dir, "halfrate_on_still_1920x1080.png");
				omDepthA = omDepthB = null;
				hrF3 = com.origin.client.client.render.HalfRateShadows.debugLine();
				// The slide: sideways (perpendicular to the view), 0.1 block per client tick.
				double yaw = Math.toRadians(mc.player.getYRot());
				hrDx = Math.cos(yaw) * 0.1;
				hrDz = Math.sin(yaw) * 0.1;
				hrY = mc.player.getY();
				mc.player.getAbilities().flying = true;
				com.origin.client.client.render.HalfRateShadows.devMaxErrFixed = 0;
				com.origin.client.client.render.HalfRateShadows.devMaxErrFresh = 0;
				com.origin.client.client.render.HalfRateShadows.devSwimSamples = 0;
				hrSlide = true;
				hrSnap();
				nextAt = System.currentTimeMillis() + 5000;
			}
			case 61 -> {
				grab(mc, dir, "halfrate_on_move_1920x1080.png");
				nextAt = System.currentTimeMillis() + 3000;
			}
			case 62 -> {
				hrOnMove = hrMeasured() + String.format(java.util.Locale.ROOT, " swimSamples=%d swimErrWithFix=%.5f swimErrWithoutFix=%.5f",
						com.origin.client.client.render.HalfRateShadows.devSwimSamples,
						com.origin.client.client.render.HalfRateShadows.devMaxErrFixed,
						com.origin.client.client.render.HalfRateShadows.devMaxErrFresh);
				Mods.set(Mods.PERFORMANCE_ID, com.origin.client.client.render.HalfRateShadows.KEY_ON, false);
				hrSnap();
				nextAt = System.currentTimeMillis() + 2000;
			}
			case 63 -> {
				grab(mc, dir, "halfrate_off_move_1920x1080.png");
				nextAt = System.currentTimeMillis() + 3000;
			}
			case 64 -> {
				hrOffMove = hrMeasured();
				hrSlide = false;
				Mods.set(Mods.PERFORMANCE_ID, com.origin.client.client.render.HalfRateShadows.KEY_ON, true);
				nextAt = System.currentTimeMillis() + 5000;   // settle after the slide's chunk loads
			}
			case 65 -> {
				// Block edit: a gold block 4 blocks along the view; the redraws it forces count as "chunks".
				hrSnap();
				net.minecraft.world.phys.Vec3 look = mc.player.getLookAngle();
				hrEdit = net.minecraft.core.BlockPos.containing(mc.player.getEyePosition().add(look.scale(4)));
				net.minecraft.core.BlockPos at = hrEdit;
				server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(),
						"setblock " + at.getX() + " " + at.getY() + " " + at.getZ() + " minecraft:gold_block"));
				nextAt = System.currentTimeMillis() + 3000;
			}
			case 66 -> {
				hrOnEdit = hrMeasured();
				grab(mc, dir, "halfrate_on_edit_1920x1080.png");
				// Teleport 20 blocks sideways (client-side move, like a /tp landing): must count as a jump.
				hrSnap();
				mc.player.setPos(mc.player.getX() + hrDx * 200, hrY, mc.player.getZ() + hrDz * 200);
				nextAt = System.currentTimeMillis() + 3000;
			}
			case 67 -> {
				String onTp = hrMeasured();
				log("[half-rate] OFF still: " + hrOffStill);
				log("[half-rate] ON still: " + hrOnStill);
				log("[half-rate] shadow depth OFF a vs OFF b (baseline): " + hrDepthOffOff);
				log("[half-rate] shadow depth OFF vs ON (read after a kept frame): " + hrDepthOffOn);
				log("[half-rate] ON sliding: " + hrOnMove);
				log("[half-rate] OFF sliding: " + hrOffMove);
				log("[half-rate] ON block edit: " + hrOnEdit);
				log("[half-rate] ON teleport 20 blocks: " + onTp);
				log("[half-rate] F3: " + hrF3);
				Mods.set(Mods.PERFORMANCE_ID, com.origin.client.client.render.HalfRateShadows.KEY_ON, false);
				net.minecraft.core.BlockPos at = hrEdit;
				server.execute(() -> {
					var src = server.createCommandSourceStack().withSuppressedOutput();
					if (at != null) {
						server.getCommands().performPrefixedCommand(src, "setblock " + at.getX() + " " + at.getY() + " " + at.getZ() + " minecraft:air");
					}
					server.getCommands().performPrefixedCommand(src, "kill @e[tag=origin_omtest]");
					server.getCommands().performPrefixedCommand(src, "tick unfreeze");
				});
				if (stClouds != null) {
					mc.options.cloudStatus().set(stClouds);
				}
				mc.options.hideGui = false;
				if (stRenderDistance > 0) {
					mc.options.renderDistance().set(stRenderDistance);
				}
				phase = 68;
				return false;
			}
			default -> {
				return false;
			}
		}
		phase++;
		return true;
	}

	// ---- Half-Rate Shadows measurement ----
	private static boolean hrSlide;
	private static double hrDx, hrDz, hrY;
	private static int hrTries;
	private static net.minecraft.core.BlockPos hrEdit;
	private static String hrOffStill, hrOnStill, hrDepthOffOff, hrDepthOffOn, hrOnMove, hrOffMove, hrOnEdit, hrF3;
	private static long hrFrames, hrKept, hrDrawn, hrAlt, hrFirst, hrWorld, hrReload, hrResize, hrDist, hrJump, hrChunks,
			hrClear, hrPack, hrNotReady, hrShadowWalks, hrShadowNs;

	private static void hrSnap() {
		hrFrames = com.origin.client.client.hud.FrameStats.frames();
		hrKept = com.origin.client.client.render.HalfRateShadows.devKept;
		hrDrawn = com.origin.client.client.render.HalfRateShadows.devDrawn;
		hrAlt = com.origin.client.client.render.HalfRateShadows.devAlternate;
		hrFirst = com.origin.client.client.render.HalfRateShadows.devFirst;
		hrWorld = com.origin.client.client.render.HalfRateShadows.devWorld;
		hrReload = com.origin.client.client.render.HalfRateShadows.devReload;
		hrResize = com.origin.client.client.render.HalfRateShadows.devResize;
		hrDist = com.origin.client.client.render.HalfRateShadows.devDistance;
		hrJump = com.origin.client.client.render.HalfRateShadows.devJump;
		hrChunks = com.origin.client.client.render.HalfRateShadows.devChunks;
		hrClear = com.origin.client.client.render.HalfRateShadows.devFullClear;
		hrPack = com.origin.client.client.render.HalfRateShadows.devPack;
		hrNotReady = com.origin.client.client.render.HalfRateShadows.devNotReady;
		hrShadowWalks = com.origin.client.client.render.SmartTerrainUpdates.totalShadowWalks;
		hrShadowNs = com.origin.client.client.render.SmartTerrainUpdates.totalShadowNanos;
	}

	/**
	 * Since hrSnap(): frames, shadow maps kept / drawn by the feature (its counters only move while it is
	 * on), why each draw happened, and the shadow-pass chunk walks that really ran (Smart Terrain's
	 * counter: one per real shadow draw, whatever the toggle). CPU-side counts only.
	 */
	private static String hrMeasured() {
		long f = com.origin.client.client.hud.FrameStats.frames() - hrFrames;
		long sw = com.origin.client.client.render.SmartTerrainUpdates.totalShadowWalks - hrShadowWalks;
		long sn = com.origin.client.client.render.SmartTerrainUpdates.totalShadowNanos - hrShadowNs;
		return String.format(java.util.Locale.ROOT,
				"frames=%d shadowWalks=%d kept=%d drawn=%d [alternate=%d first=%d world=%d reload=%d resize=%d distance=%d"
						+ " jump=%d chunks=%d fullClear=%d pack=%d notReady=%d] shadowWalkMs=%.3f",
				f, sw,
				com.origin.client.client.render.HalfRateShadows.devKept - hrKept,
				com.origin.client.client.render.HalfRateShadows.devDrawn - hrDrawn,
				com.origin.client.client.render.HalfRateShadows.devAlternate - hrAlt,
				com.origin.client.client.render.HalfRateShadows.devFirst - hrFirst,
				com.origin.client.client.render.HalfRateShadows.devWorld - hrWorld,
				com.origin.client.client.render.HalfRateShadows.devReload - hrReload,
				com.origin.client.client.render.HalfRateShadows.devResize - hrResize,
				com.origin.client.client.render.HalfRateShadows.devDistance - hrDist,
				com.origin.client.client.render.HalfRateShadows.devJump - hrJump,
				com.origin.client.client.render.HalfRateShadows.devChunks - hrChunks,
				com.origin.client.client.render.HalfRateShadows.devFullClear - hrClear,
				com.origin.client.client.render.HalfRateShadows.devPack - hrPack,
				com.origin.client.client.render.HalfRateShadows.devNotReady - hrNotReady,
				sw == 0 ? 0.0 : sn / 1e6 / sw);
	}

	private static long omFrames, omWalks, omDrawnS, omOffS, omSeenE, omOffE, omSkipE, omSeenBE, omShadowWalks, omShadowNs;
	private static String omOff;
	private static float[] omDepthA, omDepthB;

	/**
	 * Off-Map Shadows test pigs, in front of the player along the world axis nearest the view: 3 at 12
	 * blocks (on the map), 3 at 40 and 3 at 46 (off a 32-block half plane at noon, still inside RD 3).
	 */
	private static void omScene(Minecraft mc, net.minecraft.server.MinecraftServer server) {
		net.minecraft.world.phys.Vec3 look = mc.player.getLookAngle();
		boolean alongX = Math.abs(look.x) >= Math.abs(look.z);
		int sign = (alongX ? look.x : look.z) >= 0 ? 1 : -1;
		net.minecraft.core.BlockPos p = mc.player.blockPosition();
		server.execute(() -> {
			var src = server.createCommandSourceStack().withSuppressedOutput();
			var level = server.overworld();
			for (int d : new int[]{12, 40, 46}) {
				for (int side = -6; side <= 6; side += 6) {
					int x = p.getX() + (alongX ? sign * d : side);
					int z = p.getZ() + (alongX ? side : sign * d);
					int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z);
					server.getCommands().performPrefixedCommand(src, "summon minecraft:pig " + x + " " + y + " " + z
							+ " {NoAI:1b,Invulnerable:1b,Silent:1b,Tags:[\"origin_omtest\"]}");
				}
			}
		});
		log("[off-map] scene: 9 pigs at 12/40/46 blocks along " + (sign > 0 ? "+" : "-") + (alongX ? "x" : "z")
				+ " from " + p.toShortString());
	}

	private static void omSnap() {
		omFrames = com.origin.client.client.hud.FrameStats.frames();
		omWalks = com.origin.client.client.render.OffMapShadows.devWalks;
		omDrawnS = com.origin.client.client.render.OffMapShadows.devDrawnS;
		omOffS = com.origin.client.client.render.OffMapShadows.devOffS;
		omSeenE = com.origin.client.client.render.OffMapShadows.devSeenE;
		omOffE = com.origin.client.client.render.OffMapShadows.devOffE;
		omSkipE = com.origin.client.client.render.OffMapShadows.devSkipE;
		omSeenBE = com.origin.client.client.render.ShadowCasterLimit.totalSeenBE;
		omShadowWalks = com.origin.client.client.render.SmartTerrainUpdates.totalShadowWalks;
		omShadowNs = com.origin.client.client.render.SmartTerrainUpdates.totalShadowNanos;
	}

	/** Since omSnap(), per shadow walk / per frame. CPU-side counts and CPU walk time only. */
	private static String omMeasured() {
		long f = com.origin.client.client.hud.FrameStats.frames() - omFrames;
		long w = com.origin.client.client.render.OffMapShadows.devWalks - omWalks;
		long ds = com.origin.client.client.render.OffMapShadows.devDrawnS - omDrawnS;
		long os = com.origin.client.client.render.OffMapShadows.devOffS - omOffS;
		long se = com.origin.client.client.render.OffMapShadows.devSeenE - omSeenE;
		long oe = com.origin.client.client.render.OffMapShadows.devOffE - omOffE;
		long ke = com.origin.client.client.render.OffMapShadows.devSkipE - omSkipE;
		long be = com.origin.client.client.render.ShadowCasterLimit.totalSeenBE - omSeenBE;
		long sw = com.origin.client.client.render.SmartTerrainUpdates.totalShadowWalks - omShadowWalks;
		long sn = com.origin.client.client.render.SmartTerrainUpdates.totalShadowNanos - omShadowNs;
		double fw = Math.max(w, 1), ff = Math.max(f, 1);
		return String.format(java.util.Locale.ROOT,
				"frames=%d shadowWalks=%d halfPlane=%.1f sectionsDrawn/walk=%.1f offMapDrawn/walk=%.1f"
						+ " entitiesKept/frame=%.2f offMapKept/frame=%.2f skipped/frame=%.2f drawn/frame=%.2f blockEntities/frame=%.2f shadowWalkMs=%.3f",
				f, w, com.origin.client.client.render.OffMapShadows.devHalfPlane(), ds / fw, os / fw,
				se / ff, oe / ff, ke / ff, (se - ke) / ff, be / ff, sw == 0 ? 0.0 : sn / 1e6 / sw);
	}

	/** Dev only: Iris's shadow depth texture (shadowtex0) read back as floats; null if anything is missing. */
	private static float[] omReadShadowDepth() {
		try {
			Object pm = Class.forName("net.irisshaders.iris.Iris").getMethod("getPipelineManager").invoke(null);
			Object pipe = pm.getClass().getMethod("getPipelineNullable").invoke(pm);
			java.lang.reflect.Field fr = pipe.getClass().getDeclaredField("shadowRenderer");
			fr.setAccessible(true);
			Object sr = fr.get(pipe);
			java.lang.reflect.Field ft = sr.getClass().getDeclaredField("targets");
			ft.setAccessible(true);
			Object targets = ft.get(sr);
			Object depth = targets.getClass().getMethod("getDepthTexture").invoke(targets);
			// PER-VERSION (1.21.6+): Iris 1.9.x hands back a vanilla GlTexture (glId()); older Iris had its own
			// DepthTexture with getTextureId(). Dev-only harness, so plain reflection is fine here.
			java.lang.reflect.Method idOf;
			try {
				idOf = depth.getClass().getMethod("glId");
			} catch (NoSuchMethodException e) {
				idOf = depth.getClass().getMethod("getTextureId");
			}
			int id = (int) idOf.invoke(depth);
			int prev = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D);
			org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, id);
			int w = org.lwjgl.opengl.GL11.glGetTexLevelParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL11.GL_TEXTURE_WIDTH);
			int h = org.lwjgl.opengl.GL11.glGetTexLevelParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL11.GL_TEXTURE_HEIGHT);
			if (w <= 0 || h <= 0 || (long) w * h > (1 << 26)) {
				org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, prev);
				log("[off-map] shadow depth readback skipped: size " + w + "x" + h);
				return null;
			}
			float[] out = new float[w * h];
			// PER-VERSION (1.21.6+): vanilla GlCommandEncoder.copyTextureToBuffer sets GL_PACK_ROW_LENGTH to the
			// copied texture's width (screenshots: 1920) and never resets it, so a plain glGetTexImage of a smaller
			// shadow map strides 1920 texels per row and writes past this array (SIGSEGV in Mesa, CI 2026-10-20).
			// Pack state is set to defaults (and no pack PBO bound) for this one read, then put back.
			int pRow = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_PACK_ROW_LENGTH);
			int pSkipR = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_PACK_SKIP_ROWS);
			int pSkipP = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_PACK_SKIP_PIXELS);
			int pAlign = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_PACK_ALIGNMENT);
			int pPbo = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER_BINDING);
			org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER, 0);
			org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_PACK_ROW_LENGTH, 0);
			org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_PACK_SKIP_ROWS, 0);
			org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_PACK_SKIP_PIXELS, 0);
			org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_PACK_ALIGNMENT, 4);
			try {
				org.lwjgl.opengl.GL11.glGetTexImage(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL11.GL_DEPTH_COMPONENT,
						org.lwjgl.opengl.GL11.GL_FLOAT, out);
			} finally {
				org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_PACK_ROW_LENGTH, pRow);
				org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_PACK_SKIP_ROWS, pSkipR);
				org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_PACK_SKIP_PIXELS, pSkipP);
				org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_PACK_ALIGNMENT, pAlign);
				org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER, pPbo);
			}
			org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, prev);
			return out;
		} catch (Throwable t) {
			log("[off-map] shadow depth readback failed: " + t);
			return null;
		}
	}

	private static String omDepthDiff(float[] a, float[] b) {
		if (a == null || b == null || a.length != b.length) {
			return "n/a";
		}
		int diff = 0, coveredA = 0, coveredB = 0, side = (int) Math.round(Math.sqrt(a.length));
		int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = -1, y1 = -1;
		float maxD = 0f;
		for (int i = 0; i < a.length; i++) {
			if (Float.floatToRawIntBits(a[i]) != Float.floatToRawIntBits(b[i])) {
				diff++;
				int x = i % side, y = i / side;
				x0 = Math.min(x0, x); x1 = Math.max(x1, x); y0 = Math.min(y0, y); y1 = Math.max(y1, y);
				maxD = Math.max(maxD, Math.abs(a[i] - b[i]));
			}
			if (a[i] < 1f) {
				coveredA++;
			}
			if (b[i] < 1f) {
				coveredB++;
			}
		}
		return "texels=" + a.length + " differing=" + diff + " coveredA=" + coveredA + " coveredB=" + coveredB
				+ (diff == 0 ? "" : " bbox=" + x0 + "," + y0 + "-" + x1 + "," + y1 + " maxDepthDelta=" + maxD);
	}

	/** The entity-culling scene (see the comment above terrainSpin). Built before the settle wait. */
	private static void stEntityScene(Minecraft mc, net.minecraft.server.MinecraftServer server) {
		double yaw = Math.toRadians(mc.player.getYRot());
		double hx = -Math.sin(yaw), hz = Math.cos(yaw);   // horizontal view direction
		net.minecraft.world.phys.Vec3 eye = mc.player.getEyePosition();
		// Pocket 28 ahead, 28 down: 20 degrees under the view centre at pitch 25, inside the frustum.
		// 28 ahead keeps every filled section off the player's own block column on at least one axis
		// (a 3x3x3 block at 20 ahead entombed the player in the first run).
		int sx = net.minecraft.core.SectionPos.blockToSectionCoord(eye.x + hx * 28);
		int sy = net.minecraft.core.SectionPos.blockToSectionCoord(eye.y - 28);
		int sz = net.minecraft.core.SectionPos.blockToSectionCoord(eye.z + hz * 28);
		java.util.List<String> cmds = new java.util.ArrayList<>();
		// Sodium's walk enters a section only through a face of a visited neighbour that connects to
		// it, so solid stone in the pocket section and its 6 face neighbours seals it off whatever caves
		// are around.
		int[][] cells = {{0, 0, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
		for (int[] d : cells) {
			int x0 = (sx + d[0]) * 16, y0 = (sy + d[1]) * 16, z0 = (sz + d[2]) * 16;
			cmds.add("fill " + x0 + " " + y0 + " " + z0 + " " + (x0 + 15) + " " + (y0 + 15) + " " + (z0 + 15) + " minecraft:stone");
		}
		com.origin.client.client.render.SmartTerrainUpdates.hiddenSection = net.minecraft.core.SectionPos.asLong(sx, sy, sz);
		int cx = sx * 16, cy = sy * 16, cz = sz * 16;
		cmds.add("fill " + (cx + 1) + " " + (cy + 1) + " " + (cz + 1) + " " + (cx + 14) + " " + (cy + 14) + " " + (cz + 14) + " minecraft:air");
		String nbt = "{NoAI:1b,Silent:1b,PersistenceRequired:1b,Tags:[\"origin_stutest\",\""
				+ com.origin.client.client.render.SmartTerrainUpdates.HIDDEN_TAG + "\"]}";
		for (int i = 0; i < 4; i++) {
			cmds.add("summon minecraft:pig " + (cx + 4.5 + i * 2) + " " + (cy + 1) + " " + (cz + 8.5) + " " + nbt);
		}
		double px = -hz, pz = hx;   // perpendicular, for a row of surface pigs across the view
		server.execute(() -> {
			var src = server.createCommandSourceStack().withSuppressedOutput();
			for (String c : cmds) {
				server.getCommands().performPrefixedCommand(src, c);
			}
			var level = server.overworld();
			for (int i = -1; i <= 1; i++) {
				int x = net.minecraft.util.Mth.floor(eye.x + hx * 7 + px * i * 2.5);
				int z = net.minecraft.util.Mth.floor(eye.z + hz * 7 + pz * i * 2.5);
				int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z);
				server.getCommands().performPrefixedCommand(src, "summon minecraft:pig " + (x + 0.5) + " " + y + " " + (z + 0.5)
						+ " {NoAI:1b,Silent:1b,PersistenceRequired:1b,Tags:[\"origin_stutest\"]}");
			}
		});
		log("[smart-terrain] entity scene: pocket section " + sx + "," + sy + "," + sz + " (4 hidden pigs), 3 surface pigs 7 ahead");
	}

	private static void stSnap() {
		stFrames = com.origin.client.client.hud.FrameStats.frames();
		stWalks = com.origin.client.client.render.SmartTerrainUpdates.totalMainWalks;
		stReused = com.origin.client.client.render.SmartTerrainUpdates.totalReused;
		stMainNs = com.origin.client.client.render.SmartTerrainUpdates.totalMainNanos;
		stShadowWalks = com.origin.client.client.render.SmartTerrainUpdates.totalShadowWalks;
		stShadowNs = com.origin.client.client.render.SmartTerrainUpdates.totalShadowNanos;
		stRestoreNs = com.origin.client.client.render.SmartTerrainUpdates.totalRestoreNanos;
		com.origin.client.client.render.SmartTerrainUpdates.resetWhy();
		com.origin.client.client.render.SmartTerrainUpdates.entityWindowReset();
	}

	/** Since stSnap(): frames, main walks run vs reused, CPU ms per main walk / shadow walk / restore. CPU-side only. */
	private static String stMeasured() {
		long f = com.origin.client.client.hud.FrameStats.frames() - stFrames;
		long w = com.origin.client.client.render.SmartTerrainUpdates.totalMainWalks - stWalks;
		long r = com.origin.client.client.render.SmartTerrainUpdates.totalReused - stReused;
		long mainNs = com.origin.client.client.render.SmartTerrainUpdates.totalMainNanos - stMainNs;
		long sw = com.origin.client.client.render.SmartTerrainUpdates.totalShadowWalks - stShadowWalks;
		long shNs = com.origin.client.client.render.SmartTerrainUpdates.totalShadowNanos - stShadowNs;
		long reNs = com.origin.client.client.render.SmartTerrainUpdates.totalRestoreNanos - stRestoreNs;
		return String.format(java.util.Locale.ROOT,
				"frames=%d mainWalks=%d reused=%d mainWalkMs/frame=%.3f (%.3f per walk) shadowWalks=%d shadowWalkMs=%.3f restoreMs/reuse=%.4f",
				f, w, r, f == 0 ? 0.0 : mainNs / 1e6 / f, w == 0 ? 0.0 : mainNs / 1e6 / w,
				sw, sw == 0 ? 0.0 : shNs / 1e6 / sw, r == 0 ? 0.0 : reNs / 1e6 / r)
				+ " " + com.origin.client.client.render.SmartTerrainUpdates.entityLine()
				+ " " + com.origin.client.client.render.SmartTerrainUpdates.whyLine();
	}

	private static void snapshot() {
		snapFrames = com.origin.client.client.hud.FrameStats.frames();
		snapSeenE = com.origin.client.client.render.ShadowCasterLimit.totalSeenE;
		snapSkipE = com.origin.client.client.render.ShadowCasterLimit.totalSkipE;
		snapSeenBE = com.origin.client.client.render.ShadowCasterLimit.totalSeenBE;
		snapSkipBE = com.origin.client.client.render.ShadowCasterLimit.totalSkipBE;
	}

	/** Per-frame averages since snapshot(): shadow casters Iris asked for, and how many were skipped. */
	private static String measured() {
		long f = Math.max(1, com.origin.client.client.hud.FrameStats.frames() - snapFrames);
		double seenE = (com.origin.client.client.render.ShadowCasterLimit.totalSeenE - snapSeenE) / (double) f;
		double skipE = (com.origin.client.client.render.ShadowCasterLimit.totalSkipE - snapSkipE) / (double) f;
		double seenBE = (com.origin.client.client.render.ShadowCasterLimit.totalSeenBE - snapSeenBE) / (double) f;
		double skipBE = (com.origin.client.client.render.ShadowCasterLimit.totalSkipBE - snapSkipBE) / (double) f;
		return String.format(java.util.Locale.ROOT,
				"frames=%d entities/frame asked=%.1f skipped=%.1f drawn=%.1f | block entities/frame asked=%.1f skipped=%.1f drawn=%.1f",
				f, seenE, skipE, seenE - skipE, seenBE, skipBE, seenBE - skipBE);
	}

	// ---- cosmetics on your own player, orbited by the freelook camera (toggle mode keeps it latched) ----
	private static final String[][] FIT_LOOKS = {
			{"hat-headband", "mask-shades", "pack-shell"},
			{"hat-visor", "mask-goggles", "pack-explorer"},
	};
	/** Camera yaw relative to the player (0 = behind), and the shot's name. */
	private static final Object[][] FIT_VIEWS = {{0f, "back"}, {90f, "side90"}, {-90f, "side270"}, {180f, "front"}, {35f, "back35"}};
	private static int fitStep = -1;
	private static float fitBaseYaw;

	/** One step of the fit shots (dress, then aim + wait, then grab); false once every shot is taken. */
	private static boolean fitStep(Minecraft mc, String dir) {
		int perLook = FIT_VIEWS.length;
		int total = FIT_LOOKS.length * perLook + 1;   // + the first look's side view with the outer layer hidden
		if (mc.screen instanceof PauseScreen) {
			mc.setScreen(null);
		}
		if (fitStep == -1) {
			fitBaseYaw = mc.player.getYRot();
			mc.player.setXRot(0f);
			mc.options.fov().set(30);
			mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
			Mods.setOn("freelook", true);
			Mods.set("freelook", "toggle", true);
			fitStep = 0;
		} else {
			int done = fitStep - 1;   // the step aimed last tick: grab it now
			boolean wasNoLayer = done == total - 1;
			String[] look = FIT_LOOKS[wasNoLayer ? 0 : done / perLook];
			String view = wasNoLayer ? "side90-nolayer" : (String) FIT_VIEWS[done % perLook][1];
			grab(mc, dir, "fit_" + look[0] + "_" + view + "_1920x1080.png");
		}
		if (fitStep >= total) {
			com.origin.client.client.cosmetics.CosmeticsService.devWear(null);
			com.origin.client.OriginClient.LOGGER.info("[dev-shots] fit shots done");
			OriginFreelookState.active = false;
			mc.options.setModelPart(net.minecraft.world.entity.player.PlayerModelPart.HAT, true);
			mc.options.setModelPart(net.minecraft.world.entity.player.PlayerModelPart.JACKET, true);
			mc.options.broadcastOptions();
			mc.options.fov().set(70);
			mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
			return false;
		}
		boolean noLayer = fitStep == total - 1;
		String[] look = FIT_LOOKS[noLayer ? 0 : fitStep / perLook];
		com.origin.client.client.cosmetics.CosmeticsService.devWear(
				new com.origin.client.client.cosmetics.CosmeticsService.Look(null, look[0], look[1], look[2], null));
		// this era's Options.setModelPart (toggleModelPart on 1.21.1); the server learns it from broadcastOptions
		mc.options.setModelPart(net.minecraft.world.entity.player.PlayerModelPart.HAT, !noLayer);
		mc.options.setModelPart(net.minecraft.world.entity.player.PlayerModelPart.JACKET, !noLayer);
		mc.options.broadcastOptions();
		float rel = noLayer ? 90f : (Float) FIT_VIEWS[fitStep % perLook][0];
		OriginFreelookState.cameraYaw = fitBaseYaw + rel;
		OriginFreelookState.cameraPitch = 12f;
		OriginFreelookState.active = true;
		fitStep++;
		nextAt = System.currentTimeMillis() + (noLayer ? 3000 : 1500);   // the layer change goes via the server
		return true;
	}

	private static boolean press(Minecraft mc, String label) {
		for (var child : mc.screen.children()) {
			if (child instanceof AbstractButton b && b.active && label.equals(b.getMessage().getString())) {
				b.onPress(new KeyEvent(GLFW.GLFW_KEY_ENTER, 0, 0));
				return true;
			}
		}
		return false;
	}

	private static void grab(Minecraft mc, String dir, String name) {
		before = OriginDevShots.pngs();
		renameTries = 0;
		pending = name;
		Screenshot.grab(new File(dir), mc.getMainRenderTarget(), c -> {
		});
	}

	/** Renames the pending capture's new PNG to its shot name; false while it isn't written yet. */
	private static boolean finishGrab(String dir) throws java.io.IOException {
		String fresh = null;
		for (String n : OriginDevShots.pngs()) {
			if (!before.contains(n)) {
				fresh = n;
			}
		}
		if (fresh == null && renameTries++ < 20) {
			return false;
		}
		String name = pending;
		pending = null;
		if (fresh == null) {
			com.origin.client.OriginClient.LOGGER.warn("[dev-shots] no screenshot appeared for {}", name);
			return true;
		}
		File shots = new File(dir, "screenshots");
		Files.move(new File(shots, fresh).toPath(), new File(shots, name).toPath(), StandardCopyOption.REPLACE_EXISTING);
		com.origin.client.OriginClient.LOGGER.info("[dev-shots] captured {}", name);
		return true;
	}

	private static void log(String msg) {
		com.origin.client.OriginClient.LOGGER.info(msg);
	}
}
