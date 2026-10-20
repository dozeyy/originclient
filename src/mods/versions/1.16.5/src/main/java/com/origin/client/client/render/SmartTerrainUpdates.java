package com.origin.client.client.render;

import com.origin.client.client.mods.Mods;

import java.lang.invoke.MethodHandle;

/**
 * SETTINGS > Performance > Smart Terrain Updates -- NOT USED on this version (the toggle is hidden).
 *
 * <p>Why (javap of Sodium 0.2.0+mc1.16.5 and Iris 1.4.5+mc1.16.5, 2026-10-20): the 1.21.1 feature reuses
 * the main view's chunk-graph walk on frames where nothing moved, because Sodium 0.5/0.6 under Iris redo
 * that walk every frame. Sodium 0.2 already skips it by itself: SodiumWorldRenderer.updateChunks only calls
 * ChunkRenderManager.update when the graph is dirty, and marks it dirty only when the camera position,
 * pitch, yaw or fog distance changed (or a chunk was rebuilt). Iris's Sodium compat (shadow_map
 * MixinSodiumWorldRenderer) forces only the SHADOW walk every frame. So a still main view already pays no
 * walk: nothing to reuse.
 *
 * <p>What is left here: the F3 line that says so when a stored "on" value reaches this version (settings
 * sync), and dev-only walk counters that dev-shots logs as the proof ([smart-terrain] lines: main walks per
 * frame standing still must be ~0).
 */
public final class SmartTerrainUpdates {
	private SmartTerrainUpdates() {
	}

	public static final String KEY_ON = "smartTerrainUpdates";

	private static final boolean DEV = System.getenv("ORIGIN_SHOTS") != null;
	private static final MethodHandle IS_SHADOW_PASS = IrisState.SHADOW_PASS;

	/** Dev totals: RenderSectionManager.update calls (= chunk-graph walks) per pass and their CPU time. */
	public static long totalMainWalks, totalShadowWalks, totalMainNanos, totalShadowNanos;
	private static long walkStart;
	private static boolean walkShadow;

	/** Sodium RenderSectionManager.update HEAD (SodiumShadowWalkMixin). Dev only. */
	public static void walkBegin() {
		if (!DEV) {
			return;
		}
		try {
			walkShadow = IS_SHADOW_PASS != null && (boolean) IS_SHADOW_PASS.invokeExact();
		} catch (Throwable t) {
			walkShadow = false;
		}
		walkStart = System.nanoTime();
	}

	/** Sodium RenderSectionManager.update RETURN. Dev only. */
	public static void walkEnd() {
		if (!DEV || walkStart == 0) {
			return;
		}
		long ns = System.nanoTime() - walkStart;
		walkStart = 0;
		if (walkShadow) {
			totalShadowWalks++;
			totalShadowNanos += ns;
		} else {
			totalMainWalks++;
			totalMainNanos += ns;
		}
	}

	/** One F3 line only when a stored "on" reaches this version; null otherwise. */
	public static String debugLine() {
		if (!Mods.bool(Mods.PERFORMANCE_ID, KEY_ON)) {
			return null;
		}
		return "Origin Smart Terrain Updates: not needed on this version (Sodium already skips the chunk walk while nothing moves)";
	}
}
