package com.origin.client.client.mods;

import com.origin.client.client.OriginClientMod;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

// Stopwatch mod state: a pausable wall-clock timer driven by the mod's two
// rebindable keys (Start/Stop, Reset), polled once per client tick the same
// way the Scoreboard/Nametag toggle keys are. Real time, not game ticks, so a
// lagging server or a paused singleplayer world never skews the reading.
public final class Stopwatch {
	private Stopwatch() {
	}

	private static boolean running = false;
	private static long startedAt = 0;      // System.nanoTime() of the current run's start
	private static long banked = 0;         // nanos accumulated by earlier runs
	private static boolean startWasDown = false, resetWasDown = false;

	public static synchronized boolean running() {
		return running;
	}

	/** Elapsed time in milliseconds, including the live run. */
	public static synchronized long elapsedMs() {
		long n = banked + (running ? System.nanoTime() - startedAt : 0);
		return n / 1_000_000L;
	}

	public static synchronized void toggle() {
		if (running) {
			banked += System.nanoTime() - startedAt;
			running = false;
		} else {
			startedAt = System.nanoTime();
			running = true;
		}
	}

	public static synchronized void reset() {
		running = false;
		banked = 0;
	}

	/** Edge-triggered key polling; keys only act with no screen open. */
	public static void tick(Minecraft mc) {
		if (!Mods.on("stopwatch")) {
			startWasDown = resetWasDown = false;
			return;
		}
		boolean start = mc.screen == null && OriginClientMod.isRawKeyDown(Mods.keyCode("stopwatch", "startKey"));
		boolean reset = mc.screen == null && OriginClientMod.isRawKeyDown(Mods.keyCode("stopwatch", "resetKey"));
		boolean msg = Mods.bool("stopwatch", "displayToggleMessage") && mc.player != null;
		if (start && !startWasDown) {
			toggle();
			if (msg) {
				mc.player.displayClientMessage(Component.literal(running() ? "Stopwatch started" : "Stopwatch stopped"), true);
			}
		}
		if (reset && !resetWasDown) {
			reset();
			if (msg) {
				mc.player.displayClientMessage(Component.literal("Stopwatch reset"), true);
			}
		}
		startWasDown = start;
		resetWasDown = reset;
	}

	/** "1:02.34" (or "1:02" without hundredths); hours appear once reached. */
	public static String format(long ms, boolean hundredths) {
		long totalSec = ms / 1000;
		long h = totalSec / 3600, m = (totalSec / 60) % 60, s = totalSec % 60;
		String base = h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", m, s);
		return hundredths ? base + String.format(".%02d", (ms % 1000) / 10) : base;
	}
}
