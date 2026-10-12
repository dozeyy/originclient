package com.origin.client.client;

import com.origin.client.client.gui.OriginModMenuScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import com.origin.client.client.hud.HudEditorScreen;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * DEV-ONLY visual verification harness (2026-09). Off unless the environment
 * variable {@code ORIGIN_SHOTS} names a directory. When it does, the client
 * boots to the title screen, then walks a matrix of window sizes × GUI scales,
 * capturing the TITLE (after its entrance animation) and the MOD MENU at each,
 * into {@code $ORIGIN_SHOTS/screenshots/}, and finally quits. With
 * {@code ORIGIN_SHOTS_SET=design} it instead captures title, mod menu, a mod's
 * settings page and the HUD editor at two sizes (.github/workflows/dev-shots.yml). This is how a
 * layout change gets LOOKED AT on every aspect ratio before it reaches Will —
 * no more "compiles, ships, looks wrong".
 *
 * Runs on the client tick (render thread). Each step waits long enough for
 * the window to actually resize and for entrance animations to settle.
 * Never touches config; the GUI-scale option is restored at the end.
 *
 * PER-VERSION DELTA (1.21.6-1.21.8): the only Screenshot.grab this module has
 * evidence for is the 3-arg grab(File, RenderTarget, Consumer<Component>) (the
 * F2 path ScreenshotKeyMixin targets), which names the file by timestamp. So a
 * shot is grabbed with that, and its callback renames the written file to the
 * shot's name -- the same file vanilla's success message links to (the
 * ClickEvent.OpenFile ScreenshotChat reads). The dev-shots workflow sees the
 * same $ORIGIN_SHOTS/screenshots/<kind>_<w>x<h>_s<scale>.png names as 1.21.1.
 */
public final class OriginDevShots {
	private static final String DIR = System.getenv("ORIGIN_SHOTS");

	/** What a shot opens before capturing. */
	private enum Kind {
		TITLE, MENU, MENU_LIST, MOD_SETTINGS, SETTINGS, RSHIFT, COSMETICS;

		Screen open() {
			return switch (this) {
				case TITLE -> new TitleScreen();
				case MENU -> new OriginModMenuScreen().preview(false, false);
				case MENU_LIST -> new OriginModMenuScreen().preview(true, false);
				// A HUD mod's own page: the settings layout the redesign is judged on.
				case MOD_SETTINGS -> new OriginModMenuScreen(null, "keystrokes").preview(false, false);
				case SETTINGS -> new OriginModMenuScreen().preview(false, true);
				// The Right Shift screen (HUD editor + the Origin centre group).
				case RSHIFT -> new HudEditorScreen(true);
				// The cosmetics locker, opened from the menu's Cosmetics tab.
				case COSMETICS -> new com.origin.client.client.gui.CosmeticsScreen(null);
			};
		}
	}

	private record Shot(int w, int h, int scale, Kind kind) {
		String name() {
			return kind.name().toLowerCase(java.util.Locale.ROOT) + "_" + w + "x" + h + "_s" + scale + ".png";
		}
	}

	private static final List<Shot> SHOTS = new ArrayList<>();

	static {
		if ("design".equals(System.getenv("ORIGIN_SHOTS_SET"))) {
			// Design review (the menu / HUD-editor redesign loop): every screen at
			// the two most common desktop sizes, auto GUI scale.
			for (int[] s : new int[][]{{1920, 1080}, {1366, 768}}) {
				for (Kind k : Kind.values()) {
					if (k == Kind.TITLE) {
						continue;   // the title isn't part of this redesign
					}
					SHOTS.add(new Shot(s[0], s[1], 0, k));
				}
			}
			// Same menu at a forced GUI scale: the redesign must not change size with it.
			SHOTS.add(new Shot(1920, 1080, 2, Kind.MENU));
		} else {
			// Common desktop ratios at auto GUI scale (0 = Minecraft's own choice),
			// then the two extremes of GUI scale at 1080p.
			int[][] sizes = {{1920, 1080}, {2560, 1080}, {1280, 720}, {1024, 768}, {1366, 768}, {1600, 900}};
			for (int[] s : sizes) {
				SHOTS.add(new Shot(s[0], s[1], 0, Kind.TITLE));
				SHOTS.add(new Shot(s[0], s[1], 0, Kind.MENU));
			}
			for (int sc : new int[]{2, 4}) {
				SHOTS.add(new Shot(1920, 1080, sc, Kind.TITLE));
				SHOTS.add(new Shot(1920, 1080, sc, Kind.MENU));
			}
		}
	}

	private static int step = -1;      // -1 = waiting for the title to be up
	private static int phase = 0;      // 0 = resize, 1 = open screen, 2 = grab
	private static long nextAt = 0;
	private static int savedScale = -1;
	private static int resizeRetries = 0;
	private static boolean done = false;

