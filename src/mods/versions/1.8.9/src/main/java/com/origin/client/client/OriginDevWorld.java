package com.origin.client.client;

import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.entity.player.EnumPlayerModelParts;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;

/**
 * DEV-ONLY in-world captures for dev-shots.yml (ORIGIN_SHOTS_SET=world): the
 * design set never enters a world, so world-render bugs (Color Saturation's
 * black world / white vignette, 2026-10-12) were invisible to it. This creates
 * a fresh world, waits for it to draw, then captures it with Color Saturation
 * off and on, then your player in third person wearing head, face and back
 * items from every side (with and without the skin's outer layer: the
 * cosmetics fit over it), the pause menu, the Cosmetics window and a demo party
 * (Party HUD, markers, a ping), and quits. Same shots and file names as 1.21.1.
 *
 * 1.8.9: the world is created straight through Minecraft.launchIntegratedServer
 * (what GuiCreateWorld's button does) instead of pressing the Create buttons by
 * their labels -- one call, no dependence on how the menus area restyles those
 * screens. Window resize / capture: OriginDevShots' LWJGL 2 helpers. Screens of
 * other areas (Cosmetics, the social demo) by class name, so a missing area
 * skips its shot instead of failing the run.
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
				// Title is up (OriginDevShots waited for it): make a fresh world.
				OriginDevShots.resizeWindow(mc, 1920, 1080);
				// 1.8.9 re-opens the pause menu EVERY tick the window lacks focus (always,
				// under Xvfb), so closing it below can't win -- switch the behaviour off
				// for this dev run (the harness only runs with ORIGIN_SHOTS set).
				mc.gameSettings.pauseOnLostFocus = false;
				String folder = "OriginDevWorld-" + now;
				WorldSettings settings = new WorldSettings(1234567L, WorldSettings.GameType.CREATIVE,
						true, false, WorldType.DEFAULT);
				log("[dev-shots] creating world " + folder);
				phase = 1;
				mc.launchIntegratedServer(folder, "Origin Dev World", settings);
				return;
			}
			if (phase == 1) {
				if (mc.theWorld == null) {
					return;
				}
				if (mc.currentScreen instanceof GuiIngameMenu) {
					mc.displayGuiScreen(null);   // lost focus under Xvfb
				}
				if (mc.thePlayer == null || mc.currentScreen != null) {
					return;
				}
				if (loadedAt == 0) {
					loadedAt = now;
					return;
				}
				if (now - loadedAt < 20000) {
					return;   // let the chunks around spawn build and draw
				}
				OriginDevShots.grab(mc, dir, "world_off_1920x1080.png");
				Mods.setOn("colorsaturation", true);
				Mods.set("colorsaturation", "saturation", 1.8);
				phase = 2;
				nextAt = now + 2500;
				return;
			}
			if (phase == 2) {
				if (mc.currentScreen instanceof GuiIngameMenu) {
					mc.displayGuiScreen(null);
				}
				OriginDevShots.grab(mc, dir, "world_sat_1920x1080.png");
				Mods.set("colorsaturation", "saturation", 1.0);
				Mods.set("colorsaturation", "brightness", 1.4);
				phase = 3;
				nextAt = now + 2500;
				return;
			}
			if (phase == 3) {
				OriginDevShots.grab(mc, dir, "world_bright_1920x1080.png");
				Mods.setOn("colorsaturation", false);
				phase = 30;
				return;
			}
			if (phase == 30) {
				if (fitStep(mc, dir)) {
					return;
				}
				// The pause menu (its Cosmetics button sits among vanilla's buttons).
				mc.displayGuiScreen(new GuiIngameMenu());
				phase = 4;
				nextAt = now + 1500;
				return;
			}
			if (phase == 4) {
				OriginDevShots.grab(mc, dir, "pause_1920x1080.png");
				// The Cosmetics window over the world: its preview is the real player here.
				net.minecraft.client.gui.GuiScreen cos =
						OriginDevShots.make("com.origin.client.client.gui.CosmeticsScreen", (Object) null);
				mc.displayGuiScreen(cos);
				phase = cos == null ? 6 : 5;
				nextAt = now + 2500;
				if (cos == null) {
					log("[dev-shots] cosmetics_world skipped: CosmeticsScreen is not available");
					mc.displayGuiScreen(null);
					OriginDevShots.callStatic("com.origin.client.client.social.SocialDemo", "install", true);
				}
				return;
			}
			if (phase == 5) {
				OriginDevShots.grab(mc, dir, "cosmetics_world_1920x1080.png");
				// Friends & Party: a demo party standing in front of the camera, with a ping
				mc.displayGuiScreen(null);
				OriginDevShots.callStatic("com.origin.client.client.social.SocialDemo", "install", true);
				phase = 6;
				nextAt = now + 2500;
				return;
			}
			if (phase == 6) {
				if (mc.currentScreen instanceof GuiIngameMenu) {
					mc.displayGuiScreen(null);
				}
				OriginDevShots.grab(mc, dir, "party_world_1920x1080.png");
				com.origin.client.OriginClient.LOGGER.info("[dev-shots] done — world set in {}", dir);
				phase = 7;
				mc.shutdown();
			}
		} catch (Throwable t) {
			com.origin.client.OriginClient.LOGGER.warn("[dev-shots] world step {} failed", phase, t);
			phase = 7;
			mc.shutdown();
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
		if (mc.currentScreen instanceof GuiIngameMenu) {
			mc.displayGuiScreen(null);
		}
		if (fitStep == -1) {
			// 1.8.9: rotationYaw / rotationPitch, fovSetting and thirdPersonView (1 = behind) are
			// 1.21.1's getYRot / setXRot, fov() and CameraType.THIRD_PERSON_BACK
			fitBaseYaw = mc.thePlayer.rotationYaw;
			mc.thePlayer.rotationPitch = 0f;
			mc.gameSettings.fovSetting = 30f;
			mc.gameSettings.thirdPersonView = 1;
			Mods.setOn("freelook", true);
			Mods.set("freelook", "toggle", true);
			fitStep = 0;
		} else {
			int done = fitStep - 1;   // the step aimed last tick: grab it now
			boolean wasNoLayer = done == total - 1;
			String[] look = FIT_LOOKS[wasNoLayer ? 0 : done / perLook];
			String view = wasNoLayer ? "side90-nolayer" : (String) FIT_VIEWS[done % perLook][1];
			OriginDevShots.grab(mc, dir, "fit_" + look[0] + "_" + view + "_1920x1080.png");
		}
		if (fitStep >= total) {
			com.origin.client.client.cosmetics.CosmeticsService.devWear(null);
			log("[dev-shots] fit shots done");
			OriginFreelookState.active = false;
			mc.gameSettings.setModelPartEnabled(EnumPlayerModelParts.HAT, true);
			mc.gameSettings.setModelPartEnabled(EnumPlayerModelParts.JACKET, true);
			mc.gameSettings.fovSetting = 70f;
			mc.gameSettings.thirdPersonView = 0;
			return false;
		}
		boolean noLayer = fitStep == total - 1;
		String[] look = FIT_LOOKS[noLayer ? 0 : fitStep / perLook];
		com.origin.client.client.cosmetics.CosmeticsService.devWear(
				new com.origin.client.client.cosmetics.CosmeticsService.Look(null, look[0], look[1], look[2], null));
		// 1.8.9: GameSettings.setModelPartEnabled is 1.21.1's Options.toggleModelPart (it also tells the server)
		mc.gameSettings.setModelPartEnabled(EnumPlayerModelParts.HAT, !noLayer);
		mc.gameSettings.setModelPartEnabled(EnumPlayerModelParts.JACKET, !noLayer);
		float rel = noLayer ? 90f : (Float) FIT_VIEWS[fitStep % perLook][0];
		OriginFreelookState.cameraYaw = fitBaseYaw + rel;
		OriginFreelookState.cameraPitch = 12f;
		OriginFreelookState.active = true;
		fitStep++;
		nextAt = System.currentTimeMillis() + (noLayer ? 3000 : 1500);   // the layer change goes via the server
		return true;
	}

	private static void log(String msg) {
		com.origin.client.OriginClient.LOGGER.info(msg);
	}
}
