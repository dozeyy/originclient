package com.origin.client.client.render;

/**
 * Which Sodium / Iris builds are installed, read once from Fabric Loader's metadata. No Sodium, Iris or
 * Minecraft types here, so the settings list can read it to hide options the installed builds can't run.
 *
 * <p>This module serves 1.21.11 only; the launcher installs Sodium 0.8.14 + Iris 1.10.8 there
 * (PerformanceModCatalog.Data.cs). The shader speed-ups that reach into Sodium/Iris internals were
 * written against exactly those builds.
 */
public final class SodiumIrisBuilds {
	private SodiumIrisBuilds() { }

	/**
	 * Sodium is loaded at all. The launcher installs it standalone and honours a player's ".jar.disabled", so
	 * every vanilla-target mixin that calls into a Sodium-typed class (OffMapShadows, SmartTerrainUpdates)
	 * checks this first: touching those classes without Sodium throws NoClassDefFoundError on the first frame.
	 */
	public static final boolean SODIUM = loaded("sodium");

	private static boolean loaded(String mod) {
		try {
			return net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded(mod);
		} catch (Throwable t) {
			return false;
		}
	}

	/**
	 * Smart Terrain Updates was written against Sodium 0.8.12+mc1.21.11 and Iris 1.10.7+mc1.21.11 (javap
	 * 2026-10-20) and only runs there: it reads RenderSectionManager's private walk state and relies on
	 * Iris keeping separate main/shadow render lists, both of which change between Sodium/Iris releases.
	 * Pairs checked: 0.8.12 + 1.10.7, and 0.8.14 + 1.10.8 (2026-10-25: a javap diff of both jars shows the
	 * classes Origin's mixins and SmartTerrainUpdates touch -- RenderSectionManager, OcclusionCuller,
	 * Viewport, SodiumWorldRenderer, SortedRenderLists, Iris's Sodium-compat mixins -- unchanged in
	 * behaviour). Sodium and Iris move together, so mixed pairs stay off.
	 */
	public static final boolean SMART_TERRAIN =
			(is("sodium", "0.8.12") && is("iris", "1.10.7")) || (is("sodium", "0.8.14") && is("iris", "1.10.8"));

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
