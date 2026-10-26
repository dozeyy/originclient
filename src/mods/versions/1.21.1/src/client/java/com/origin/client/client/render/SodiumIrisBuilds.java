package com.origin.client.client.render;

/**
 * Which Sodium / Iris builds are installed, read once from Fabric Loader's metadata. No Sodium, Iris or
 * Minecraft types here, so the settings list can read it to hide options the installed builds can't run.
 *
 * <p>This module serves 1.21.1 only and bundles Sodium 0.8.13 + Iris 1.8.14-beta.1 jar-in-jar
 * (gradle.properties). The shader speed-ups that reach into Sodium/Iris internals were written against
 * exactly those builds; a player who swaps in another Sodium/Iris gets the feature switched off.
 */
// Every flag below reads compat/CompatGates (this module's originclient.compat-gates.txt): the
// Sodium/Iris builds each hook was proven on. A player's own Sodium/Iris outside them = flag false.
// (CompatGates gate)
public final class SodiumIrisBuilds {
	private SodiumIrisBuilds() { }

	/**
	 * Sodium is loaded at all. The launcher installs it standalone and honours a player's ".jar.disabled", so
	 * every vanilla-target mixin that calls into a Sodium-typed class (OffMapShadows, SmartTerrainUpdates)
	 * checks this first: touching those classes without Sodium throws NoClassDefFoundError on the first frame.
	 */
	public static final boolean SODIUM = com.origin.client.client.compat.CompatGates.on("sodium");

	private static boolean loaded(String mod) {
		try {
			return net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded(mod);
		} catch (Throwable t) {
			return false;
		}
	}

	/**
	 * Smart Terrain Updates reads RenderSectionManager's private walk state and relies on Iris keeping separate
	 * main/shadow render lists, both of which change between Sodium/Iris releases. On 1.21.1 it was re-derived
	 * against Sodium 0.8.13+mc1.21.1 and Iris 1.8.14-beta.1+mc1.21.1 (javap 2026-10-25: RenderSectionManager,
	 * OcclusionCuller, Viewport, SodiumWorldRenderer, SortedRenderLists, Iris's MixinRenderSectionManagerShadow).
	 * Sodium and Iris move together, so any other pair stays off.
	 */
	public static final boolean SMART_TERRAIN = com.origin.client.client.compat.CompatGates.on("smartTerrainUpdates");

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
