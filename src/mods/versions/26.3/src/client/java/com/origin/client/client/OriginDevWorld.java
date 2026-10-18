package com.origin.client.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.resources.language.I18n;
import org.lwjgl.sdl.SDLVideo;

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
 * Saturation off and on, then the pause menu, the Cosmetics window and a demo
 * party (Party HUD, markers, a ping), and quits.
 *
 * PER-VERSION DELTA (1.21.11), the same swaps OriginDevShots makes:
 *   * the window is sized with SDLVideo.SDL_SetWindowSize on Window.handle() (26.3: SDL3);
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
				SDLVideo.SDL_SetWindowSize(mc.getWindow().handle(), 1920, 1080);
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

	private static boolean press(Minecraft mc, String label) {
		for (var child : mc.gui.screen().children()) {
			if (child instanceof AbstractButton b && b.active && label.equals(b.getMessage().getString())) {
				b.onPress(new KeyEvent(InputConstants.KEY_RETURN, 0, 0));
				return true;
			}
		}
		return false;
	}

	private static void grab(Minecraft mc, String dir, String name) {
		before = OriginDevShots.pngs();
		renameTries = 0;
		pending = name;
		Screenshot.grab(new File(dir), mc.gameRenderer.mainRenderTarget(), c -> {
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
