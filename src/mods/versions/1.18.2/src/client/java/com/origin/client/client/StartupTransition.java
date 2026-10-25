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
 * Render thread only. Everything in the hand-off counts on ONE clock,
 * {@link #clockMs}: the scene clock the loading mark, its flight and the rings
 * run on. The dissolve, the flight and the title's entrance used to read two
 * different clocks, so a slow frame at the hand-off (the main menu's first
 * frame) moved one and not the other and the picture jumped (Will 2026-10-18).
 */
public final class StartupTransition {
	/** Loading -> title dissolve. Mirrors the launcher's Motion.Intro. */
	public static final long REVEAL_MS = 560;
	/** The title's entrance (the buttons' rise, TitleScreenMixin) is over this long after the reveal starts. */
	public static final long TITLE_ENTRANCE_MS = 1200;

	// clockMs() at the startup reveal's first frame; 0 = none yet.
	private static long titleRevealAt = 0;

	private StartupTransition() {
	}

	private static boolean renderWorkRanSeen = false;

	/** Dev frame log only: the first queued render-thread reload task is starting. */
	public static void reloadRenderWorkRan(String listener) {
		if (!renderWorkRanSeen && OriginFrameLog.ENABLED) {
			renderWorkRanSeen = true;
			OriginFrameLog.mark("render-thread reload work begins (" + listener + ")");
		}
	}

	/**
	 * Pre-1.19.3 (ReloadTaskTagMixin): a reload apply step submitted ON the render
	 * thread during the startup load is queued (Minecraft.tell) instead of letting
	 * Minecraft.execute run it inline, wherever the submitter happens to be (inside
	 * the buffer swap's render-call replay, for TextureManager). True = queued.
	 */
	public static boolean deferInlineApply(Runnable hop) {
		if (!com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread()) {
			return false;   // from a worker thread Minecraft.execute queues it anyway
		}
		net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
		if (mc == null || mc.level != null || !(mc.getOverlay() instanceof net.minecraft.client.gui.screens.LoadingOverlay)) {
			return false;
		}
		mc.tell(hop);
		return true;
	}

	/** Cubic ease-out on 0..1 (clamped), the curve both halves use. */
	public static double ease(double t) {
		return OriginTheme.easeOut(Math.max(0.0, Math.min(1.0, t)));
	}

	/** The startup loading screen began dissolving into the title (first frame only counts). */
	public static void titleRevealed() {
		if (titleRevealAt == 0) {
			titleRevealAt = clockMs();
			OriginFrameLog.mark("dissolve-start");
			OriginFrameLog.entranceEndsAt(titleRevealAt - 1 + TITLE_ENTRANCE_MS);
		}
	}

	/**
	 * The hand-off's clock in ms (never 0): SceneClock's scene clock, which glides
	 * through uneven frames and drops a freeze instead of jumping past it.
	 */
	public static long clockMs() {
		return 1L + (long) com.origin.client.client.render.SceneClock.sceneMs();
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
