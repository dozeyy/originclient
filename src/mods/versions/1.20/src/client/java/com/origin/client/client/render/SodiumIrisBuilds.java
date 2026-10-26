package com.origin.client.client.render;

/**
 * Which Sodium / Iris builds are installed, read once from Fabric Loader's metadata. No Sodium, Iris or
 * Minecraft types here: CompatMixinPlugin (originclient.compat-gates.txt) reads it while mixins are still being selected, and the
 * settings list reads it to hide options the installed builds can't run.
 *
 * <p>This module serves MC 1.20 and 1.20.1, and the launcher installs two different stacks
 * (PerformanceModCatalog.Data.cs): MC 1.20 gets Sodium 0.4.10+build.27 + Iris 1.6.4 (net.coderbot
 * packages, Sodium's pre-0.5 chunk graph), MC 1.20.1 gets Sodium 0.5.13 + Iris 1.7.6 (the same members
 * the 1.20.4 module hooks on Sodium 0.5.8 + Iris 1.7.2; javap 2026-10-20).
 */
// Every flag below reads compat/CompatGates (this module's originclient.compat-gates.txt): the
// Sodium/Iris builds each hook was proven on. A player's own Sodium/Iris outside them = flag false.
// (CompatGates gate)
public final class SodiumIrisBuilds {
	private SodiumIrisBuilds() { }

	/**
	 * Any Sodium at all. The player can disable it (PerfModInstaller honours .jar.disabled), and Iris
	 * 1.6.4 (MC 1.20) still loads without it, so vanilla-target hooks check this before touching a
	 * class that uses Sodium types.
	 */
	public static final boolean SODIUM = com.origin.client.client.compat.CompatGates.on("sodium");
	/** Sodium 0.5.13 (MC 1.20.1): Viewport, OcclusionCuller and the 0.5 RenderSectionManager. */
	public static final boolean SODIUM_05 = com.origin.client.client.compat.CompatGates.on("sodium05");
	/** Sodium 0.4.10 (MC 1.20): the old chunk graph (iterateChunks / bfsEnqueue), no Viewport. */
	public static final boolean SODIUM_04 = com.origin.client.client.compat.CompatGates.on("sodium04");
	/** Iris 1.7.6 (MC 1.20.1): net.irisshaders.iris.* (IrisRenderingPipeline, shadows.ShadowRenderer). */
	public static final boolean IRIS_17 = com.origin.client.client.compat.CompatGates.on("iris17");
	/** Iris 1.6.4 (MC 1.20): net.coderbot.iris.* (NewWorldRenderingPipeline, pipeline.ShadowRenderer). */
	public static final boolean IRIS_16 = com.origin.client.client.compat.CompatGates.on("iris16");

	/**
	 * Smart Terrain Updates needs Sodium 0.5's graph walk (update / createTerrainRenderList / the
	 * OcclusionCuller stamp) and Iris 1.7's shadow-pass render-list swap; Sodium 0.4.10 has none of them
	 * (javap 2026-10-20), so it runs on MC 1.20.1 only. SmartTerrainUpdates.VERSIONS_OK is the same test.
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