	private OriginDevShots() {
	}

	public static boolean enabled() {
		return DIR != null && !DIR.isEmpty();
	}

	public static void tick(Minecraft mc) {
		if (done) {
			return;
		}
		long now = System.currentTimeMillis();
		if (step < 0) {
			// A fresh game directory (CI) opens vanilla's one-time accessibility
			// onboarding screen instead of the title; skip it, or the harness would
			// wait for a title screen that never comes.
			if (mc.getOverlay() == null && mc.screen != null && !(mc.screen instanceof TitleScreen)
					&& !(mc.screen instanceof OriginModMenuScreen)) {
				mc.options.onboardAccessibility = false;
				mc.setScreen(new TitleScreen());
				return;
			}
			if (mc.getOverlay() == null && mc.screen instanceof TitleScreen) {
				if (nextAt == 0) {
					nextAt = now + 1500;
				} else if (now >= nextAt) {
					File output = new File(DIR);
					if (!output.isDirectory() && !output.mkdirs()) {
						com.origin.client.OriginClient.LOGGER.error(
								"[dev-shots] could not create output directory {}", output.getAbsolutePath());
						done = true;
						mc.stop();
						return;
					}
					savedScale = mc.options.guiScale().get();
					step = 0;
					phase = 0;
					nextAt = 0;
				}
			}
			return;
		}
		if (now < nextAt) {
			return;
		}
		if (step >= SHOTS.size()) {
			finish(mc);
			return;
		}
		Shot s = SHOTS.get(step);
		try {
			switch (phase) {
				case 0 -> {
					mc.getWindow().setWindowed(s.w(), s.h());
					mc.options.guiScale().set(s.scale());
					mc.resizeDisplay();
					resizeRetries = 0;
					phase = 1;
					nextAt = now + 800;
				}
				case 1 -> {
					// Fresh screens so entrance animations replay and get captured settled.
					mc.setScreen(s.kind().open());
					mc.resizeDisplay();
					phase = 2;
					nextAt = now + (s.kind() == Kind.TITLE ? 1900 : 900);
				}
				case 2 -> {
					// Windows may apply a resize one message-loop later (especially
					// immediately after changing GUI scale). Never label a capture with
					// dimensions its framebuffer did not actually reach.
					int actualW = mc.getWindow().getWidth();
					int actualH = mc.getWindow().getHeight();
					if ((actualW != s.w() || actualH != s.h()) && resizeRetries++ < 8) {
						mc.getWindow().setWindowed(s.w(), s.h());
						mc.resizeDisplay();
						nextAt = now + 350;
						return;
					}
					if (actualW != s.w() || actualH != s.h()) {
						throw new IllegalStateException("window stayed at " + actualW + "x" + actualH
								+ " instead of " + s.w() + "x" + s.h());
					}
					String name = s.name();
					Screenshot.grab(new File(DIR), mc.getMainRenderTarget(), msg -> renameShot(msg, name));
					com.origin.client.OriginClient.LOGGER.info("[dev-shots] captured {}", s.name());
					step++;
					phase = 0;
					// this era reads the frame back and writes the PNG asynchronously:
					// give the last one time to land (and be renamed) before quitting
					nextAt = now + (step >= SHOTS.size() ? 2000 : 250);
				}
				default -> phase = 0;
			}
		} catch (Throwable t) {
			com.origin.client.OriginClient.LOGGER.warn("[dev-shots] step {} failed", step, t);
			step++;
			phase = 0;
			nextAt = now + 250;
		}
	}

	/** IO thread: moves the timestamp-named capture to the shot's own name. */
	private static void renameShot(Component msg, String name) {
		try {
			if (!(msg.getContents() instanceof TranslatableContents tc) || !"screenshot.success".equals(tc.getKey())) {
				com.origin.client.OriginClient.LOGGER.warn("[dev-shots] {} not saved: {}", name, msg.getString());
				return;
			}
			Object[] args = tc.getArgs();
			if (args.length == 0 || !(args[0] instanceof Component link)
					|| !(link.getStyle().getClickEvent() instanceof ClickEvent.OpenFile open)) {
				return;
			}
			File saved = open.file();
			Files.move(saved.toPath(), saved.toPath().resolveSibling(name), StandardCopyOption.REPLACE_EXISTING);
		} catch (Throwable t) {
			com.origin.client.OriginClient.LOGGER.warn("[dev-shots] could not rename {}", name, t);
		}
	}

	private static void finish(Minecraft mc) {
		done = true;
		try {
			if (savedScale >= 0) {
				mc.options.guiScale().set(savedScale);
			}
		} catch (Throwable ignored) {
		}
		com.origin.client.OriginClient.LOGGER.info("[dev-shots] done — {} shots in {}", SHOTS.size(), DIR);
		mc.stop();
	}
}
