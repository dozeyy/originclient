// Origin compatibility stub for VulkaIris (LGPL-3.0-only). Written by Origin from the call sites in
// Iris 1.8.14-beta.1 and VulkaIris; no Sodium code. Same binary name as the Sodium type Iris links
// against, so Iris loads without Sodium under VulkanMod. See sodium-compat/README.md.
package net.caffeinemc.mods.sodium.client.render.chunk.terrain;

/** The three standard terrain passes Iris's SodiumPrograms keys its programs by. */
public final class DefaultTerrainRenderPasses {
	public static final TerrainRenderPass SOLID = new TerrainRenderPass("solid", false, false);
	public static final TerrainRenderPass CUTOUT = new TerrainRenderPass("cutout", false, true);
	public static final TerrainRenderPass TRANSLUCENT = new TerrainRenderPass("translucent", true, false);

	private DefaultTerrainRenderPasses() { }
}
