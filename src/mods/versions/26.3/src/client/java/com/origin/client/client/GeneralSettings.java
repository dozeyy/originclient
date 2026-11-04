package com.origin.client.client;

import com.origin.client.OriginClient;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.sdl.SDL_Rect;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;

// Applies the SETTINGS > General options that need to run against the live
// window every tick: Borderless Fullscreen (window mode). Acts on a toggle change,
// then keeps an applied borderless window covering its display. Fully fail-soft:
// any window-system hiccup is swallowed so a bad window state can never crash the
// game.
//
// PER-VERSION DELTA (26.3): Minecraft moved from GLFW to SDL3, so Window.handle()
// is an SDL_Window* and the window calls are SDLVideo's. Raw mouse input is left
// to Minecraft on every version but 1.8.9 (Will, 2026-11-03): SDL3's relative
// mouse mode (what Minecraft uses while the mouse is grabbed) already reads
// unaccelerated device motion, so Origin no longer touches its hint.
public final class GeneralSettings {
	// Borderless enters/leaves only on a real change of the toggle. In between,
	// an applied borderless window (no frame, so nothing for the player to drag)
	// is only checked to still cover its display (keepCovering, paced by guard).
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
			win = mc.getWindow().handle();
		} catch (Throwable t) {
			return;
		}
		if (lastBorderless == null || lastBorderless != want) {
			lastBorderless = want;
			// Never fight vanilla's fullscreen (F11): let that own the window;
			// borderless applies from the windowed state only.
			if (want && !borderlessApplied) {
				if (mc.options.fullscreen().get()) {
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
		try (MemoryStack stack = MemoryStack.stackPush()) {
			// A maximised window first goes back to its normal size: SDL3 ignores a
			// size request on a maximised window, and the normal size is the one to
			// return to when Borderless goes off.
			restoreIfMaximised(win);
			IntBuffer x = stack.mallocInt(1), y = stack.mallocInt(1), w = stack.mallocInt(1), h = stack.mallocInt(1);
			SDLVideo.SDL_GetWindowPosition(win, x, y);
			SDLVideo.SDL_GetWindowSize(win, w, h);
			int[] bounds = displayBounds(win);
			if (bounds == null) {
				return;
			}
			savedX = x.get(0);
			savedY = y.get(0);
			savedW = w.get(0);
			savedH = h.get(0);
			fit(win, bounds);
			borderlessApplied = true;
			guard.reset(System.currentTimeMillis());
			coverReported = false;
			OriginClient.LOGGER.info("Origin: borderless on: window {},{} {}x{} -> display {},{} {}x{}",
					savedX, savedY, savedW, savedH, bounds[0], bounds[1], bounds[2], bounds[3]);
		} catch (Throwable t) {
			// fail-soft: leave the window as-is
		}
	}

	private static void leaveBorderless(long win) {
		try {
			SDLVideo.SDL_SetWindowBordered(win, true);
			SDLVideo.SDL_SetWindowSize(win, Math.max(320, savedW), Math.max(240, savedH));
			SDLVideo.SDL_SetWindowPosition(win, savedX, savedY);
			borderlessApplied = false;
		} catch (Throwable t) {
			// fail-soft
		}
	}

	// While Borderless is on, the window must keep covering its display. A game that
	// starts with it on applies it on the first tick, mid start-up; anything that
	// resizes the window after that (Will, 2026-11-04: borderless but on part of the
	// screen until toggled off and on) is undone here. Paced and capped by
	// BorderlessFit.Guard; skipped while vanilla's fullscreen or a minimise owns the
	// window. The log line is the evidence trail when it does fire.
	private static void keepCovering(Minecraft mc, long win) {
		long now = System.currentTimeMillis();
		if (!guard.shouldCheck(now)) {
			return;
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long flags = SDLVideo.SDL_GetWindowFlags(win);
			if (mc.options.fullscreen().get()
					|| (flags & (SDLVideo.SDL_WINDOW_FULLSCREEN | SDLVideo.SDL_WINDOW_MINIMIZED)) != 0L) {
				return;
			}
			IntBuffer x = stack.mallocInt(1), y = stack.mallocInt(1), w = stack.mallocInt(1), h = stack.mallocInt(1);
			SDLVideo.SDL_GetWindowPosition(win, x, y);
			SDLVideo.SDL_GetWindowSize(win, w, h);
			int[] bounds = displayBounds(win);
			boolean framed = (flags & SDLVideo.SDL_WINDOW_BORDERLESS) == 0L;
			boolean maximised = (flags & SDLVideo.SDL_WINDOW_MAXIMIZED) != 0L;
			if (bounds == null) {
				return;
			}
			if (!framed && !maximised && BorderlessFit.covers(x.get(0), y.get(0), w.get(0), h.get(0), bounds)) {
				if (!coverReported) {
					coverReported = true;
					OriginClient.LOGGER.info("Origin: borderless window covers its display {},{} {}x{}", x.get(0), y.get(0), w.get(0), h.get(0));
				}
				return;
			}
			if (guard.mayRefit(now)) {
				OriginClient.LOGGER.info("Origin: borderless window was at {},{} {}x{}{}; fitting it to its display {},{} {}x{} again",
						x.get(0), y.get(0), w.get(0), h.get(0), framed ? " with a frame" : "", bounds[0], bounds[1], bounds[2], bounds[3]);
				fit(win, bounds);
			}
		} catch (Throwable t) {
			// fail-soft
		}
	}

	private static void fit(long win, int[] bounds) {
		restoreIfMaximised(win);
		SDLVideo.SDL_SetWindowBordered(win, false);
		SDLVideo.SDL_SetWindowPosition(win, bounds[0], bounds[1]);
		SDLVideo.SDL_SetWindowSize(win, bounds[2], bounds[3]);
	}

	private static void restoreIfMaximised(long win) {
		if ((SDLVideo.SDL_GetWindowFlags(win) & SDLVideo.SDL_WINDOW_MAXIMIZED) != 0L) {
			SDLVideo.SDL_RestoreWindow(win);
			SDLVideo.SDL_SyncWindow(win); // the restore must land before the size request
		}
	}

	// {x, y, w, h} of the display the window is on (SDL picks it by the window's
	// centre, like BorderlessFit.pickMonitor), else the primary; null if neither.
	private static int[] displayBounds(long win) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			int display = SDLVideo.SDL_GetDisplayForWindow(win);
			if (display == 0) {
				display = SDLVideo.SDL_GetPrimaryDisplay();
			}
			SDL_Rect bounds = SDL_Rect.malloc(stack);
			if (display == 0 || !SDLVideo.SDL_GetDisplayBounds(display, bounds)) {
				return null;
			}
			return new int[] {bounds.x(), bounds.y(), bounds.w(), bounds.h()};
		}
	}
}
