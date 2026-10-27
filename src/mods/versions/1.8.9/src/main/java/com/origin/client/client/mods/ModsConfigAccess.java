package com.origin.client.client.mods;

// Narrow bridge so the hud package can reach ModsConfig's package-private
// HUD store without widening ModsConfig itself.
public final class ModsConfigAccess {
	private ModsConfigAccess() {
	}

	public static double[] hud(String elementId) {
		ModsConfig.ensureLoaded();
		return ModsConfig.HUD.get(elementId);
	}

	public static void putHud(String elementId, double[] v) {
		ModsConfig.ensureLoaded();
		ModsConfig.HUD.put(elementId, v);
		ModsConfig.save();
	}

	/**
	 * A HUD position/scale that is still changing (the HUD editor's resize drag): every reader sees it at
	 * once, but nothing is written to disk -- the gesture ends with {@link #putHud}, which saves. Writing the
	 * whole settings file on every mouse move stalled the render thread (bench scene hudedit).
	 */
	public static void putHudLive(String elementId, double[] v) {
		ModsConfig.ensureLoaded();
		ModsConfig.HUD.put(elementId, v);
		ModsConfig.touch();
	}

	public static void resetHud(String elementId) {
		ModsConfig.ensureLoaded();
		ModsConfig.HUD.remove(elementId);
		ModsConfig.save();
	}
}
