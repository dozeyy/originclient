package com.origin.client.client.render;

import java.lang.invoke.MethodHandle;

/**
 * PER-VERSION (26.1): why there is no "Smart Terrain Updates" toggle here, and the counters that prove it.
 *
 * <p>On 1.21.1 (Sodium 0.6.13 + Iris 1.8) Iris's shadow pass marked Sodium's chunk graph dirty every
 * frame, so the MAIN pass re-walked its chunk graph every frame even while nothing moved; Smart Terrain
 * Updates reused the last main walk on those frames. On 26.1.2 (Sodium 0.9.2 + Iris 1.11.4, read from the
 * jars 2026-10-20) that cost is gone at the source: ShadowRenderer.renderShadows opens
 * iris$beginShadowRenderListScope (offset 552) BEFORE SodiumWorldRenderer.scheduleTerrainUpdate
 * (markGraphDirty, offset 559), and iris$endShadowRenderListScope restores the main pass's render
 * lists, tree, task lists and its needsRenderListUpdate / needsGraphUpdate / cameraChanged flags. The
 * main RenderSectionManager.finalizeRenderLists then reuses its lists unless something really changed
 * (renderOutOfGraph only on updateImmediately or a sync frame with a dirty flag;
 * readRenderListFromTree only on needsRenderListUpdate). So there is nothing left for the feature to
 * save, and shipping a toggle that does nothing would be faking it.
 *
 * <p>These counters (SodiumRenderSectionManagerHooksMixin) count main-pass list rebuilds and async cull
 * jobs outside the shadow pass, plus the shadow walks themselves. dev-shots asserts "standing still with
 * a shader on = no main rebuilds"; F3 shows the reason line while a shader pack is in use.
 * Render thread only; plain counters, no allocation.
 *
 * <p>PER-VERSION (26.1): the reasoning above holds for 26.1.2 (Sodium 0.9.2 + Iris 1.11.4+mc26.1.2, the same
 * scope calls; javap 2026-10-20). 26.1 / 26.1.1 run Sodium 0.8.9 + Iris 1.10.9, which has no
 * iris$beginShadowRenderListScope, so there Smart Terrain Updates is a real toggle and owns the F3 line;
 * this class only times their shadow walks (shadowWalkBegin) for dev-shots.
 */
public final class ShadowTerrainStats {
	private ShadowTerrainStats() {
	}

	private static final MethodHandle IS_SHADOW_PASS = IrisState.SHADOW_PASS;
	private static final MethodHandle PACK_IN_USE = IrisState.PACK_IN_USE;

	/** Running totals (dev-shots reads deltas): main-pass renderOutOfGraph / readRenderListFromTree /
	 * scheduleAsyncWork calls, shadow-pass walks and their summed nanoseconds. */
	public static long mainOutOfGraph, mainReadTree, mainAsync, shadowWalks, shadowWalkNanos;

	private static long walkStart;
	private static boolean broken;

	// Rolling one-second window for the F3 line.
	private static long secStart, lastSecAt;
	private static int secRebuilds, lastRebuilds;

	private static boolean inShadowPass() {
		if (broken || IS_SHADOW_PASS == null) {
			return false;
		}
		try {
			return (boolean) IS_SHADOW_PASS.invokeExact();
		} catch (Throwable t) {
			broken = true;
			return false;
		}
	}

	/** renderOutOfGraph HEAD. */
	public static void outOfGraphBegin() {
		if (inShadowPass()) {
			walkStart = System.nanoTime();
		} else {
			mainOutOfGraph++;
			rebuilt();
		}
	}

	/** PER-VERSION (26.1): Sodium 0.8.9's createTerrainRenderList HEAD (SodiumWalkEndMixin) -- times shadow walks
	 * only, so Off-Map / Half-Rate dev counts read the same on both Sodium generations. Main walks on 0.8 are
	 * Smart Terrain Updates' to count (a reused walk is answered at that same HEAD). */
	public static void shadowWalkBegin() {
		if (inShadowPass()) {
			walkStart = System.nanoTime();
		}
	}

	/** renderOutOfGraph RETURN (and Sodium 0.8.9's createTerrainRenderList RETURN). */
	public static void outOfGraphEnd() {
		if (walkStart != 0) {
			shadowWalkNanos += System.nanoTime() - walkStart;
			shadowWalks++;
			walkStart = 0;
		}
	}

	/** readRenderListFromTree HEAD. */
	public static void readTree() {
		if (!inShadowPass()) {
			mainReadTree++;
			rebuilt();
		}
	}

	/** scheduleAsyncWork HEAD (a background cull job for the main pass). */
	public static void asyncWork() {
		if (!inShadowPass()) {
			mainAsync++;
		}
	}

	private static void rebuilt() {
		long now = System.nanoTime();
		if (secStart == 0) {
			secStart = now;
		}
		secRebuilds++;
		roll(now);
	}

	private static void roll(long now) {
		if (now - secStart >= 1_000_000_000L) {
			lastRebuilds = secRebuilds;
			lastSecAt = now;
			secRebuilds = 0;
			secStart = now;
		}
	}

	/** One F3 line while a shader pack is in use: why there is no Smart Terrain toggle on this version. */
	public static String debugLine() {
		if (broken || PACK_IN_USE == null) {
			return null;
		}
		try {
			if (!(boolean) PACK_IN_USE.invokeExact()) {
				return null;
			}
		} catch (Throwable t) {
			broken = true;
			return null;
		}
		long now = System.nanoTime();
		roll(now);
		int perSec = lastSecAt != 0 && now - lastSecAt < 3_000_000_000L ? lastRebuilds : 0;
		return "Origin Smart Terrain: built into Sodium here (main chunk list rebuilt " + perSec + "x in the last second)";
	}
}
