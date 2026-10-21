package com.origin.client.client.hud;

import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

// Two cheap per-frame jobs, both driven by GameRendererFrameMixin on the render thread.
//
// 1. Background mode. idle() is true while the window is not the active one and
//    Settings > Performance > Limit Unfocused FPS is on (the same switch that caps
//    the frame rate, MinecraftFramerateMixin). Origin's own optional drawing (HUD
//    elements, cosmetic sparkles) checks it and skips: nobody is looking, so the
//    frames the cap leaves should not be spent on them. Never touches the game
//    tick, the integrated server or anything that changes game state.
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
	private static boolean hooked;
	private static Screen lastScreen;
	private static long screenChangedAt;
	private static long lastLogMs;
	private static int lines;
	private static int suppressed;

	/** True while the window is unfocused and Limit Unfocused FPS is on. */
	public static boolean idle() {
		return idle;
	}

	/** Render thread, once per frame, at the head of GameRenderer.render. */
	public static void onFrame(Minecraft mc) {
		if (!hooked) {
			hooked = true;
			FrameStats.setSpikeSink(FrameWatch::logSpike);
		}
		idle = !mc.isWindowActive() && Mods.bool(Mods.PERFORMANCE_ID, "limitUnfocusedFps");
		Screen sc = mc.gui.screen(); // 26.2+: the open screen lives on Gui
		if (sc != lastScreen) {
			lastScreen = sc;
			if (sc != null) {
				screenChangedAt = System.nanoTime();
			}
		}
	}

	private static void logSpike(float ms, float avgMs, byte kind) {
		try {
			if (idle || lines >= MAX_LINES) {
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
