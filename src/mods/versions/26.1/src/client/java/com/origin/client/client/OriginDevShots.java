package com.origin.client.client;

import com.origin.client.client.gui.OriginModMenuScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import com.origin.client.client.hud.HudEditorScreen;
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
 * PER-VERSION DELTA (1.21.11): two calls differ from 1.21.1, both swapped for
 * ones this module already uses elsewhere --
 *   * the window is resized with GLFW.glfwSetWindowSize on Window.handle()
 *     (the 1.21.9 Window API; handle() is what GeneralSettings uses) instead
 *     of Window.setWindowed -- the same call setWindowed makes for a windowed
 *     game, and the framebuffer callback + resizeDisplay() pick it up;
 *   * the capture is Screenshot.grab(File, RenderTarget, Consumer), the
 *     overload F2 calls (ScreenshotKeyMixin targets it), which names the file
 *     by date and writes it on the IO thread. The extra phase 3 waits for that
 *     new PNG in screenshots/ and renames it to the shot's name, so the output
 *     folder matches every other module's.
 */
public final class OriginDevShots {
	private static final String DIR = System.getenv("ORIGIN_SHOTS");

	/** What a shot opens before capturing. */
	private enum Kind {
		TITLE, MENU, MENU_LIST, MOD_SETTINGS, SETTINGS, RSHIFT, COSMETICS,
		// every other Origin window (the 2026-10-12 restyle onto gui/OriginWindow)
		FPSCOACH, WAYPOINTS, ITEMSIZE, ITEMCOUNTER, QUICKPLAY, SHADERS,
		// the mod menu with "Solid Menu Background" off (the see-through mode)
		MENU_CLEAR,
		// Friends (social/SocialDemo fills it): a chat, then the party
		FRIENDS, FRIENDS_PARTY;

