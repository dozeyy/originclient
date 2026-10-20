package com.origin.client.client.render;

/**
 * Whether Sodium is loaded, read once from Fabric Loader. No Sodium, Iris or Minecraft types here, so
 * any hook can read it safely. Sodium is a separate install on this version and the player can disable
 * it (PerfModInstaller honours .jar.disabled); vanilla-target hooks (LevelRenderer.prepareCullFrustum,
 * EntityRenderDispatcher.shouldRender, the F3 screen) check this before touching a class that uses
 * Sodium types, so a missing Sodium costs the speed-ups, never the game.
 */
public final class SodiumLoaded {
	private SodiumLoaded() { }

	public static final boolean YES = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("sodium");
}
