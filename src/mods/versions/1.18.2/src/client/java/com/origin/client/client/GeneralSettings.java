package com.origin.client.client;

import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWVidMode;

// Applies the SETTINGS > General options that need to run against the live
// window every tick: Borderless Fullscreen (GLFW window mode) and Raw Mouse
// Input (GLFW raw motion). Both are edge-/state-driven and fully fail-soft —
// any GLFW hiccup is swallowed so a bad window state can never crash the game.
public final class GeneralSettings {
	// Borderless is edge-triggered so we never fight the user's own window
	// dragging: we only act when the toggle's value actually changes.
	private static Boolean lastBorderless = null;
	private static boolean borderlessApplied = false;
	private static int savedX, savedY, savedW, savedH;

	private GeneralSettings() {
	}

	static void tick(Minecraft mc) {
		applyBorderless(mc);
		applyTransparentMenus();
		applyRawMouseInput(mc);
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
		if (lastBorderless != null && lastBorderless == want) {
			return; // only act on a real change
		}
		lastBorderless = want;
		long win;
		try {
			win = mc.getWindow().getWindow();
		} catch (Throwable t) {
			return;
		}
		// Never fight vanilla's exclusive fullscreen (F11): let that own the
		// window; borderless applies from the windowed state only.
		if (want && !borderlessApplied) {
			if (mc.options.fullscreen) {
				lastBorderless = null; // retry once we're back in windowed mode
				return;
			}
			try {
				int[] x = new int[1], y = new int[1], w = new int[1], h = new int[1];
				GLFW.glfwGetWindowPos(win, x, y);
				GLFW.glfwGetWindowSize(win, w, h);
				savedX = x[0];
				savedY = y[0];
				savedW = w[0];
				savedH = h[0];
				long monitor = GLFW.glfwGetPrimaryMonitor();
				GLFWVidMode mode = GLFW.glfwGetVideoMode(monitor);
				if (mode == null) {
					return;
				}
				int[] mx = new int[1], my = new int[1];
				GLFW.glfwGetMonitorPos(monitor, mx, my);
				GLFW.glfwSetWindowAttrib(win, GLFW.GLFW_DECORATED, GLFW.GLFW_FALSE);
				GLFW.glfwSetWindowMonitor(win, 0L, mx[0], my[0], mode.width(), mode.height(), GLFW.GLFW_DONT_CARE);
				borderlessApplied = true;
			} catch (Throwable t) {
				// fail-soft: leave the window as-is
			}
		} else if (!want && borderlessApplied) {
			try {
				GLFW.glfwSetWindowAttrib(win, GLFW.GLFW_DECORATED, GLFW.GLFW_TRUE);
				GLFW.glfwSetWindowMonitor(win, 0L, savedX, savedY,
						Math.max(320, savedW), Math.max(240, savedH), GLFW.GLFW_DONT_CARE);
				borderlessApplied = false;
			} catch (Throwable t) {
				// fail-soft
			}
		}
	}

	private static void applyRawMouseInput(Minecraft mc) {
		try {
			if (!GLFW.glfwRawMouseMotionSupported() || !mc.mouseHandler.isMouseGrabbed()) {
				return;
			}
			boolean on = Mods.bool(Mods.GENERAL_ID, "rawMouseInput");
			GLFW.glfwSetInputMode(mc.getWindow().getWindow(), GLFW.GLFW_RAW_MOUSE_MOTION,
					on ? GLFW.GLFW_TRUE : GLFW.GLFW_FALSE);
		} catch (Throwable t) {
			// fail-soft
		}
	}
}
