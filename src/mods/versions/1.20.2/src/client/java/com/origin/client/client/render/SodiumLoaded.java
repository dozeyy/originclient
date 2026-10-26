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

	// A Sodium build Origin's hooks were proven on (compat/CompatGates), not just any Sodium.
	public static final boolean YES = com.origin.client.client.compat.CompatGates.on("sodium");
}
