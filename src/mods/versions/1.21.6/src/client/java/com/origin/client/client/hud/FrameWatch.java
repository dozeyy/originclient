package com.origin.client.client.hud;

import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

// Two cheap per-frame jobs, both driven by GameRendererFrameMixin on the render thread.
//
// 1. Background mode. idle() is true only while the window is minimised. Origin's own
//    optional drawing (HUD elements, cosmetic sparkles) checks it and skips: nobody can
//    see them. An unfocused window is NOT idle: a game left on a second monitor while
//    the player clicks Discord or OBS is still on screen, and its HUD (keystrokes, CPS on
//    a stream) must stay (Will's QoL call 2026-11-04). Limit Unfocused FPS only caps the
//    frame rate (MinecraftFramerateMixin / FramerateLimitTrackerMixin). Never touches the
//    game tick, the integrated server or anything that changes game state.
//
// 2. Hitch log. FrameStats spots a long frame (>= 40 ms and well over the running
//    average); this writes one line to latest.log with a coarse cause: "gc" (the
//    JVM paused to clean memory), "screen-open <Name>" (a screen was opened in the
//    last few frames: first-draw / lazy-init cost), "loading" (no world yet) or
//    "other" (chunk loading, shader compile, disk: not distinguishable from here).
//    At most one line per second and 300 per run, never on screen. Frames while
//    unfocused are not logged (the cap makes them long on purpose).
public final class FrameWatch {
	private FrameWatch() {
	}

	private static final long MIN_GAP_MS = 1000;
	private static final int MAX_LINES = 300;
	private static final long SCREEN_WINDOW_NS = 250_000_000L;

	private static boolean idle;
	private static boolean capped;   // frames throttled on purpose (minimised, or the unfocused cap)
	private static boolean hooked;
	private static Screen lastScreen;
	private static long screenChangedAt;
	private static long lastLogMs;
	private static int lines;
	private static int suppressed;

	/** True while the game window is minimised (nothing of it is visible). */
	public static boolean idle() {
		return idle;
	}

	/** Render thread, once per frame, at the head of GameRenderer.render. */
	public static void onFrame(Minecraft mc) {
		if (!hooked) {
			hooked = true;
			FrameStats.setSpikeSink(FrameWatch::logSpike);
		}
		boolean minimised = false;
		try {
			minimised = org.lwjgl.glfw.GLFW.glfwGetWindowAttrib(mc.getWindow().getWindow(), org.lwjgl.glfw.GLFW.GLFW_ICONIFIED) != 0;
		} catch (Throwable ignored) {
			// no window attribute: treat as visible (the HUD keeps drawing)
		}
		idle = minimised;
		capped = minimised || (!mc.isWindowActive() && Mods.bool(Mods.PERFORMANCE_ID, "limitUnfocusedFps"));
		Screen sc = mc.screen;
		if (sc != lastScreen) {
			lastScreen = sc;
			if (sc != null) {
				screenChangedAt = System.nanoTime();
			}
		}
	}

	private static void logSpike(float ms, float avgMs, byte kind) {
		try {
			if (capped || lines >= MAX_LINES) {
				return;
			}
			long now = System.currentTimeMillis();
			if (now - lastLogMs < MIN_GAP_MS) {
				suppressed++;
				return;
			}
			Minecraft mc = Minecraft.getInstance();
			String cause;
			if (kind == FrameStats.GC) {
				cause = "gc";
			} else if (lastScreen != null && System.nanoTime() - screenChangedAt < SCREEN_WINDOW_NS) {
				cause = "screen-open " + lastScreen.getClass().getSimpleName();
			} else if (mc.level == null) {
				cause = "loading";
			} else {
				cause = "other";
			}
			lastLogMs = now;
			lines++;
			com.origin.client.OriginClient.LOGGER.info("[OriginFrame] hitch {} ms (avg {} ms) cause={}{}",
					Math.round(ms), Math.round(avgMs), cause, suppressed > 0 ? " (+" + suppressed + " more since last line)" : "");
			suppressed = 0;
		} catch (Throwable ignored) {
			// a log line must never cost a frame
		}
	}
}
