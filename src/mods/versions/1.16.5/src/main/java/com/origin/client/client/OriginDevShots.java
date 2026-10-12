package com.origin.client.client;

import com.origin.client.client.gui.OriginModMenuScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import com.origin.client.client.hud.HudEditorScreen;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * DEV-ONLY visual verification harness (2026-09). Off unless the environment
 * variable {@code ORIGIN_SHOTS} names a directory. When it does, the client
 * boots to the title screen, then walks a matrix of window sizes × GUI scales,
 * capturing the TITLE (after its entrance animation) and the MOD MENU at each,
 * into {@code $ORIGIN_SHOTS/screenshots/}, and finally quits. With
 * {@code ORIGIN_SHOTS_SET=design} it instead captures the title, the mod menu
 * (grid + list + see-through), a mod's settings page, the Settings tab, the
 * Right Shift screen and every Origin window at two sizes; with
 * {@code ORIGIN_SHOTS_SET=world} OriginDevWorld captures inside a fresh world
 * (.github/workflows/dev-shots.yml). This is how a
 * layout change gets LOOKED AT on every aspect ratio before it reaches Will —
 * no more "compiles, ships, looks wrong".
 *
 * Runs on the client tick (render thread). Each step waits long enough for
 * the window to actually resize and for entrance animations to settle.
 * Never touches config; the GUI-scale option is restored at the end.
 */
public final class OriginDevShots {
	private static final String DIR = System.getenv("ORIGIN_SHOTS");

	/** What a shot opens before capturing. */
	private enum Kind {
		TITLE, MENU, MENU_LIST, MOD_SETTINGS, SETTINGS, RSHIFT, COSMETICS,
		// every other Origin window (the 2026-10-12 restyle onto gui/OriginWindow)
		FPSCOACH, WAYPOINTS, ITEMSIZE, ITEMCOUNTER, QUICKPLAY, SHADERS,
		// the mod menu with "Solid Menu Background" off (the see-through mode)
		MENU_CLEAR;

		Screen open() {
			// Only MENU_CLEAR shows the see-through panel; finish() restores the setting.
			if (savedBgOpacity < 0) {
				savedBgOpacity = com.origin.client.client.mods.Mods.metaNum("menuBgOpacity", 1.0);
			}
			com.origin.client.client.mods.Mods.setMetaNum("menuBgOpacity", this == MENU_CLEAR ? 0.0 : 1.0);
			switch (this) {
				case TITLE:
					return new TitleScreen();
				case MENU:
					return new OriginModMenuScreen().preview(false, false);
				case MENU_LIST:
					return new OriginModMenuScreen().preview(true, false);
				case MOD_SETTINGS:
					// A HUD mod's own page: the settings layout the redesign is judged on.
					return new OriginModMenuScreen(null, "keystrokes").preview(false, false);
				case SETTINGS:
					return new OriginModMenuScreen().preview(false, true);
				case COSMETICS:
					// The cosmetics locker, opened from the menu's Cosmetics tab.
					return new com.origin.client.client.gui.CosmeticsScreen(null);
				case FPSCOACH:
					return new com.origin.client.client.gui.FpsCoachScreen(null);
				case WAYPOINTS:
					return new com.origin.client.client.waypoints.WaypointScreen();
				case ITEMSIZE:
					return new com.origin.client.client.gui.OriginItemSizeScreen();
				case ITEMCOUNTER:
					return new com.origin.client.client.gui.ItemCounterPickerScreen();
				case QUICKPLAY:
					return new com.origin.client.client.gui.QuickplayScreen();
				case SHADERS:
					return new com.origin.client.client.shaders.ShaderBrowserScreen(null);
				case MENU_CLEAR:
					return new OriginModMenuScreen().preview(false, false);
				default:
					// RSHIFT: the Right Shift screen (HUD editor + the Origin centre group).
					return new HudEditorScreen(true);
			}
		}
	}

	// Java 8 has no records: a final value class with the same canonical
	// constructor, accessors and equals/hashCode the record had.
	private static final class Shot {
		private final int w;
		private final int h;
		private final int scale;
		private final Kind kind;

		private Shot(int w, int h, int scale, Kind kind) {
			this.w = w;
			this.h = h;
			this.scale = scale;
			this.kind = kind;
		}

		public int w() {
			return w;
		}

		public int h() {
			return h;
		}

		public int scale() {
			return scale;
		}

		public Kind kind() {
			return kind;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (!(obj instanceof Shot)) {
				return false;
			}
			Shot other = (Shot) obj;
			return this.w == other.w
					&& this.h == other.h
					&& this.scale == other.scale
					&& this.kind == other.kind;
		}

		@Override
		public int hashCode() {
			return java.util.Objects.hash(w, h, scale, kind);
		}

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
					savedScale = mc.options.guiScale;   // pre-1.19: a plain field (no OptionInstance)
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
				case 0: {
					org.lwjgl.glfw.GLFW.glfwSetWindowSize(mc.getWindow().getWindow(), s.w(), s.h()); // 1.16.5: no Window.setWindowed
					mc.options.guiScale = s.scale();
					mc.resizeDisplay();
					resizeRetries = 0;
					phase = 1;
					nextAt = now + 800;
					break;
				}
				case 1: {
					// Fresh screens so entrance animations replay and get captured settled.
					mc.setScreen(s.kind().open());
					mc.resizeDisplay();
					phase = 2;
					nextAt = now + (s.kind() == Kind.TITLE ? 1900 : 900);
					break;
				}
				case 2: {
					// Windows may apply a resize one message-loop later (especially
					// immediately after changing GUI scale). Never label a capture with
					// dimensions its framebuffer did not actually reach.
					int actualW = mc.getWindow().getWidth();
					int actualH = mc.getWindow().getHeight();
					if ((actualW != s.w() || actualH != s.h()) && resizeRetries++ < 8) {
						org.lwjgl.glfw.GLFW.glfwSetWindowSize(mc.getWindow().getWindow(), s.w(), s.h()); // 1.16.5: no Window.setWindowed
						mc.resizeDisplay();
						nextAt = now + 350;
						return;
					}
					if (actualW != s.w() || actualH != s.h()) {
						throw new IllegalStateException("window stayed at " + actualW + "x" + actualH
								+ " instead of " + s.w() + "x" + s.h());
					}
					// 1.16.5: grab() still takes the framebuffer size explicitly.
					Screenshot.grab(new File(DIR), s.name(), actualW, actualH, mc.getMainRenderTarget(), c -> {
					});
					com.origin.client.OriginClient.LOGGER.info("[dev-shots] captured {}", s.name());
					step++;
					phase = 0;
					nextAt = now + 250;
					break;
				}
				default: phase = 0; break;
			}
		} catch (Throwable t) {
			com.origin.client.OriginClient.LOGGER.warn("[dev-shots] step {} failed", step, t);
			step++;
			phase = 0;
			nextAt = now + 250;
		}
	}

	private static void finish(Minecraft mc) {
		done = true;
		if (savedBgOpacity >= 0) {
			com.origin.client.client.mods.Mods.setMetaNum("menuBgOpacity", savedBgOpacity);
		}
		try {
			if (savedScale >= 0) {
				mc.options.guiScale = savedScale;
			}
		} catch (Throwable ignored) {
		}
		com.origin.client.OriginClient.LOGGER.info("[dev-shots] done — {} shots in {}", SHOTS.size(), DIR);
		mc.stop();
	}
}
