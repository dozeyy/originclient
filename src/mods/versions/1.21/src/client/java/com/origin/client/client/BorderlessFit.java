package com.origin.client.client;

// Borderless Fullscreen's window maths, free of Minecraft and of the window library
// (GLFW or SDL3), so tools/borderless-tests runs it without a game. Java 8 source:
// the same file ships in every GLFW/SDL module, 1.16.5 included, byte-identical.
//
// Why a guard at all (Will, 2026-11-04): a game started with Borderless already on
// applies it on its first tick, while the window is still being set up, and on his
// Windows PC (display scaling 133%) the window then ended up borderless but only
// covering part of the monitor until the toggle was flipped off and on. So Origin no
// longer trusts one apply: while Borderless is on, GeneralSettings checks twice a
// second that the window still covers its monitor and fits it again if not. The
// check is paced and capped (Guard) so it can never fight the window in a loop.
public final class BorderlessFit {
	private BorderlessFit() {
	}

	/**
	 * The monitor the window belongs on, as an index into {@code monitors} (each
	 * {x, y, w, h} in the window library's screen coordinates): the one holding the
	 * window's centre, else the one it overlaps most, else -1 (caller falls back to
	 * the primary monitor).
	 */
	public static int pickMonitor(int wx, int wy, int ww, int wh, int[][] monitors) {
		if (monitors == null) {
			return -1;
		}
		long cx = (long) wx + ww / 2, cy = (long) wy + wh / 2;
		for (int i = 0; i < monitors.length; i++) {
			int[] m = monitors[i];
			if (m != null && m[2] > 0 && m[3] > 0
					&& cx >= m[0] && cx < (long) m[0] + m[2] && cy >= m[1] && cy < (long) m[1] + m[3]) {
				return i;
			}
		}
		int best = -1;
		long bestArea = 0;
		for (int i = 0; i < monitors.length; i++) {
			int[] m = monitors[i];
			if (m == null) {
				continue;
			}
			long ox = Math.min((long) wx + ww, (long) m[0] + m[2]) - Math.max(wx, m[0]);
			long oy = Math.min((long) wy + wh, (long) m[1] + m[3]) - Math.max(wy, m[1]);
			long area = ox > 0 && oy > 0 ? ox * oy : 0;
			if (area > bestArea) {
				bestArea = area;
				best = i;
			}
		}
		return best;
	}

	/** True when the window rect is exactly the monitor rect {x, y, w, h}. */
	public static boolean covers(int wx, int wy, int ww, int wh, int[] monitor) {
		return monitor != null && wx == monitor[0] && wy == monitor[1] && ww == monitor[2] && wh == monitor[3];
	}

	/**
	 * Paces the "still covering its monitor?" check and caps the re-fits. A check
	 * runs at most every CHECK_MS and never within SETTLE_MS of a fit (X11 and
	 * Wayland apply a resize a few frames later; checking sooner would re-fit a window
	 * that is about to be right). At most BURST_MAX re-fits per BURST_MS: if the
	 * window keeps refusing the monitor's size (a coordinate mismatch Origin cannot
	 * see), it stops trying instead of flickering. A real toggle resets everything.
	 */
	public static final class Guard {
		public static final long SETTLE_MS = 750;
		public static final long CHECK_MS = 500;
		public static final long BURST_MS = 10_000;
		public static final int BURST_MAX = 3;

		private boolean fittedOnce;
		private long lastFit;
		private boolean checkedOnce;
		private long lastCheck;
		private final long[] refits = new long[BURST_MAX];
		private int refitCount;

		/** The toggle changed (or borderless was applied fresh): start over. */
		public void reset(long now) {
			fittedOnce = true;
			lastFit = now;
			checkedOnce = false;
			refitCount = 0;
		}

		/** Time for a "still covering?" look? Records the look when it says yes. */
		public boolean shouldCheck(long now) {
			if (fittedOnce && now - lastFit < SETTLE_MS) {
				return false;
			}
			if (checkedOnce && now - lastCheck < CHECK_MS) {
				return false;
			}
			checkedOnce = true;
			lastCheck = now;
			return true;
		}

		/** The window drifted off its monitor: may Origin fit it again now? Records the re-fit when yes. */
		public boolean mayRefit(long now) {
			int recent = 0;
			for (int i = 0; i < refitCount; i++) {
				if (now - refits[i] < BURST_MS) {
					refits[recent++] = refits[i];
				}
			}
			refitCount = recent;
			if (refitCount >= BURST_MAX) {
				return false;
			}
			refits[refitCount++] = now;
			fittedOnce = true;
			lastFit = now;
			return true;
		}
	}
}
