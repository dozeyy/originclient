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

	public static void resetHud(String elementId) {
		ModsConfig.ensureLoaded();
		ModsConfig.HUD.remove(elementId);
		ModsConfig.save();
	}
}
