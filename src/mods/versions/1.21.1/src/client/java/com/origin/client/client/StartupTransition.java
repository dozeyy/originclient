package com.origin.client.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.origin.client.client.theme.OriginTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.opengl.GL11;

/**
 * The game half of Origin's launch journey (docs/MOTION.md). The launcher's
 * Motion.Intro / Motion.Handoff durations (Theme/Spacing.xaml) are mirrored
 * here so the two halves move at the same pace:
 *
 * <ul>
 *   <li>Loading -> title: when the startup resource load finishes, the Origin
 *       loading screen dissolves over {@link #REVEAL_MS} into the title screen
 *       (LoadingOverlayRevealMixin) and the title's mark and buttons start their
 *       entrance at that moment, not behind the loading screen.</li>
 *   <li>Menu -> world: the first world frames rise out of the loading scene's
 *       charcoal over {@link #WORLD_MS}. Drawn over the HUD only — the player can
 *       move from the first frame, and any error screen draws above it.</li>
 * </ul>
 *
 * Render thread only. Every clock here is a plain timestamp: nothing ticks
 * between transitions, so a finished transition costs nothing.
 */
public final class StartupTransition {
	/** Loading -> title dissolve. Mirrors the launcher's Motion.Intro. */
	public static final long REVEAL_MS = 560;
	/** Loading scene -> first world frames. Mirrors the launcher's Motion.Handoff. */
	public static final long WORLD_MS = 320;

	// System.currentTimeMillis() of the startup reveal's first frame; 0 = none yet.
	private static long titleRevealAt = 0;
	// A world-loading scene was on screen; the next in-world frame with no screen starts the fade.
	private static boolean worldPending = false;
	private static long worldFadeAt = 0;

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

	/** A world-loading scene (level load, dimension change, joining a server) drew this frame. */
	public static void worldLoading() {
		worldPending = true;
		worldFadeAt = 0;
	}

	/** End of the HUD: lift the loading scene's charcoal off the first world frames. */
	public static void drawWorldEntry(GuiGraphics guiGraphics) {
		if (!worldPending && worldFadeAt == 0) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return;                         // not in a world yet; the next entry re-arms anyway
		}
		if (worldPending) {
			if (mc.screen != null) {
				return;                     // still on a loading/connect screen
			}
			worldPending = false;
			worldFadeAt = System.currentTimeMillis();
		}
		long el = System.currentTimeMillis() - worldFadeAt;
		if (el >= WORLD_MS) {
			worldFadeAt = 0;
			return;
		}
		int alpha = (int) Math.round((1.0 - ease(el / (double) WORLD_MS)) * 255);
		if (alpha > 0) {
			// The HUD's layers sit at rising z (LayeredDraw), so a plain fill at z 0
			// would lose the depth test to the hotbar, chat and Origin HUD and they'd
			// show at full strength through the charcoal. Draw what the HUD queued,
			// clear depth (GameRenderer clears it again right after the HUD anyway,
			// so screens are unaffected), then cover everything.
			guiGraphics.flush();
			RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
			guiGraphics.fill(0, 0, guiGraphics.guiWidth(), guiGraphics.guiHeight(), (alpha << 24) | (OriginTheme.BG & 0xFFFFFF));
			guiGraphics.flush();
		}
	}
}
