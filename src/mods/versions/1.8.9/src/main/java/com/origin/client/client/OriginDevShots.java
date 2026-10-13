package com.origin.client.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.ScreenShotHelper;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
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
 * (.github/workflows/dev-shots.yml). This is how a layout change gets LOOKED
 * AT on every aspect ratio before it reaches Will.
 *
 * Runs on the client tick (render thread). Each step waits long enough for
 * the window to actually resize and for entrance animations to settle.
 * Never touches config; the GUI-scale option is restored at the end.
 *
 * 1.8.9: the same shot list and file names as 1.16.5. The window is resized
 * with LWJGL 2's Display.setDisplayMode + Minecraft.resize (no GLFW); the
 * capture is ScreenShotHelper.saveScreenshot(dir, name, w, h, framebuffer),
 * which writes to dir/screenshots/name like 1.16.5's Screenshot.grab. The
 * screens other areas own are built by class name (reflection), so the
 * harness compiles and runs before every area has landed -- a missing screen
 * is a skipped shot with a log line, never a failed run.
 */
public final class OriginDevShots {
	private static final String DIR = System.getenv("ORIGIN_SHOTS");
	private static final String P = "com.origin.client.client.";

	/** What a shot opens before capturing. */
	private enum Kind {
		TITLE, MENU, MENU_LIST, MOD_SETTINGS, SETTINGS, RSHIFT, COSMETICS,
		// every other Origin window (the 2026-10-12 restyle onto gui/OriginWindow)
		FPSCOACH, WAYPOINTS, ITEMSIZE, ITEMCOUNTER, QUICKPLAY, SHADERS,
		// the mod menu with "Solid Menu Background" off (the see-through mode)
		MENU_CLEAR,
		// Friends (social/SocialDemo fills it): a chat, then the party
		FRIENDS, FRIENDS_PARTY;

		GuiScreen open() {
			// Only MENU_CLEAR shows the see-through panel; finish() restores the setting.
			if (savedBgOpacity < 0) {
				savedBgOpacity = com.origin.client.client.mods.Mods.metaNum("menuBgOpacity", 1.0);
			}
			com.origin.client.client.mods.Mods.setMetaNum("menuBgOpacity", this == MENU_CLEAR ? 0.0 : 1.0);
			// The friends demo only on its own shots, so every other shot looks the same in both passes.
			callStatic(P + "social.SocialDemo", "clear");
			switch (this) {
				case TITLE:
					// GuiOpenEvent hands the menus area its chance to swap in the Origin title.
					return new GuiMainMenu();
				case MENU:
					return preview(make(P + "gui.OriginModMenuScreen"), false, false);
				case MENU_LIST:
					return preview(make(P + "gui.OriginModMenuScreen"), true, false);
				case MOD_SETTINGS:
					// A HUD mod's own page: the settings layout the redesign is judged on.
					return preview(make(P + "gui.OriginModMenuScreen", null, "keystrokes"), false, false);
				case SETTINGS:
					return preview(make(P + "gui.OriginModMenuScreen"), false, true);
				case COSMETICS:
					return make(P + "gui.CosmeticsScreen", (Object) null);
				case FPSCOACH:
					return make(P + "gui.FpsCoachScreen", (Object) null);
				case WAYPOINTS:
					return make(P + "waypoints.WaypointScreen");
				case ITEMSIZE:
					return make(P + "gui.OriginItemSizeScreen");
				case ITEMCOUNTER:
					return make(P + "gui.ItemCounterPickerScreen");
				case QUICKPLAY:
					return make(P + "gui.QuickplayScreen");
				case SHADERS:
					return make(P + "shaders.ShaderBrowserScreen", (Object) null);
				case MENU_CLEAR:
					return preview(make(P + "gui.OriginModMenuScreen"), false, false);
				case FRIENDS:
					callStatic(P + "social.SocialDemo", "install", false);
					return select(make(P + "gui.FriendsScreen", (Object) null),
							staticField(P + "social.SocialDemo", "KESTREL"));
				case FRIENDS_PARTY:
					callStatic(P + "social.SocialDemo", "install", false);
					return select(make(P + "gui.FriendsScreen", (Object) null),
							staticField(P + "gui.FriendsScreen", "PARTY"));
				default:
					// RSHIFT: the Right Shift screen (HUD editor + the Origin centre group).
					return make(P + "hud.HudEditorScreen", true);
			}
		}
	}

