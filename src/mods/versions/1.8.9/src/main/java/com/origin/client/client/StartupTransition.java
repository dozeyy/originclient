package com.origin.client.client;

import com.origin.client.client.theme.OriginTheme;

/**
 * The game half of Origin's launch journey (docs/MOTION.md). Loading -> title:
 * the Origin loading layer dissolves over {@link #REVEAL_MS} into the title
 * screen while the loading mark flies onto the title's logo spot, and the
 * title's buttons start their entrance at that moment. The launcher's
 * Motion.Intro mirrors REVEAL_MS.
 *
 * 1.8.9: the startup resource load is Forge's own splash (SplashProgress,
 * themed charcoal by the launcher's config/splash.properties), which owns the
 * GL context on its own thread, so nothing of Origin's can draw during it. The
 * loading layer therefore takes over the first title frame instead: the seed
 * blooms into the three circles on the splash's charcoal, then the dissolve +
 * flight above (OriginTitleScreen). That intro plays once per game start, and
 * only if the title is the first thing after the splash ({@link #claimIntro}).
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
	// 1.8.9: the startup intro is claimed (played, or given up) at most once.
	private static boolean introClaimed = false;

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

	/**
	 * 1.8.9: true exactly once per game start, for the caller that plays the
	 * startup intro. The client tick claims it away the moment a world exists
	 * (a launch straight into a server), so a later trip to the title never
	 * replays the loading screen.
	 */
	public static boolean claimIntro() {
		if (introClaimed) {
			return false;
		}
		introClaimed = true;
		return true;
	}
}
