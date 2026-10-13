package com.origin.client.client;

import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import org.lwjgl.sdl.SDLHints;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.sdl.SDL_Rect;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;

// Applies the SETTINGS > General options that need to run against the live
// window every tick: Borderless Fullscreen (window mode) and Raw Mouse Input.
// Both are edge-/state-driven and fully fail-soft — any window-system hiccup is
// swallowed so a bad window state can never crash the game.
//
// PER-VERSION DELTA (26.3): Minecraft moved from GLFW to SDL3, so Window.handle()
// is an SDL_Window* and the window calls are SDLVideo's. Raw input: SDL3's
// relative mouse mode (what Minecraft uses while the mouse is grabbed) already
// reads unaccelerated device motion unless SDL_HINT_MOUSE_RELATIVE_SYSTEM_SCALE
// asks for the OS pointer scaling -- so the toggle maps onto that hint (on = raw,
// the SDL default; off = OS scaling, the old "raw input off" feel).
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
		applyRawMouseInput(mc);
	}

	private static void applyBorderless(Minecraft mc) {
		boolean want = Mods.bool(Mods.GENERAL_ID, "borderlessFullscreen");
		if (lastBorderless != null && lastBorderless == want) {
			return; // only act on a real change
		}
		lastBorderless = want;
		long win;
		try {
			win = mc.getWindow().handle();
		} catch (Throwable t) {
			return;
		}
		// Never fight vanilla's exclusive fullscreen (F11): let that own the
		// window; borderless applies from the windowed state only.
		if (want && !borderlessApplied) {
			if (mc.options.fullscreen().get()) {
				lastBorderless = null; // retry once we're back in windowed mode
				return;
			}
			try (MemoryStack stack = MemoryStack.stackPush()) {
				IntBuffer x = stack.mallocInt(1), y = stack.mallocInt(1), w = stack.mallocInt(1), h = stack.mallocInt(1);
				SDLVideo.SDL_GetWindowPosition(win, x, y);
				SDLVideo.SDL_GetWindowSize(win, w, h);
				savedX = x.get(0);
				savedY = y.get(0);
				savedW = w.get(0);
				savedH = h.get(0);
				int display = SDLVideo.SDL_GetDisplayForWindow(win);
				if (display == 0) {
					display = SDLVideo.SDL_GetPrimaryDisplay();
				}
				SDL_Rect bounds = SDL_Rect.malloc(stack);
				if (display == 0 || !SDLVideo.SDL_GetDisplayBounds(display, bounds)) {
					return;
				}
				SDLVideo.SDL_SetWindowBordered(win, false);
				SDLVideo.SDL_SetWindowPosition(win, bounds.x(), bounds.y());
				SDLVideo.SDL_SetWindowSize(win, bounds.w(), bounds.h());
				borderlessApplied = true;
			} catch (Throwable t) {
				// fail-soft: leave the window as-is
			}
		} else if (!want && borderlessApplied) {
			try {
				SDLVideo.SDL_SetWindowBordered(win, true);
				SDLVideo.SDL_SetWindowSize(win, Math.max(320, savedW), Math.max(240, savedH));
				SDLVideo.SDL_SetWindowPosition(win, savedX, savedY);
				borderlessApplied = false;
			} catch (Throwable t) {
				// fail-soft
			}
		}
	}

	private static Boolean lastRaw = null;

	private static void applyRawMouseInput(Minecraft mc) {
		try {
			boolean on = Mods.bool(Mods.GENERAL_ID, "rawMouseInput");
			if (lastRaw != null && lastRaw == on) {
				return; // a hint only needs setting when the toggle changes
			}
			lastRaw = on;
			SDLHints.SDL_SetHint("SDL_MOUSE_RELATIVE_SYSTEM_SCALE", on ? "0" : "1");
		} catch (Throwable t) {
			// fail-soft
		}
	}
}
