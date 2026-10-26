package com.origin.client.client.render;

/**
 * Which Sodium / Iris builds are installed, read once from Fabric Loader's metadata. No Sodium, Iris or
 * Minecraft types here: CompatMixinPlugin (originclient.compat-gates.txt) reads it while mixins are still being selected, and the
 * settings list reads it to hide options the installed builds can't run.
 *
 * <p>This module serves 1.21.2, 1.21.3 and 1.21.4, and the launcher installs a different stack on each
 * (Sodium 0.6.1 / 0.6.8 / 0.6.13, Iris 1.8.0 / 1.8.1 / 1.8.8; PerformanceModCatalog.Data.cs).
 */
// Every flag below reads compat/CompatGates (this module's originclient.compat-gates.txt): the
// Sodium/Iris builds each hook was proven on. A player's own Sodium/Iris outside them = flag false.
// (CompatGates gate)
public final class SodiumIrisBuilds {
	private SodiumIrisBuilds() { }

	/**
	 * Sodium is loaded at all. On this module it is a separate install the player can switch off (the
	 * launcher honours a renamed .jar.disabled), so a vanilla-target mixin must test this before calling
	 * into any class that touches Sodium types (OffMapShadows, SmartTerrainUpdates): loading such a class
	 * without Sodium throws NoClassDefFoundError on the first world frame.
	 */
	public static final boolean SODIUM = com.origin.client.client.compat.CompatGates.on("sodium");

	/**
	 * Smart Terrain Updates was written against Sodium 0.6.13+mc1.21.4 and Iris 1.8.8+mc1.21.4 and only runs
	 * there. Sodium 0.6.1/0.6.8 also differ in the hooked signatures (javap 2026-10-20: setupTerrain
	 * (Camera, Viewport, boolean, boolean) and update(Camera, Viewport, boolean), with no FogParameters argument).
	 */
	public static final boolean SMART_TERRAIN = com.origin.client.client.compat.CompatGates.on("smartTerrainUpdates");

	private static boolean loaded(String mod) {
		try {
			return net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded(mod);
		} catch (Throwable t) {
			return false;
		}
	}

	/** True when mod {@code mod} is loaded at version {@code prefix} (with or without a "+build" suffix). */
	public static boolean is(String mod, String prefix) {
		try {
			return net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer(mod)
					.map(c -> {
						String v = c.getMetadata().getVersion().getFriendlyString();
						return v.equals(prefix) || v.startsWith(prefix + "+");
					})
					.orElse(false);
		} catch (Throwable t) {
			return false;
		}
	}
}
