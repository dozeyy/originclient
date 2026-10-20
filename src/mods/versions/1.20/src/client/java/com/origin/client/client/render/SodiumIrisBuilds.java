package com.origin.client.client.render;

/**
 * Which Sodium / Iris builds are installed, read once from Fabric Loader's metadata. No Sodium, Iris or
 * Minecraft types here: OriginMixinPlugin reads it while mixins are still being selected, and the
 * settings list reads it to hide options the installed builds can't run.
 *
 * <p>This module serves MC 1.20 and 1.20.1, and the launcher installs two different stacks
 * (PerformanceModCatalog.Data.cs): MC 1.20 gets Sodium 0.4.10+build.27 + Iris 1.6.4 (net.coderbot
 * packages, Sodium's pre-0.5 chunk graph), MC 1.20.1 gets Sodium 0.5.13 + Iris 1.7.6 (the same members
 * the 1.20.4 module hooks on Sodium 0.5.8 + Iris 1.7.2; javap 2026-10-20).
 */
public final class SodiumIrisBuilds {
	private SodiumIrisBuilds() { }

	/** Sodium 0.5.13 (MC 1.20.1): Viewport, OcclusionCuller and the 0.5 RenderSectionManager. */
	public static final boolean SODIUM_05 = is("sodium", "0.5.13");
	/** Sodium 0.4.10 (MC 1.20): the old chunk graph (iterateChunks / bfsEnqueue), no Viewport. */
	public static final boolean SODIUM_04 = is("sodium", "0.4.10");
	/** Iris 1.7.6 (MC 1.20.1): net.irisshaders.iris.* (IrisRenderingPipeline, shadows.ShadowRenderer). */
	public static final boolean IRIS_17 = is("iris", "1.7.6");
	/** Iris 1.6.4 (MC 1.20): net.coderbot.iris.* (NewWorldRenderingPipeline, pipeline.ShadowRenderer). */
	public static final boolean IRIS_16 = is("iris", "1.6.4");

	/**
	 * Smart Terrain Updates needs Sodium 0.5's graph walk (update / createTerrainRenderList / the
	 * OcclusionCuller stamp) and Iris 1.7's shadow-pass render-list swap; Sodium 0.4.10 has none of them
	 * (javap 2026-10-20), so it runs on MC 1.20.1 only. SmartTerrainUpdates.VERSIONS_OK is the same test.
	 */
	public static final boolean SMART_TERRAIN = SODIUM_05 && IRIS_17;

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
