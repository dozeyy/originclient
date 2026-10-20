package com.origin.client.client.render;

/**
 * Which Sodium / Iris builds are installed, read once from Fabric Loader's metadata. No Sodium, Iris or
 * Minecraft types here, so the settings list and the mixin plugin (OriginMixinPlugin) can read it.
 *
 * <p>PER-VERSION (26.1): this one jar meets two Sodium generations (PerformanceModCatalog.Data.cs):
 * 26.1 and 26.1.1 get Sodium 0.8.9 + Iris 1.10.9 (the +mc26.1.1 builds), 26.1.2 gets Sodium 0.9.2 +
 * Iris 1.11.4. The shader speed-ups that reach into Sodium/Iris internals were written against exactly
 * those builds.
 */
public final class SodiumIrisBuilds {
	private SodiumIrisBuilds() { }

	/** Sodium 0.8.x: the synchronous graph walk (createTerrainRenderList, SectionCollector). */
	public static final boolean SODIUM_08 = startsWith("sodium", "0.8.");

	/** Sodium 0.9.x: async culling, the shadow walk is renderOutOfGraph. */
	public static final boolean SODIUM_09 = startsWith("sodium", "0.9.");

	/**
	 * Smart Terrain Updates was written against Sodium 0.8.9+mc26.1.1 and Iris 1.10.9+mc26.1.1 (javap
	 * 2026-10-20) and only runs there: it reads RenderSectionManager's private walk state and relies on
	 * Iris keeping separate main/shadow render lists, both of which change between Sodium/Iris releases.
	 * Sodium 0.9 has no such walk to skip (render/ShadowTerrainStats).
	 */
	public static final boolean SMART_TERRAIN = is("sodium", "0.8.9") && is("iris", "1.10.9");

	/** True when mod {@code mod} is loaded at version {@code prefix} (with or without a "+build" suffix). */
	public static boolean is(String mod, String prefix) {
		String v = version(mod);
		return v != null && (v.equals(prefix) || v.startsWith(prefix + "+"));
	}

	private static boolean startsWith(String mod, String prefix) {
		String v = version(mod);
		return v != null && v.startsWith(prefix);
	}

	private static String version(String mod) {
		try {
			return net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer(mod)
					.map(c -> c.getMetadata().getVersion().getFriendlyString())
					.orElse(null);
		} catch (Throwable t) {
			return null;
		}
	}
}
