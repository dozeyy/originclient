package com.origin.client.client.render;

/**
 * The smoothed scene clock every animated part of Origin's loading scenes reads (the
 * progress bar's glide and sweep, the loading -> title hand-off, OriginFrameLog).
 *
 * <p>Why not the raw wall clock: while Minecraft loads, frames arrive unevenly (one takes
 * 16 ms, the next 250 ms), and wall-clock motion turns every slow frame into a visible skip
 * (Will 2026-10-18: "the loading bar isn't smooth"). This clock steps at most about one usual
 * frame at a time and quietly earns the rest back over the next smooth frames. A real freeze
 * (the game finishing its load, the main menu's first frame) is different: its time is
 * dropped, so after it the motion carries on from where it stopped, at normal speed, instead
 * of jumping ahead or racing to catch up (Will 2026-10-18: "it freezes and messes up the
 * animation"). Render thread only.
 *
 * <p>1.8.9: verbatim from 1.21.1 (pure Java 8, no Minecraft types). It is what is left of
 * the old RingField: the rings are gone (Will, 2026-10-25: the only ring shapes are inside
 * origin_logo.png), the clock still times the loading bar and the startup hand-off.
 */
public final class SceneClock {
	/** Longest single step of the scene clock (ms) on a fast machine: one 30 fps frame. */
	private static final double STEP_MS = 33.0;
	/** How much faster than real time the clock may run while it catches up. */
	private static final double CATCH_UP = 0.3;
	/** Most time it ever owes (ms): enough to earn back an uneven frame, never a freeze. */
	private static final double DEBT_CAP_MS = 250.0;
	/** A gap longer than this (and well past the machine's usual frame) is a freeze, not an uneven frame. */
	private static final double FREEZE_MS = 250.0;
	private static long clockNanos;
	private static boolean clockStarted;
	private static double clockMs;
	private static double clockDebt;
	// The machine's usual frame (ms), followed slowly and spike-proof, so a PC that
	// is steadily slow runs the scene in real time rather than in slow motion.
	private static double typicalMs = 16.0;

	private SceneClock() {
	}

	/**
	 * The smoothed scene clock in ms (arbitrary origin). Every animated part of an
	 * Origin loading scene (the bar's fill and sweep, the loading -> title hand-off)
	 * reads this, so they all glide through the same uneven frames
	 * together. Safe to call any number of times per frame.
	 */
	public static double sceneMs() {
		// nanoTime, not the wall clock: it never steps back or jumps when the
		// system clock is adjusted.
		long now = System.nanoTime();
		if (!clockStarted) {
			clockStarted = true;
			clockNanos = now;
		}
		double raw = Math.max(0L, now - clockNanos) / 1_000_000.0;
		clockNanos = now;
		if (raw > 0.0) {
			// Weighted by the gap's length: sceneMs() is read several times a frame, and
			// those tiny in-frame gaps must not count as "frames" (~1 s to follow a change).
			double weight = Math.min(0.2, raw / 1000.0);
			typicalMs += (Math.min(raw, typicalMs * 2.0 + 8.0) - typicalMs) * weight;
			double cap = Math.max(STEP_MS, typicalMs * 1.5);
			if (raw > Math.max(FREEZE_MS, cap * 4.0)) {
				// A freeze: one usual frame of motion, nothing owed.
				clockMs += Math.min(raw, typicalMs);
			} else {
				clockDebt = Math.min(DEBT_CAP_MS, clockDebt + raw);
				double step = Math.min(clockDebt, Math.min(raw, cap) * (1.0 + CATCH_UP));
				clockDebt -= step;
				clockMs += step;
			}
		}
		return clockMs;
	}

	/** The scene clock's current value without advancing it (diagnostics: OriginFrameLog). */
	public static double peekMs() {
		return clockMs;
	}
}