		Screen open() {
			// Only MENU_CLEAR shows the see-through panel; finish() restores the setting.
			if (savedBgOpacity < 0) {
				savedBgOpacity = com.origin.client.client.mods.Mods.metaNum("menuBgOpacity", 1.0);
			}
			com.origin.client.client.mods.Mods.setMetaNum("menuBgOpacity", this == MENU_CLEAR ? 0.0 : 1.0);
			// The friends demo only on its own shots, so every other shot looks the same in both passes.
			com.origin.client.client.social.SocialDemo.clear();
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
				case FPSCOACH -> new com.origin.client.client.gui.FpsCoachScreen(null);
				case WAYPOINTS -> new com.origin.client.client.waypoints.WaypointScreen();
				case ITEMSIZE -> new com.origin.client.client.gui.OriginItemSizeScreen();
				case ITEMCOUNTER -> new com.origin.client.client.gui.ItemCounterPickerScreen();
				case QUICKPLAY -> new com.origin.client.client.gui.QuickplayScreen();
				case SHADERS -> new com.origin.client.client.shaders.ShaderBrowserScreen(null);
				case MENU_CLEAR -> new OriginModMenuScreen().preview(false, false);
				case FRIENDS -> {
					com.origin.client.client.social.SocialDemo.install(false);
					yield new com.origin.client.client.gui.FriendsScreen(null)
							.select(com.origin.client.client.social.SocialDemo.KESTREL);
				}
				case FRIENDS_PARTY -> {
					com.origin.client.client.social.SocialDemo.install(false);
					yield new com.origin.client.client.gui.FriendsScreen(null)
							.select(com.origin.client.client.gui.FriendsScreen.PARTY);
				}
			};
		}
	}

	private record Shot(int w, int h, int scale, Kind kind) {
		String name() {
			return kind.name().toLowerCase(java.util.Locale.ROOT) + "_" + w + "x" + h + "_s" + scale + ".png";
		}
	}

	private static final List<Shot> SHOTS = new ArrayList<>();
	private static double savedBgOpacity = -1;
	/** ORIGIN_SHOTS_SET=world: OriginDevWorld captures inside a fresh world instead. */
	private static final boolean WORLD = "world".equals(System.getenv("ORIGIN_SHOTS_SET"));

	static {
		if (WORLD) {
			// no screen matrix: OriginDevWorld takes over once the title is up
		} else if ("design".equals(System.getenv("ORIGIN_SHOTS_SET"))) {
			// Design review (the menu / HUD-editor redesign loop): every screen at
			// the two most common desktop sizes, auto GUI scale.
			for (int[] s : new int[][]{{1920, 1080}, {1366, 768}}) {
				for (Kind k : Kind.values()) {
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
	// phase 3: the PNGs that existed before the grab, and how long we've waited
	private static Set<String> before = new HashSet<>();
	private static int renameRetries = 0;

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
		if (WORLD) {
			OriginDevWorld.tick(mc, DIR);
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
					GLFW.glfwSetWindowSize(mc.getWindow().handle(), s.w(), s.h());
					mc.options.guiScale().set(s.scale());
					mc.resizeGui();
					resizeRetries = 0;
					phase = 1;
					nextAt = now + 800;
				}
				case 1 -> {
					// Fresh screens so entrance animations replay and get captured settled.
					mc.setScreen(s.kind().open());
					mc.resizeGui();
					phase = 2;
					// The shader browser's first open starts its preview downloads; under
					// software GL (CI) that first second is slow, so it gets longer to settle.
					nextAt = now + (s.kind() == Kind.TITLE ? 1900 : s.kind() == Kind.SHADERS ? 3000 : 900);
				}
				case 2 -> {
					// Windows may apply a resize one message-loop later (especially
					// immediately after changing GUI scale). Never label a capture with
					// dimensions its framebuffer did not actually reach.
					int actualW = mc.getWindow().getWidth();
					int actualH = mc.getWindow().getHeight();
					if ((actualW != s.w() || actualH != s.h()) && resizeRetries++ < 8) {
						GLFW.glfwSetWindowSize(mc.getWindow().handle(), s.w(), s.h());
						mc.resizeGui();
						nextAt = now + 350;
						return;
					}
					if (actualW != s.w() || actualH != s.h()) {
						throw new IllegalStateException("window stayed at " + actualW + "x" + actualH
								+ " instead of " + s.w() + "x" + s.h());
					}
					before = pngs();
					renameRetries = 0;
					Screenshot.grab(new File(DIR), mc.getMainRenderTarget(), c -> {
					});
					phase = 3;
					nextAt = now + 250;
				}
				case 3 -> {
					// The PNG is written on the IO thread: wait for the new file, then
					// give it the shot's name.
					File dir = new File(DIR, "screenshots");
					String fresh = null;
					for (String n : pngs()) {
						if (!before.contains(n)) {
							fresh = n;
						}
					}
					if (fresh == null && renameRetries++ < 20) {
						nextAt = now + 250;
						return;
					}
					if (fresh == null) {
						throw new IllegalStateException("no screenshot appeared for " + s.name());
					}
					Files.move(new File(dir, fresh).toPath(), new File(dir, s.name()).toPath(),
							StandardCopyOption.REPLACE_EXISTING);
					com.origin.client.OriginClient.LOGGER.info("[dev-shots] captured {}", s.name());
					step++;
					phase = 0;
					nextAt = now + 250;
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

	/** PNG names currently in the output's screenshots/ folder (OriginDevWorld uses it too). */
	static Set<String> pngs() {
		Set<String> out = new HashSet<>();
		String[] names = new File(DIR, "screenshots").list();
		if (names != null) {
			for (String n : names) {
				if (n.endsWith(".png")) {
					out.add(n);
				}
			}
		}
		return out;
	}

	private static void finish(Minecraft mc) {
		done = true;
		if (savedBgOpacity >= 0) {
			com.origin.client.client.mods.Mods.setMetaNum("menuBgOpacity", savedBgOpacity);
		}
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
