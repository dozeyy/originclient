package com.origin.client.client;

import com.origin.client.OriginClient;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWVidMode;

// Applies the SETTINGS > General options that need to run against the live
// window every tick: Borderless Fullscreen (GLFW window mode). Acts on a toggle
// change, then keeps an applied borderless window covering its monitor. Fully
// fail-soft — any GLFW hiccup is swallowed so a bad window state can never
// crash the game. Raw mouse input is NOT Origin's here: Minecraft's own Mouse
// Settings option rules it on every version but 1.8.9 (Will, 2026-11-03).
public final class GeneralSettings {
	// Borderless enters/leaves only on a real change of the toggle. In between,
	// an applied borderless window (no frame, so nothing for the player to drag)
	// is only checked to still cover its monitor (keepCovering, paced by guard).
	private static Boolean lastBorderless = null;
	private static boolean borderlessApplied = false;
	private static int savedX, savedY, savedW, savedH;
	private static final BorderlessFit.Guard guard = new BorderlessFit.Guard();
	// One "covers its monitor" log line per enter: the proof in latest.log that the
	// first-tick apply held (CI boot-smoke greps it too).
	private static boolean coverReported;

	private GeneralSettings() {
	}

	static void tick(Minecraft mc) {
		applyBorderless(mc);
		applyTransparentMenus();
	}

	// Transparent Menus (Settings > General). The vanilla backdrops are cleared by MenuBlur
	// (blur, dim and list strips); the Origin panels use the existing "Solid Menu
	// Background" setting, so this toggle drives it: on = panel off (remembering the old
	// opacity), off = the remembered opacity back. Edge-triggered, so a player who never
	// turns it on keeps their own Solid Menu Background choice untouched.
	private static Boolean lastTransparent = null;
	private static double solidBefore = 1.0;

	private static void applyTransparentMenus() {
		boolean want = Mods.bool(Mods.GENERAL_ID, "transparentMenus");
		if (lastTransparent != null && lastTransparent == want) {
			return;
		}
		boolean first = lastTransparent == null;
		lastTransparent = want;
		if (want) {
			double cur = Mods.metaNum("menuBgOpacity", 1.0);
			if (cur > 0.0) {
				solidBefore = cur;
				Mods.setMetaNum("menuBgOpacity", 0.0);
			}
		} else if (!first) {
			Mods.setMetaNum("menuBgOpacity", solidBefore);
		}
	}

	private static void applyBorderless(Minecraft mc) {
		boolean want = Mods.bool(Mods.GENERAL_ID, "borderlessFullscreen");
		long win;
		try {
			win = mc.getWindow().getWindow();
		} catch (Throwable t) {
			return;
		}
		if (lastBorderless == null || lastBorderless != want) {
			lastBorderless = want;
			// Never fight vanilla's exclusive fullscreen (F11): let that own the
			// window; borderless applies from the windowed state only.
			if (want && !borderlessApplied) {
				if (mc.options.fullscreen) {
					lastBorderless = null; // retry once we're back in windowed mode
					return;
				}
				enterBorderless(win);
			} else if (!want && borderlessApplied) {
				leaveBorderless(win);
			}
			return;
		}
		if (borderlessApplied) {
			keepCovering(mc, win);
		}
	}

	private static void enterBorderless(long win) {
		try {
			// A maximised window first goes back to its normal size: that is the size
			// to return to, and Windows keeps re-sizing a maximised window to the
			// work area (taskbar left showing).
			restoreIfMaximised(win);
			int[] x = new int[1], y = new int[1], w = new int[1], h = new int[1];
			GLFW.glfwGetWindowPos(win, x, y);
			GLFW.glfwGetWindowSize(win, w, h);
			int[] monitor = monitorFor(x[0], y[0], w[0], h[0]);
			if (monitor == null) {
				return;
			}
			savedX = x[0];
			savedY = y[0];
			savedW = w[0];
			savedH = h[0];
			fit(win, monitor);
			borderlessApplied = true;
			guard.reset(System.currentTimeMillis());
			coverReported = false;
			OriginClient.LOGGER.info("Origin: borderless on: window {},{} {}x{} -> monitor {},{} {}x{}",
					x[0], y[0], w[0], h[0], monitor[0], monitor[1], monitor[2], monitor[3]);
		} catch (Throwable t) {
			// fail-soft: leave the window as-is
		}
	}

