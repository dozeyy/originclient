package com.origin.client.client.mods;

/**
 * SETTINGS > Performance values the hot paths read, copied out of the config once
 * per frame (HudHooks, RenderTickEvent START) instead of through
 * {@link Mods#num} / {@link Mods#bool} on every call. The entity-distance check
 * runs for every entity of every world pass (twice with a shader pack's shadow
 * pass) and the text-shadow check for every string drawn; a config lookup there is
 * several map reads each. A changed setting applies on the next frame.
 * Render thread writes, render thread reads; plain static fields.
 */
public final class PerfSettings {
	private PerfSettings() {
	}

	/** Entity Distance, percent of the render distance (100 = off). */
	public static double entityDistancePct = 100;
	/** Tile Entity Distance, percent of the render distance (100 = off). */
	public static double tileEntityDistancePct = 100;
	/** Disable Text Shadow: every string the game draws loses its drop-shadow pass. */
	public static boolean noTextShadow = false;

	/** Once per frame, render thread. Fail-soft: a bad read keeps the last values. */
	public static void refresh() {
		try {
			entityDistancePct = Mods.num(Mods.PERFORMANCE_ID, "entityDistance");
			tileEntityDistancePct = Mods.num(Mods.PERFORMANCE_ID, "tileEntityDistance");
			noTextShadow = Mods.bool(Mods.PERFORMANCE_ID, "noTextShadow");
		} catch (Throwable ignored) {
			// keep the previous frame's values
		}
	}
}
