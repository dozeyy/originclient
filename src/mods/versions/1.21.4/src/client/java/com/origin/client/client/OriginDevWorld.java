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
 * Saturation off and on, then with Motion Blur on, then your player in third
 * person wearing head, face and back items from every side (with and without
 * the skin's outer layer: the cosmetics fit over it), the pause menu, the
 * Cosmetics window and a demo party (Party HUD, markers, a ping), and quits.
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
				// Motion Blur at full strength: loads its post chain, so run.log shows
				// whether the chain parses and its last-frame target attaches.
				Mods.setOn("motionblur", true);
				Mods.set("motionblur", "amount", 10.0);
				phase = 4;
				nextAt = now + 2500;
				return;
			}
			if (phase == 4) {
				if (mc.screen instanceof PauseScreen) {
					mc.setScreen(null);
				}
				grab(mc, dir, "world_motionblur_1920x1080.png");
				Mods.setOn("motionblur", false);
				phase = 30;
				return;
			}
			if (phase == 30) {
				if (fitStep(mc, dir)) {
					return;
				}
				// The pause menu (its Cosmetics button sits among vanilla's buttons).
				mc.setScreen(new PauseScreen(true));
				phase = 5;
				nextAt = now + 1500;
				return;
			}
			if (phase == 5) {
				grab(mc, dir, "pause_1920x1080.png");
				// The Cosmetics window over the world: its preview is the real player here.
				mc.setScreen(new com.origin.client.client.gui.CosmeticsScreen(null));
				phase = 6;
				nextAt = now + 2500;
				return;
			}
			if (phase == 6) {
				grab(mc, dir, "cosmetics_world_1920x1080.png");
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
				com.origin.client.OriginClient.LOGGER.info("[dev-shots] done — world set in {}", dir);
				phase = 8;
				mc.stop();
			}
		} catch (Throwable t) {
			com.origin.client.OriginClient.LOGGER.warn("[dev-shots] world step {} failed", phase, t);
			phase = 8;
			mc.stop();
		}
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
