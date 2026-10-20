package com.origin.client.client.render;

/**
 * Which Sodium / Iris builds are installed, read once from Fabric Loader's metadata. No Sodium, Iris or
 * Minecraft types here, so the settings list can read it to hide options the installed builds can't run.
 *
 * <p>This module serves 1.21.10 only (PerformanceModCatalog.Data.cs "1.21.10": sodium-fabric-0.7.3+mc1.21.10
 * and iris-fabric-1.9.7+mc1.21.10).
 */
public final class SodiumIrisBuilds {
	private SodiumIrisBuilds() { }

	/**
	 * Smart Terrain Updates was written against Sodium 0.7.3+mc1.21.10 and Iris 1.9.7+mc1.21.10 (their
	 * walk internals: RenderSectionManager.createTerrainRenderList / OcclusionCuller.findVisible and
	 * Iris's separate shadow render lists, javap 2026-10-20) and only runs there.
	 */
	/**
	 * Sodium is loaded. Sodium is a separate install on this version and the player can disable it
	 * (PerfModInstaller honours .jar.disabled), so every vanilla-side hook checks this before it
	 * touches a class that links Sodium types (SmartTerrainUpdates, OffMapShadows); without the check
	 * the first world frame threw NoClassDefFoundError out of LevelRenderer (review 2026-10-20).
	 */
	public static final boolean SODIUM = loaded("sodium");

	public static final boolean SMART_TERRAIN = is("sodium", "0.7.3+mc1.21.10") && is("iris", "1.9.7+mc1.21.10");

	private static boolean loaded(String mod) {
		try {
			return net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded(mod);
		} catch (Throwable t) {
			return false;
		}
	}

	/** True when mod {@code mod} is loaded at version {@code prefix} (exactly, or with a further "+build" suffix). */
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