	// Java 8 has no records: a final value class.
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
			// Design review: every screen at the two most common desktop sizes, auto GUI scale.
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
			if (mc.theWorld == null && isTitle(mc.currentScreen)) {
				if (nextAt == 0) {
					nextAt = now + 1500;
				} else if (now >= nextAt) {
					File output = new File(DIR);
					if (!output.isDirectory() && !output.mkdirs()) {
						com.origin.client.OriginClient.LOGGER.error(
								"[dev-shots] could not create output directory {}", output.getAbsolutePath());
						done = true;
						mc.shutdown();
						return;
					}
					savedScale = mc.gameSettings.guiScale;
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
					mc.gameSettings.guiScale = s.scale;
					resizeWindow(mc, s.w, s.h);
					resizeRetries = 0;
					phase = 1;
					nextAt = now + 800;
					break;
				}
				case 1: {
					// Fresh screens so entrance animations replay and get captured settled.
					GuiScreen screen = s.kind.open();
					if (screen == null) {
						com.origin.client.OriginClient.LOGGER.warn("[dev-shots] {} skipped: its screen is not available", s.name());
						step++;
						phase = 0;
						nextAt = now + 100;
						break;
					}
					mc.displayGuiScreen(screen);
					phase = 2;
					nextAt = now + (s.kind == Kind.TITLE ? 1900 : 900);
					break;
				}
				case 2: {
					// The window manager may apply a resize a little later. Never label a
					// capture with dimensions its framebuffer did not actually reach.
					if ((mc.displayWidth != s.w || mc.displayHeight != s.h) && resizeRetries++ < 8) {
						resizeWindow(mc, s.w, s.h);
						nextAt = now + 350;
						return;
					}
					if (mc.displayWidth != s.w || mc.displayHeight != s.h) {
						throw new IllegalStateException("window stayed at " + mc.displayWidth + "x" + mc.displayHeight
								+ " instead of " + s.w + "x" + s.h);
					}
					grab(mc, DIR, s.name());
					step++;
					phase = 0;
					nextAt = now + 250;
					break;
				}
				default:
					phase = 0;
					break;
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
				mc.gameSettings.guiScale = savedScale;
			}
		} catch (Throwable ignored) {
			// dev only
		}
		com.origin.client.OriginClient.LOGGER.info("[dev-shots] done — {} shots in {}", SHOTS.size(), DIR);
		mc.shutdown();
	}

	// ---- 1.8.9 helpers (shared with OriginDevWorld) ----

	/** The vanilla title screen or the menus area's Origin replacement for it. */
	static boolean isTitle(GuiScreen s) {
		return s instanceof GuiMainMenu || (s != null && s.getClass().getSimpleName().contains("Title"));
	}

	/** 1.8.9: LWJGL 2 window resize (re-creates the window), then Minecraft's own resize. */
	static void resizeWindow(Minecraft mc, int w, int h) throws Exception {
		if (Display.getWidth() != w || Display.getHeight() != h) {
			Display.setDisplayMode(new DisplayMode(w, h));
		}
		mc.resize(w, h);
	}

	/** ScreenShotHelper writes `dir`/screenshots/`name` from the main framebuffer. */
	static void grab(Minecraft mc, String dir, String name) {
		ScreenShotHelper.saveScreenshot(new File(dir), name, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
		com.origin.client.OriginClient.LOGGER.info("[dev-shots] captured {}", name);
	}

	/** A screen of another area by class name and constructor arity (null on any failure). */
	static GuiScreen make(String className, Object... args) {
		try {
			Class<?> c = Class.forName(className);
			for (Constructor<?> k : c.getConstructors()) {
				if (k.getParameterTypes().length == args.length && fits(k.getParameterTypes(), args)) {
					return (GuiScreen) k.newInstance(args);
				}
			}
		} catch (Throwable t) {
			com.origin.client.OriginClient.LOGGER.warn("[dev-shots] could not build {}", className, t);
		}
		return null;
	}

	private static GuiScreen preview(GuiScreen menu, boolean list, boolean settings) {
		Object r = call(menu, "preview", list, settings);
		return r instanceof GuiScreen ? (GuiScreen) r : menu;
	}

	private static GuiScreen select(GuiScreen friends, Object what) {
		Object r = call(friends, "select", what);
		return r instanceof GuiScreen ? (GuiScreen) r : friends;
	}

	static Object call(Object target, String method, Object... args) {
		if (target == null) {
			return null;
		}
		try {
			for (Method m : target.getClass().getMethods()) {
				if (m.getName().equals(method) && m.getParameterTypes().length == args.length
						&& fits(m.getParameterTypes(), args)) {
					return m.invoke(target, args);
				}
			}
		} catch (Throwable t) {
			com.origin.client.OriginClient.LOGGER.warn("[dev-shots] {}.{} failed", target.getClass().getSimpleName(), method, t);
		}
		return null;
	}

	static Object callStatic(String className, String method, Object... args) {
		try {
			Class<?> c = Class.forName(className);
			for (Method m : c.getMethods()) {
				if (m.getName().equals(method) && m.getParameterTypes().length == args.length
						&& fits(m.getParameterTypes(), args)) {
					return m.invoke(null, args);
				}
			}
		} catch (Throwable t) {
			// that area has not landed: the shot goes without it
		}
		return null;
	}

	private static Object staticField(String className, String field) {
		try {
			return Class.forName(className).getField(field).get(null);
		} catch (Throwable t) {
			return null;
		}
	}

	private static boolean fits(Class<?>[] types, Object[] args) {
		for (int i = 0; i < types.length; i++) {
			Object a = args[i];
			Class<?> t = types[i];
			if (a == null) {
				if (t.isPrimitive()) {
					return false;
				}
				continue;
			}
			if (t.isPrimitive()) {
				if ((t == boolean.class && a instanceof Boolean) || (t == int.class && a instanceof Integer)
						|| (t == double.class && a instanceof Double) || (t == float.class && a instanceof Float)) {
					continue;
				}
				return false;
			}
			if (!t.isInstance(a)) {
				return false;
			}
		}
		return true;
	}
}
