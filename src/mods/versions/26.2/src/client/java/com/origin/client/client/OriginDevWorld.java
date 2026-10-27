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
 *
 * PER-VERSION DELTA (1.21.11), the same swaps OriginDevShots makes:
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
			// Shader terrain "moving camera" window: turn 3 degrees every client tick.
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
				mc.framebufferSizeChanged();
				mc.gui.setScreen(new SelectWorldScreen(new TitleScreen()));
				phase = 1;
				return;
			}
			if (phase == 1) {
				if (mc.level == null) {
					// Select World / Create World: press "Create New World" wherever it is.
					if (mc.gui.screen() != null && press(mc, I18n.get("selectWorld.create"))) {
						log("[dev-shots] pressed Create New World on " + mc.gui.screen().getClass().getSimpleName());
					}
					return;
				}
				if (mc.gui.screen() instanceof PauseScreen) {
					mc.gui.setScreen(null);   // lost focus under Xvfb
				}
				if (mc.player == null || mc.gui.screen() != null) {
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
				if (mc.gui.screen() instanceof PauseScreen) {
					mc.gui.setScreen(null);
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
				if (phase < 45 ? shadowLimitStep(mc, dir) : shaderTerrainStep(mc, dir)) {
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
				mc.gui.setScreen(new PauseScreen(true));
				phase = 4;
				nextAt = now + 1500;
				return;
			}
			if (phase == 4) {
				grab(mc, dir, "pause_1920x1080.png");
				// The Cosmetics window over the world: its preview is the real player here.
				mc.gui.setScreen(new com.origin.client.client.gui.CosmeticsScreen(null));
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
				mc.gui.setScreen(null);
				com.origin.client.client.social.SocialDemo.install(true);
				phase = 7;
				nextAt = now + 2500;
				return;
			}
			if (phase == 7) {
				if (mc.gui.screen() instanceof PauseScreen) {
					mc.gui.setScreen(null);
				}
				grab(mc, dir, "party_world_1920x1080.png");
				phase = 8;
				return;
			}
			if (phase == 8) {
				// 26.x extra: the world-geometry overlays (submitted from
				// COLLECT_SUBMITS) -- chunk borders + the Origin block outline/overlay,
				// looking down at the ground so a block is targeted.
				com.origin.client.client.social.SocialDemo.clear();
				Mods.setOn("chunkborders", true);
				Mods.setOn("blockoverlay", true);
				Mods.set("blockoverlay", "outline", true);
				Mods.set("blockoverlay", "overlay", true);
				mc.player.setXRot(55f);
				phase = 9;
				nextAt = now + 2500;
				return;
			}
			if (phase == 9) {
				if (mc.gui.screen() instanceof PauseScreen) {
					mc.gui.setScreen(null);
				}
				grab(mc, dir, "overlays_world_1920x1080.png");
				// The Right Shift HUD editor over the world: its widget previews (Cooldowns'
				// ender pearl + shield, Armor's diamond set) must draw real item icons in a
				// world too, not only the title screen's display stacks (gui/ItemStacks).
				mc.player.setXRot(0f);
				mc.gui.setScreen(new com.origin.client.client.hud.HudEditorScreen(true));
				phase = 12;
				nextAt = now + 2500;
				return;
			}
			if (phase == 12) {
				grab(mc, dir, "rshift_world_1920x1080.png");
				phase = 10;
				return;
			}
			if (phase == 10) {
				com.origin.client.OriginClient.LOGGER.info("[dev-shots] done — world set in {}", dir);
				phase = 11;
				mc.stop();
			}
		} catch (Throwable t) {
			com.origin.client.OriginClient.LOGGER.warn("[dev-shots] world step {} failed", phase, t);
			phase = 11;
			mc.stop();
		}
	}

	// ======== Shader speed-up measurement (only with a shader pack active: dev-shots shaderpack=true) ========
	// PER-VERSION (26.2): screens via mc.gui.screen()/setScreen, the HUD via mc.gui.hud.toggle(), gamerules
	// advance_time / advance_weather, Iris's shadow depth texture id via GlTexture.glId(). There is no Smart
	// Terrain toggle here (render/ShadowTerrainStats says why), so its phases measure the main-pass chunk
	// list rebuilds that toggle would have saved: they must already be ~0 while standing still.

	// ---- Limit Far Shadows ----
	// A fixed test scene -- 8 still pigs and 8 chests on each ring at 8/24/40/56/72 blocks -- then the
	// shadow-pass caster counts per frame with the limit OFF, and ON at 32 blocks. CPU-side counts of
	// what Iris asked to draw into the shadow map; no timings.
	private static final int[] RINGS = {8, 24, 40, 56, 72};
	private static long snapFrames, snapSeenE, snapSkipE, snapSeenBE, snapSkipBE;
	private static String offResult;

	private static void run(net.minecraft.server.MinecraftServer server, String... cmds) {
		server.execute(() -> {
			var src = server.createCommandSourceStack().withSuppressedOutput();
			for (String c : cmds) {
				server.getCommands().performPrefixedCommand(src, c);
			}
		});
	}

	private static boolean shadowLimitStep(Minecraft mc, String dir) {
		if (mc.gui.screen() instanceof PauseScreen) {
			mc.gui.setScreen(null);
		}
		if (phase == 40) {
			var server = mc.getSingleplayerServer();
			if (server == null || mc.player == null) {
				return false;
			}
			net.minecraft.core.BlockPos p = mc.player.blockPosition();
			java.util.List<String> cmds = new java.util.ArrayList<>();
			for (int r : RINGS) {
				for (int i = 0; i < 8; i++) {
					double a = Math.PI * 2 * i / 8;
					int x = p.getX() + (int) Math.round(Math.cos(a) * r);
					int z = p.getZ() + (int) Math.round(Math.sin(a) * r);
					cmds.add("setblock " + x + " " + (p.getY() + 1) + " " + z + " minecraft:chest");
					cmds.add("summon minecraft:pig " + x + " " + (p.getY() + 3) + " " + z
							+ " {NoAI:1b,Invulnerable:1b,Silent:1b,Tags:[\"origin_shadowtest\"]}");
				}
			}
			run(server, cmds.toArray(new String[0]));
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
			log("[shadow-limit] F3: " + com.origin.client.client.render.ShadowCasterLimit.debugLine());
			Mods.set(Mods.PERFORMANCE_ID, com.origin.client.client.render.ShadowCasterLimit.KEY_ON, false);
			var server = mc.getSingleplayerServer();
			if (server != null) {
				run(server, "kill @e[tag=origin_shadowtest]");
			}
			phase = 45;   // next: shader terrain (main-list rebuilds), Off-Map, Half-Rate
			return true;
		}
		return false;
	}

	// ---- Shader terrain: main-pass chunk list rebuilds (why no Smart Terrain toggle), then Off-Map, Half-Rate ----
	// Time, weather, ticking and clouds frozen, HUD hidden, camera fixed, render distance 3 (CI renders at
	// a few fps; a 12-chunk world is still loading minutes in).
	private static boolean terrainSpin;
	private static long stFrames, stOutOfGraph, stReadTree, stAsync, stShadowWalks, stShadowNs;
	private static net.minecraft.core.BlockPos stEdit;
	private static net.minecraft.client.CloudStatus stClouds;
	private static int stRenderDistance = -1;
	private static boolean stHidHud;
	private static String stStill, stOnEdit, stMove;

	private static boolean shaderTerrainStep(Minecraft mc, String dir) {
		if (mc.gui.screen() instanceof PauseScreen) {
			mc.gui.setScreen(null);
		}
		var server = mc.getSingleplayerServer();
		if (server == null || mc.player == null) {
			return false;
		}
		switch (phase) {
			case 45 -> {
				run(server, "gamerule advance_time false", "gamerule advance_weather false",
						"time set 6000", "weather clear", "tick freeze");
				stClouds = mc.options.cloudStatus().get();
				mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
				if (!mc.gui.hud.isHidden()) {
					mc.gui.hud.toggle();
					stHidHud = true;
				}
				mc.player.setXRot(25f);
				stRenderDistance = mc.options.renderDistance().get();
				mc.options.renderDistance().set(3);
				nextAt = System.currentTimeMillis() + 25000;
			}
			case 46 -> {
				grab(mc, dir, "terrain_still_1920x1080.png");
				stSnap();
				nextAt = System.currentTimeMillis() + 4000;
			}
			case 47 -> {
				stStill = stMeasured();
				// A gold block 4 blocks along the view: Sodium schedules a rebuild, so the main list must
				// update (counted) and the block must show up.
				net.minecraft.world.phys.Vec3 look = mc.player.getLookAngle();
				stEdit = net.minecraft.core.BlockPos.containing(mc.player.getEyePosition().add(look.scale(4)));
				net.minecraft.core.BlockPos at = stEdit;
				run(server, "setblock " + at.getX() + " " + at.getY() + " " + at.getZ() + " minecraft:gold_block");
				stSnap();
				nextAt = System.currentTimeMillis() + 3000;
			}
			case 48 -> {
				stOnEdit = stMeasured();
				grab(mc, dir, "terrain_edit_1920x1080.png");
				terrainSpin = true;
				stSnap();
				nextAt = System.currentTimeMillis() + 4000;
			}
			case 49 -> {
				stMove = stMeasured();
				terrainSpin = false;
				log("[smart-terrain] no toggle on 26.2 (Sodium 0.9.2 + Iris 1.11.4 keep the main list across the shadow pass)");
				log("[smart-terrain] still: " + stStill);
				log("[smart-terrain] block edit: " + stOnEdit);
				log("[smart-terrain] turning: " + stMove);
				log("[smart-terrain] F3: " + com.origin.client.client.render.ShadowTerrainStats.debugLine());
				net.minecraft.core.BlockPos at = stEdit;
				if (at != null) {
					run(server, "setblock " + at.getX() + " " + at.getY() + " " + at.getZ() + " minecraft:air");
				}
				omScene(mc, server);
				phase = 52;   // phase++ below -> 53, Off-Map Shadows
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
				run(server, "gamemode creative @a");
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
				run(server, "setblock " + at.getX() + " " + at.getY() + " " + at.getZ() + " minecraft:gold_block");
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
				if (at != null) {
					run(server, "setblock " + at.getX() + " " + at.getY() + " " + at.getZ() + " minecraft:air");
				}
				run(server, "kill @e[tag=origin_omtest]", "tick unfreeze");
				if (stClouds != null) {
					mc.options.cloudStatus().set(stClouds);
				}
				if (stHidHud && mc.gui.hud.isHidden()) {
					mc.gui.hud.toggle();
				}
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

	private static void stSnap() {
		stFrames = com.origin.client.client.hud.FrameStats.frames();
		stOutOfGraph = com.origin.client.client.render.ShadowTerrainStats.mainOutOfGraph;
		stReadTree = com.origin.client.client.render.ShadowTerrainStats.mainReadTree;
		stAsync = com.origin.client.client.render.ShadowTerrainStats.mainAsync;
		stShadowWalks = com.origin.client.client.render.ShadowTerrainStats.shadowWalks;
		stShadowNs = com.origin.client.client.render.ShadowTerrainStats.shadowWalkNanos;
	}

	/** Since stSnap(): frames, main-pass list rebuilds (sync walks + reads from a cull tree), async culls, shadow walks. */
	private static String stMeasured() {
		long f = com.origin.client.client.hud.FrameStats.frames() - stFrames;
		long og = com.origin.client.client.render.ShadowTerrainStats.mainOutOfGraph - stOutOfGraph;
		long rt = com.origin.client.client.render.ShadowTerrainStats.mainReadTree - stReadTree;
		long as = com.origin.client.client.render.ShadowTerrainStats.mainAsync - stAsync;
		long sw = com.origin.client.client.render.ShadowTerrainStats.shadowWalks - stShadowWalks;
		long sn = com.origin.client.client.render.ShadowTerrainStats.shadowWalkNanos - stShadowNs;
		return String.format(java.util.Locale.ROOT,
				"frames=%d mainSyncWalks=%d mainListReads=%d mainRebuilds/frame=%.3f asyncCulls=%d shadowWalks=%d shadowWalkMs=%.3f",
				f, og, rt, f == 0 ? 0.0 : (og + rt) / (double) f, as, sw, sw == 0 ? 0.0 : sn / 1e6 / sw);
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
		hrShadowWalks = com.origin.client.client.render.ShadowTerrainStats.shadowWalks;
		hrShadowNs = com.origin.client.client.render.ShadowTerrainStats.shadowWalkNanos;
	}

	/**
	 * Since hrSnap(): frames, shadow maps kept / drawn by the feature (its counters only move while it is
	 * on), why each draw happened, and the shadow-pass chunk walks that really ran. CPU-side counts only.
	 */
	private static String hrMeasured() {
		long f = com.origin.client.client.hud.FrameStats.frames() - hrFrames;
		long sw = com.origin.client.client.render.ShadowTerrainStats.shadowWalks - hrShadowWalks;
		long sn = com.origin.client.client.render.ShadowTerrainStats.shadowWalkNanos - hrShadowNs;
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

	// ---- Off-Map Shadows measurement ----
	private static long omFrames, omWalks, omDrawnS, omOffS, omSeenE, omOffE, omSkipE, omSeenBE, omShadowWalks, omShadowNs,
			omSkipBoxes, omSplitBoxes;
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
		omSkipBoxes = com.origin.client.client.render.OffMapShadows.devSkipBoxes;
		omSplitBoxes = com.origin.client.client.render.OffMapShadows.devSplitBoxes;
		omSeenBE = com.origin.client.client.render.ShadowCasterLimit.totalSeenBE;
		omShadowWalks = com.origin.client.client.render.ShadowTerrainStats.shadowWalks;
		omShadowNs = com.origin.client.client.render.ShadowTerrainStats.shadowWalkNanos;
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
		long sb = com.origin.client.client.render.OffMapShadows.devSkipBoxes - omSkipBoxes;
		long sp = com.origin.client.client.render.OffMapShadows.devSplitBoxes - omSplitBoxes;
		long be = com.origin.client.client.render.ShadowCasterLimit.totalSeenBE - omSeenBE;
		long sw = com.origin.client.client.render.ShadowTerrainStats.shadowWalks - omShadowWalks;
		long sn = com.origin.client.client.render.ShadowTerrainStats.shadowWalkNanos - omShadowNs;
		double fw = Math.max(w, 1), ff = Math.max(f, 1);
		return String.format(java.util.Locale.ROOT,
				"frames=%d shadowWalks=%d halfPlane=%.1f sectionsDrawn/walk=%.1f offMapDrawn/walk=%.1f boxesSkipped/walk=%.1f boxesSplit/walk=%.1f"
						+ " entitiesKept/frame=%.2f offMapKept/frame=%.2f skipped/frame=%.2f drawn/frame=%.2f blockEntities/frame=%.2f shadowWalkMs=%.3f",
				f, w, com.origin.client.client.render.OffMapShadows.devHalfPlane(), ds / fw, os / fw, sb / fw, sp / fw,
				se / ff, oe / ff, ke / ff, (se - ke) / ff, be / ff, sw == 0 ? 0.0 : sn / 1e6 / sw);
	}

	/**
	 * Dev only: Iris's shadow depth texture (shadowtex0) read back as floats; null if anything is missing.
	 * PER-VERSION (26.2): ShadowRenderTargets.getDepthTexture() is a blaze3d GpuTexture; its GL name is
	 * GlTexture.glId() (looked up on GlTexture itself, the public class that declares it).
	 */
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
			int id = (int) Class.forName("com.mojang.blaze3d.opengl.GlTexture").getMethod("glId").invoke(depth);
			int prev = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D);
			org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, id);
			int w = org.lwjgl.opengl.GL11.glGetTexLevelParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL11.GL_TEXTURE_WIDTH);
			int h = org.lwjgl.opengl.GL11.glGetTexLevelParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL11.GL_TEXTURE_HEIGHT);
			// PER-VERSION (26.x): blaze3d's copyTextureToBuffer (every Screenshot.grab) leaves GL_PACK_ROW_LENGTH
			// at the screenshot's width and may leave a pixel-pack buffer bound. A tightly packed w*h read
			// with a stale 1920 row length writes past the array and corrupted the native heap ("double free
			// or corruption", dev-shots 2026-10-20). Read with clean pack state, then put it back.
			int pbo = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER_BINDING);
			int rowLen = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_PACK_ROW_LENGTH);
			int skipRows = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_PACK_SKIP_ROWS);
			int skipPix = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_PACK_SKIP_PIXELS);
			int align = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_PACK_ALIGNMENT);
			org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER, 0);
			org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_PACK_ROW_LENGTH, 0);
			org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_PACK_SKIP_ROWS, 0);
			org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_PACK_SKIP_PIXELS, 0);
			org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_PACK_ALIGNMENT, 4);
			float[] out = w > 0 && h > 0 ? new float[w * h] : null;
			if (out != null) {
				org.lwjgl.opengl.GL11.glGetTexImage(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL11.GL_DEPTH_COMPONENT,
						org.lwjgl.opengl.GL11.GL_FLOAT, out);
			}
			org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_PACK_ROW_LENGTH, rowLen);
			org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_PACK_SKIP_ROWS, skipRows);
			org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_PACK_SKIP_PIXELS, skipPix);
			org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_PACK_ALIGNMENT, align);
			org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER, pbo);
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
		if (mc.gui.screen() instanceof PauseScreen) {
			mc.gui.setScreen(null);
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
			showOuterLayer(mc, true);
			mc.options.fov().set(70);
			mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
			return false;
		}
		boolean noLayer = fitStep == total - 1;
		String[] look = FIT_LOOKS[noLayer ? 0 : fitStep / perLook];
		com.origin.client.client.cosmetics.CosmeticsService.devWear(
				new com.origin.client.client.cosmetics.CosmeticsService.Look(null, look[0], look[1], look[2], null));
		showOuterLayer(mc, !noLayer);
		float rel = noLayer ? 90f : (Float) FIT_VIEWS[fitStep % perLook][0];
		OriginFreelookState.cameraYaw = fitBaseYaw + rel;
		OriginFreelookState.cameraPitch = 12f;
		OriginFreelookState.active = true;
		fitStep++;
		nextAt = System.currentTimeMillis() + (noLayer ? 3000 : 1500);   // the layer change goes via the server
		return true;
	}

	/**
	 * Shows / hides the skin's hat + jacket layers. 26.x: Options has no toggleModelPart (1.21.1's
	 * set + broadcast in one); setModelPart then broadcastOptions tells the server, which syncs it back.
	 */
	private static void showOuterLayer(Minecraft mc, boolean on) {
		mc.options.setModelPart(net.minecraft.world.entity.player.PlayerModelPart.HAT, on);
		mc.options.setModelPart(net.minecraft.world.entity.player.PlayerModelPart.JACKET, on);
		mc.options.broadcastOptions();
	}

	private static boolean press(Minecraft mc, String label) {
		for (var child : mc.gui.screen().children()) {
			if (child instanceof AbstractButton b && b.active && label.equals(b.getMessage().getString())) {
				b.onPress(new KeyEvent(GLFW.GLFW_KEY_ENTER, 0, 0));
				return true;
			}
		}
		return false;
	}

	// ---- In-world smoke step (boot-smoke + the dev-shots design set) ----
	// The screen sets never enter a world, so a mod that boots to the title and then breaks the moment a
	// world loads (HUD, mod menu over a world, Save and Quit) passed every gate. This is the one in-world
	// route both gates share, turned on per module by "inworld": true in src/mods/live-modules.json:
	//   boot-smoke.yml -> smoke.py --inworld DIR -> env ORIGIN_SMOKE_WORLD=DIR (OriginClientMod's end-tick hook)
	//   dev-shots.yml design set -> env ORIGIN_INWORLD=1 (OriginDevShots.finish hands over to it)
	// Steps, each with a deadline: make (or reopen) a flat test world straight through WorldOpenFlows, wait
	// until Origin's HUD has really drawn (HudElements.passes climbing), shoot it, open the Mod Menu over the
	// world, shoot it, Save and Quit back to the title. One verdict line, gated by smoke.py and check_shots.py:
	//   [origin-smoke] in-world: PASS      or      [origin-smoke] in-world: FAIL <reason>
	// A throw or a missed deadline is a FAIL, never a hang. It always ends with onEnd + mc.stop().
	// PER-VERSION (26.2, javap): screens via mc.gui.screen() / mc.gui.setScreen; LevelSettings takes a
	// DifficultySettings record and no GameRules (26.x); the flat preset through HolderLookup.Provider
	// lookupOrThrow/getOrThrow; Save and Quit is vanilla's own Minecraft.disconnectFromWorld (what
	// PauseScreen's button calls: level disconnect, saving screen for the local server, then the title).
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
			// 26.x Screenshot.grab writes a timestamped PNG on a worker thread: no step moves on (or quits)
			// until the last capture is on disk under its shot name (finishGrab), or smoke.py finds it missing.
			if (pending != null && !finishGrab(dir)) {
				iwAt = now + 100;
				return;
			}
			var screen = mc.gui.screen();
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
						flows.openWorld(SMOKE_WORLD, () -> mc.gui.setScreen(new TitleScreen()));
					} else {
						var settings = new net.minecraft.world.level.LevelSettings("Origin Smoke",
								net.minecraft.world.level.GameType.SURVIVAL,
								new net.minecraft.world.level.LevelSettings.DifficultySettings(net.minecraft.world.Difficulty.PEACEFUL, false, false),
								true, net.minecraft.world.level.WorldDataConfiguration.DEFAULT);
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
								+ (screen == null ? "none" : screen.getClass().getSimpleName()) + ", player: "
								+ (mc.player != null) + ", HUD passes: " + com.origin.client.client.hud.HudElements.passes + ")", onEnd);
						return;
					}
					if (screen instanceof PauseScreen) {
						mc.gui.setScreen(null);   // lost focus under Xvfb
					}
					if (mc.level == null || mc.player == null || screen != null) {
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
					mc.gui.setScreen(new com.origin.client.client.gui.OriginModMenuScreen());
					iw = 3;
					iwAt = now + 2500;
				}
				case 3 -> {
					if (!(screen instanceof com.origin.client.client.gui.OriginModMenuScreen)) {
						inWorldEnd(mc, false, "mod menu did not open over the world (screen: "
								+ (screen == null ? "none" : screen.getClass().getSimpleName()) + ")", onEnd);
						return;
					}
					grab(mc, dir, "inworld_modmenu_" + mc.getWindow().getWidth() + "x" + mc.getWindow().getHeight() + ".png");
					log("[origin-smoke] in-world: mod menu open");
					iw = 4;
					iwAt = now + 1500;
				}
				case 4 -> {
					// Vanilla's Save and Quit (PauseScreen's button -> disconnectFromWorld), which ends on the title.
					mc.gui.setScreen(null);
					mc.disconnectFromWorld(net.minecraft.client.multiplayer.ClientLevel.DEFAULT_QUIT_MESSAGE);
					iw = 5;
					iwFrom = now;
				}
				case 5 -> {
					if (mc.level == null && mc.getSingleplayerServer() == null && screen instanceof TitleScreen) {
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

	private static void grab(Minecraft mc, String dir, String name) {
		before = pngsIn(dir);
		renameTries = 0;
		pending = name;
		Screenshot.grab(new File(dir), mc.gameRenderer.mainRenderTarget(), c -> {
		});
	}

	/** The PNGs in {@code dir}/screenshots (the world set passes OriginDevShots' DIR; boot-smoke's in-world step its own dir). */
	private static Set<String> pngsIn(String dir) {
		Set<String> out = new HashSet<>();
		String[] names = new File(dir, "screenshots").list();
		if (names != null) {
			for (String n : names) {
				if (n.endsWith(".png")) {
					out.add(n);
				}
			}
		}
		return out;
	}

	/** Renames the pending capture's new PNG to its shot name; false while it isn't written yet. */
	private static boolean finishGrab(String dir) throws java.io.IOException {
		String fresh = null;
		for (String n : pngsIn(dir)) {
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
