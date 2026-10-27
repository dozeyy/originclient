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

	// ---- In-world smoke step (boot-smoke + the dev-shots design set), 1.21.1's OriginDevWorld.inWorldTick ----
	// The screen sets never enter a world, so a mod that boots to the title and then breaks the moment a
	// world loads (HUD, mod menu over a world, Save and Quit) passed every gate. This is the one in-world
	// route both gates share, turned on per module by "inworld": true in src/mods/live-modules.json:
	//   boot-smoke.yml -> smoke.py --inworld DIR -> env ORIGIN_SMOKE_WORLD=DIR (OriginClientMod.onEndTick)
	//   dev-shots.yml design set -> env ORIGIN_INWORLD=1 (OriginDevShots.finish hands over to it)
	// Steps, each with a deadline: make (or reopen) a flat test world straight through
	// Minecraft.launchIntegratedServer (what GuiCreateWorld / GuiSelectWorld do), wait until Origin's HUD has
	// really drawn (HudElements.passes climbing), shoot it, open the Mod Menu over the world, shoot it, Save
	// and Quit back to the title. One verdict line, gated by smoke.py and check_shots.py:
	//   [origin-smoke] in-world: PASS      or      [origin-smoke] in-world: FAIL <reason>
	// A throw or a missed deadline is a FAIL, never a hang. It always ends with onEnd + mc.shutdown().
	// 1.8.9: Save and Quit is GuiIngameMenu.actionPerformed's button 1 (sendQuittingDisconnectingPacket,
	// loadWorld(null), GuiMainMenu); Forge's loadWorld(null) waits in a sleep loop until the integrated
	// server reports isServerStopped (javap), so the world is saved when it returns.
	static final String SMOKE_WORLD = "origin-smoke";
	private static final long IW_LOAD_MS = 240000, IW_QUIT_MS = 90000;
	private static int iw = 0;
	private static long iwAt = 0, iwFrom = 0, iwHudFrom = -1, iwHudAt = 0;
	private static Boolean iwLimitUnfocused;
	private static Boolean iwPauseOnLostFocus;
	private static net.minecraft.world.EnumDifficulty iwDifficulty;
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
				case 0: {
					boolean exists = mc.getSaveLoader().canLoadWorld(SMOKE_WORLD);
					log("[origin-smoke] in-world: starting (" + (exists ? "reopening" : "creating")
							+ " flat world '" + SMOKE_WORLD + "')");
					// Xvfb has no window manager, so the window is never focused: with Limit Unfocused FPS on,
					// Origin's HUD skips itself (FrameWatch.idle), and 1.8.9 re-opens the pause menu every tick
					// the window lacks focus. Both off for the step, restored at the end. Peaceful: a flat world's
					// slime chunks would otherwise spawn slimes onto the player (y 4 is under slime height).
					iwLimitUnfocused = Mods.bool(Mods.PERFORMANCE_ID, "limitUnfocusedFps");
					Mods.set(Mods.PERFORMANCE_ID, "limitUnfocusedFps", false);
					iwPauseOnLostFocus = mc.gameSettings.pauseOnLostFocus;
					mc.gameSettings.pauseOnLostFocus = false;
					iwDifficulty = mc.gameSettings.difficulty;
					mc.gameSettings.difficulty = net.minecraft.world.EnumDifficulty.PEACEFUL;
					if (mc.displayWidth < 1280) {
						OriginDevShots.resizeWindow(mc, 1280, 720);   // boot-smoke's 854x480 default is too small to read
					}
					iw = 1;
					iwFrom = now;
					if (exists) {
						mc.launchIntegratedServer(SMOKE_WORLD, SMOKE_WORLD, null);
					} else {
						WorldSettings settings = new WorldSettings(20261027L, WorldSettings.GameType.SURVIVAL,
								false, false, WorldType.FLAT);
						mc.launchIntegratedServer(SMOKE_WORLD, "Origin Smoke", settings);
					}
					break;
				}
				case 1: {
					if (now - iwFrom > IW_LOAD_MS) {
						inWorldEnd(mc, false, "world not playable after " + IW_LOAD_MS / 1000 + " s (screen: "
								+ (mc.currentScreen == null ? "none" : mc.currentScreen.getClass().getSimpleName()) + ", player: "
								+ (mc.thePlayer != null) + ", HUD passes: " + com.origin.client.client.hud.HudElements.passes + ")", onEnd);
						return;
					}
					if (mc.currentScreen instanceof GuiIngameMenu) {
						mc.displayGuiScreen(null);   // lost focus under Xvfb
					}
					if (mc.theWorld == null || mc.thePlayer == null || mc.currentScreen != null) {
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
					OriginDevShots.grab(mc, dir, "inworld_hud_" + mc.displayWidth + "x" + mc.displayHeight + ".png");
					log("[origin-smoke] in-world: HUD up (" + passes + " HUD passes)");
					iw = 2;
					iwAt = now + 1500;
					break;
				}
				case 2: {
					mc.displayGuiScreen(new com.origin.client.client.gui.OriginModMenuScreen());
					iw = 3;
					iwAt = now + 2500;
					break;
				}
				case 3: {
					if (!(mc.currentScreen instanceof com.origin.client.client.gui.OriginModMenuScreen)) {
						inWorldEnd(mc, false, "mod menu did not open over the world (screen: "
								+ (mc.currentScreen == null ? "none" : mc.currentScreen.getClass().getSimpleName()) + ")", onEnd);
						return;
					}
					OriginDevShots.grab(mc, dir, "inworld_modmenu_" + mc.displayWidth + "x" + mc.displayHeight + ".png");
					log("[origin-smoke] in-world: mod menu open");
					iw = 4;
					iwAt = now + 1500;
					break;
				}
				case 4: {
					// Vanilla's Save and Quit (GuiIngameMenu.actionPerformed, button 1), then the title.
					mc.displayGuiScreen(null);
					if (mc.theWorld != null) {
						mc.theWorld.sendQuittingDisconnectingPacket();
					}
					mc.loadWorld((net.minecraft.client.multiplayer.WorldClient) null);
					mc.displayGuiScreen(new net.minecraft.client.gui.GuiMainMenu());
					iw = 5;
					iwFrom = now;
					break;
				}
				case 5: {
					if (mc.theWorld == null && mc.getIntegratedServer() == null && OriginDevShots.isTitle(mc.currentScreen)) {
						log("[origin-smoke] in-world: quit clean (saved, back on the title)");
						inWorldEnd(mc, true, "", onEnd);
						return;
					}
					if (now - iwFrom > IW_QUIT_MS) {
						inWorldEnd(mc, false, "Save and Quit did not reach the title in " + IW_QUIT_MS / 1000 + " s", onEnd);
						return;
					}
					iwAt = now + 250;
					break;
				}
				default:
					break;
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
			if (iwPauseOnLostFocus != null) {
				mc.gameSettings.pauseOnLostFocus = iwPauseOnLostFocus;
			}
			if (iwDifficulty != null) {
				mc.gameSettings.difficulty = iwDifficulty;
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
			mc.shutdown();
		}
	}

	private static void log(String msg) {
		com.origin.client.OriginClient.LOGGER.info(msg);
	}
}
