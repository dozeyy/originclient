package com.origin.client.client;

import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;

/**
 * DEV-ONLY in-world captures for dev-shots.yml (ORIGIN_SHOTS_SET=world): the
 * design set never enters a world, so world-render bugs (Color Saturation's
 * black world / white vignette, 2026-10-12) were invisible to it. This creates
 * a fresh world, waits for it to draw, then captures it with Color Saturation
 * off and on, then the pause menu, the Cosmetics window and a demo party
 * (Party HUD, markers, a ping), and quits. Same shots and file names as 1.16.5.
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

	private static void log(String msg) {
		com.origin.client.OriginClient.LOGGER.info(msg);
	}
}
