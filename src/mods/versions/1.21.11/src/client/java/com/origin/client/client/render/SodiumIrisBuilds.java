package com.origin.client.client.render;

/**
 * Which Sodium / Iris builds are installed, read once from Fabric Loader's metadata. No Sodium, Iris or
 * Minecraft types here, so the settings list can read it to hide options the installed builds can't run.
 *
 * <p>This module serves 1.21.11 only; the launcher installs Sodium 0.8.12 + Iris 1.10.7 there
 * (PerformanceModCatalog.Data.cs). The shader speed-ups that reach into Sodium/Iris internals were
 * written against exactly those builds.
 */
public final class SodiumIrisBuilds {
	private SodiumIrisBuilds() { }

	/**
	 * Smart Terrain Updates was written against Sodium 0.8.12+mc1.21.11 and Iris 1.10.7+mc1.21.11 (javap
	 * 2026-10-20) and only runs there: it reads RenderSectionManager's private walk state and relies on
	 * Iris keeping separate main/shadow render lists, both of which change between Sodium/Iris releases.
	 */
	public static final boolean SMART_TERRAIN = is("sodium", "0.8.12") && is("iris", "1.10.7");

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
