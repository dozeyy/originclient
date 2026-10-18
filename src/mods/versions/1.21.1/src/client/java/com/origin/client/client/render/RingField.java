package com.origin.client.client.render;

/**
 * The one set of orbiting rings behind every Origin scene (launch experience v2,
 * docs/MOTION.md). The loading screen, the main menu and the world-loading
 * screens all draw THESE rings, so moving between them never swaps one picture
 * for another: the rings only change speed, drift and lean.
 *
 * <ul>
 *   <li>{@link Mood#LOADING}: full speed, dead centre. Phases start from the wall
 *       clock (angle0 + time / period), the same formula Origin Launcher's scene
 *       uses, so the game's first loading frame shows the rings exactly where the
 *       launcher's were.</li>
 *   <li>{@link Mood#MENU}: the rings slow to a third, drift on slow Lissajous paths
 *       and lean towards the mouse (front rings more than back ones: parallax).</li>
 * </ul>
 *
 * Each frame the active scene calls {@link #update} once with its mood; every
 * value eases towards that mood's target, so a scene change is a glide, never a
 * cut. Render thread only. Calling update twice in one frame is harmless (the
 * second call sees dt = 0).
 *
 * <p>Everything here moves on {@link #sceneMs}, not the raw wall clock: while
 * Minecraft loads, frames arrive unevenly (one takes 16 ms, the next 250 ms), and
 * wall-clock motion turns every slow frame into a visible skip (Will 2026-10-18:
 * "the rings are jumpy and the loading bar isn't smooth"). The scene clock steps
 * at most about one usual frame at a time and quietly earns the rest back over the
 * next smooth frames. A real freeze (the game finishing its load, the main menu's
 * first frame) is different: its time is dropped, so after it the motion carries
 * on from where it stopped, at normal speed, instead of jumping ahead or racing
 * to catch up (Will 2026-10-18: "it freezes and messes up the animation").
 */
public final class RingField {
	public enum Mood { LOADING, MENU }

	/** Menu speed as a fraction of the loading orbit's. */
	private static final double MENU_SPEED = 0.33;
	/** Easing time constants (ms): speed, drift/lean amount, the lean itself. */
	private static final double SPEED_TAU = 650.0;
	private static final double CALM_TAU = 520.0;
	private static final double LEAN_TAU = 260.0;
	/** Furthest a ring leans, as a fraction of screen width, per depth (ring 0 = front). */
	private static final double[] LEAN = {0.030, 0.022, 0.015, 0.010};
	/** Drift amplitude, fraction of screen width, per depth. */
	private static final double[] DRIFT = {0.010, 0.008, 0.006, 0.005};

	private static double[] phase;     // degrees, per ring
	private static double speed = 1.0;
	private static double calm = 0.0;  // 0 = loading (centred), 1 = menu (drifting + leaning)
	private static double leanX, leanY; // -0.5..0.5, eased mouse position from centre
	private static double clockS;      // menu-time seconds for the drift paths
	private static double lastScene;   // sceneMs() at the last update

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

	private RingField() {
	}

	/**
	 * Advances the field to now. {@code mouseNx/Ny} are the cursor's position as
	 * 0..1 of the screen (ignored for LOADING).
	 */
	public static void update(Mood mood, double[] angle0, double[] periodS, boolean[] reverse,
							  double mouseNx, double mouseNy) {
		double scene = sceneMs();
		int n = angle0.length;
		if (phase == null || phase.length != n) {
			phase = new double[n];
			long now = System.currentTimeMillis();
			for (int i = 0; i < n; i++) {
				phase[i] = wallClockAngle(now, angle0[i], periodS[i], reverse[i]);
			}
			lastScene = scene;
		}
		double dt = scene - lastScene;
		lastScene = scene;

		boolean menu = mood == Mood.MENU;
		speed = approach(speed, menu ? MENU_SPEED : 1.0, dt, SPEED_TAU);
		calm = approach(calm, menu ? 1.0 : 0.0, dt, CALM_TAU);
		double tx = menu ? clamp(mouseNx) - 0.5 : 0.0;
		double ty = menu ? clamp(mouseNy) - 0.5 : 0.0;
		leanX = approach(leanX, tx, dt, LEAN_TAU);
		leanY = approach(leanY, ty, dt, LEAN_TAU);
		clockS += dt / 1000.0 * Math.max(calm, 0.0001);

		for (int i = 0; i < n; i++) {
			double step = dt / 1000.0 / periodS[i] * 360.0 * speed;
			phase[i] = (phase[i] + (reverse[i] ? -step : step)) % 360.0;
		}
	}

	/**
	 * The smoothed scene clock in ms (arbitrary origin). Every animated part of an
	 * Origin loading scene — the rings, the bar's fill and sweep, the wordmark
	 * build — reads this, so they all glide through the same uneven frames
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

	// TEMPORARY (HandoffProbe): read-only views of the scene clock.
	static double probeClock() {
		return clockMs;
	}

	static double probeDebt() {
		return clockDebt;
	}

	/** Ring i's rotation in degrees. Wall-clock exact until the field first leaves LOADING. */
	public static double angle(int i, double angle0, double periodS, boolean reverse) {
		if (phase == null || i >= phase.length) {
			return wallClockAngle(System.currentTimeMillis(), angle0, periodS, reverse);
		}
		return phase[i];
	}

	/** Ring i's centre offset from screen centre, in GUI units (x). */
	public static double offsetX(int i, int w) {
		int d = Math.min(i, LEAN.length - 1);
		double drift = Math.sin(clockS * 2 * Math.PI / (23.0 + 7.0 * i) + i * 1.7) * DRIFT[d];
		return (leanX * 2.0 * LEAN[d] + drift) * w * calm;
	}

	/** Ring i's centre offset from screen centre, in GUI units (y). */
	public static double offsetY(int i, int w) {
		int d = Math.min(i, LEAN.length - 1);
		double drift = Math.cos(clockS * 2 * Math.PI / (31.0 + 5.0 * i) + i * 0.9) * DRIFT[d] * 0.7;
		return (leanY * 2.0 * LEAN[d] * 0.7 + drift) * w * calm;
	}

	/** The formula Origin Launcher's loading scene uses too (LoadingScene.cs) — keep them identical. */
	public static double wallClockAngle(long nowMs, double angle0, double periodS, boolean reverse) {
		double revs = (nowMs / 1000.0) / periodS;
		return (angle0 + (reverse ? -revs : revs) * 360.0) % 360.0;
	}

	private static double approach(double value, double target, double dt, double tau) {
		return target + (value - target) * Math.exp(-dt / tau);
	}

	private static double clamp(double v) {
		return Double.isFinite(v) ? Math.max(0.0, Math.min(1.0, v)) : 0.5;
	}
}
