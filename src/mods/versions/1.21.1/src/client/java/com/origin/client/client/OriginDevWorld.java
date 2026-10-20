package com.origin.client.client;

import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.resources.language.I18n;

import java.io.File;

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
 * party (Party HUD, markers, a ping), and quits. With a shader pack active it
 * first measures Limit Far Shadows on a fixed test scene (shadowLimitStep).
 */
final class OriginDevWorld {
	private OriginDevWorld() {
	}

	private static int phase = 0;
	private static long nextAt = 0;
	private static long loadedAt = 0;

	static void tick(Minecraft mc, String dir) {
		long now = System.currentTimeMillis();
		if (now < nextAt) {
			return;
		}
		nextAt = now + 1000;
		try {
			if (phase == 0) {
				// Title is up (OriginDevShots waited for it): open Select World.
				mc.getWindow().setWindowed(1920, 1080);
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
				// With a shader pack on (dev-shots shaderpack=true), measure Limit Far Shadows first.
				phase = com.origin.client.client.shaders.IrisBridge.currentPack() != null ? 40 : 30;
				return;
			}
			if (phase >= 40 && phase < 50) {
				if (shadowLimitStep(mc, dir)) {
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
				// Friends & Party: a demo party standing in front of the camera, with a ping
				mc.setScreen(null);
				com.origin.client.client.social.SocialDemo.install(true);
				phase = 6;
				nextAt = now + 2500;
				return;
			}
			if (phase == 6) {
				if (mc.screen instanceof PauseScreen) {
					mc.setScreen(null);
				}
				grab(mc, dir, "party_world_1920x1080.png");
				com.origin.client.OriginClient.LOGGER.info("[dev-shots] done — world set in {}", dir);
				phase = 7;
				mc.stop();
			}
		} catch (Throwable t) {
			com.origin.client.OriginClient.LOGGER.warn("[dev-shots] world step {} failed", phase, t);
			phase = 7;
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
			phase = 45;
			return false;
		}
		return false;
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
			mc.options.toggleModelPart(net.minecraft.world.entity.player.PlayerModelPart.HAT, true);
			mc.options.toggleModelPart(net.minecraft.world.entity.player.PlayerModelPart.JACKET, true);
			mc.options.fov().set(70);
			mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
			return false;
		}
		boolean noLayer = fitStep == total - 1;
		String[] look = FIT_LOOKS[noLayer ? 0 : fitStep / perLook];
		com.origin.client.client.cosmetics.CosmeticsService.devWear(
				new com.origin.client.client.cosmetics.CosmeticsService.Look(null, look[0], look[1], look[2], null));
		mc.options.toggleModelPart(net.minecraft.world.entity.player.PlayerModelPart.HAT, !noLayer);
		mc.options.toggleModelPart(net.minecraft.world.entity.player.PlayerModelPart.JACKET, !noLayer);
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
				b.onPress();
				return true;
			}
		}
		return false;
	}

	private static void grab(Minecraft mc, String dir, String name) {
		Screenshot.grab(new File(dir), name, mc.getMainRenderTarget(), c -> {
		});
		com.origin.client.OriginClient.LOGGER.info("[dev-shots] captured {}", name);
	}

	private static void log(String msg) {
		com.origin.client.OriginClient.LOGGER.info(msg);
	}
}