	private static void leaveBorderless(long win) {
		try {
			GLFW.glfwSetWindowAttrib(win, GLFW.GLFW_DECORATED, GLFW.GLFW_TRUE);
			GLFW.glfwSetWindowMonitor(win, 0L, savedX, savedY,
					Math.max(320, savedW), Math.max(240, savedH), GLFW.GLFW_DONT_CARE);
			borderlessApplied = false;
		} catch (Throwable t) {
			// fail-soft
		}
	}

	// While Borderless is on, the window must keep covering its monitor. A game that
	// starts with it on applies it on the first tick, mid start-up; anything that
	// resizes the window after that (Will, 2026-11-04: borderless but on part of the
	// screen until toggled off and on) is undone here. Paced and capped by
	// BorderlessFit.Guard; skipped while vanilla's F11 fullscreen or a minimise owns
	// the window. The log line is the evidence trail when it does fire.
	private static void keepCovering(Minecraft mc, long win) {
		long now = System.currentTimeMillis();
		if (!guard.shouldCheck(now)) {
			return;
		}
		try {
			if (mc.options.fullscreen || GLFW.glfwGetWindowMonitor(win) != 0L
					|| GLFW.glfwGetWindowAttrib(win, GLFW.GLFW_ICONIFIED) == GLFW.GLFW_TRUE) {
				return;
			}
			int[] x = new int[1], y = new int[1], w = new int[1], h = new int[1];
			GLFW.glfwGetWindowPos(win, x, y);
			GLFW.glfwGetWindowSize(win, w, h);
			int[] monitor = monitorFor(x[0], y[0], w[0], h[0]);
			boolean decorated = GLFW.glfwGetWindowAttrib(win, GLFW.GLFW_DECORATED) == GLFW.GLFW_TRUE;
			boolean maximised = GLFW.glfwGetWindowAttrib(win, GLFW.GLFW_MAXIMIZED) == GLFW.GLFW_TRUE;
			if (monitor == null) {
				return;
			}
			if (!decorated && !maximised && BorderlessFit.covers(x[0], y[0], w[0], h[0], monitor)) {
				if (!coverReported) {
					coverReported = true;
					OriginClient.LOGGER.info("Origin: borderless window covers its monitor {},{} {}x{}", x[0], y[0], w[0], h[0]);
				}
				return;
			}
			if (guard.mayRefit(now, x[0], y[0], w[0], h[0])) {
				OriginClient.LOGGER.info("Origin: borderless window was at {},{} {}x{}{}; fitting it to its monitor {},{} {}x{} again",
						x[0], y[0], w[0], h[0], decorated ? " with a frame" : "", monitor[0], monitor[1], monitor[2], monitor[3]);
				fit(win, monitor);
			}
		} catch (Throwable t) {
			// fail-soft
		}
	}

	private static void fit(long win, int[] monitor) {
		restoreIfMaximised(win);
		GLFW.glfwSetWindowAttrib(win, GLFW.GLFW_DECORATED, GLFW.GLFW_FALSE);
		GLFW.glfwSetWindowMonitor(win, 0L, monitor[0], monitor[1], monitor[2], monitor[3], GLFW.GLFW_DONT_CARE);
	}

	private static void restoreIfMaximised(long win) {
		if (GLFW.glfwGetWindowAttrib(win, GLFW.GLFW_MAXIMIZED) == GLFW.GLFW_TRUE) {
			GLFW.glfwRestoreWindow(win);
		}
	}

	// {x, y, w, h} of the monitor the window is on (its centre, else the most
	// overlap), else the primary monitor; null when GLFW knows no monitor at all.
	// The window's own monitor, not always the primary: a game opened on a second
	// screen goes borderless there.
	private static int[] monitorFor(int wx, int wy, int ww, int wh) {
		PointerBuffer all = GLFW.glfwGetMonitors();
		int n = all == null ? 0 : all.limit();
		int[][] rects = new int[n][];
		for (int i = 0; i < n; i++) {
			rects[i] = monitorRect(all.get(i));
		}
		int pick = BorderlessFit.pickMonitor(wx, wy, ww, wh, rects);
		if (pick >= 0) {
			return rects[pick];
		}
		long primary = GLFW.glfwGetPrimaryMonitor();
		return primary == 0L ? null : monitorRect(primary);
	}

	private static int[] monitorRect(long monitor) {
		GLFWVidMode mode = GLFW.glfwGetVideoMode(monitor);
		if (mode == null) {
			return null;
		}
		int[] mx = new int[1], my = new int[1];
		GLFW.glfwGetMonitorPos(monitor, mx, my);
		return new int[] {mx[0], my[0], mode.width(), mode.height()};
	}
}
