package com.origin.client.client;

import com.origin.client.client.theme.OriginTheme;

/**
 * The game half of Origin's launch journey (docs/MOTION.md). Loading -> title:
 * when the startup resource load finishes, the Origin loading screen dissolves
 * over {@link #REVEAL_MS} into the title screen (LoadingOverlayRevealMixin)
 * and the title's mark and buttons start their entrance at that moment, not
 * behind the loading screen. The launcher's Motion.Intro mirrors REVEAL_MS.
 *
 * Entering a world has no transition on purpose (Will 2026-10-18: "like normal
 * once the world loads") — the world appears the frame it is ready.
 *
 * Render thread only. The clock is a plain timestamp: nothing ticks between
 * transitions, so a finished transition costs nothing.
 */
public final class StartupTransition {
	/** Loading -> title dissolve. Mirrors the launcher's Motion.Intro. */
	public static final long REVEAL_MS = 560;

	// System.currentTimeMillis() of the startup reveal's first frame; 0 = none yet.
	private static long titleRevealAt = 0;

	private StartupTransition() {
	}

	/** Cubic ease-out on 0..1 (clamped), the curve both halves use. */
	public static double ease(double t) {
		return OriginTheme.easeOut(Math.max(0.0, Math.min(1.0, t)));
	}

	/** The startup loading screen began dissolving into the title (first frame only counts). */
	public static void titleRevealed() {
		if (titleRevealAt == 0) {
			titleRevealAt = System.currentTimeMillis();
		}
	}

	/**
	 * When the title's entrance should count from: its own open time, or the
	 * startup reveal if that came later (the title is first opened behind the
	 * loading screen, where its entrance would play unseen).
	 */
	public static long titleIntroStart(long openedAt) {
		return Math.max(openedAt, titleRevealAt);
	}
}
