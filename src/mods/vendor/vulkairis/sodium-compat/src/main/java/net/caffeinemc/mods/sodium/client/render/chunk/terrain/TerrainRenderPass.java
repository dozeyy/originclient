// Origin compatibility stub for VulkaIris (LGPL-3.0-only). Written by Origin from the call sites in
// Iris 1.8.14-beta.1 and VulkaIris; no Sodium code. Same binary name as the Sodium type Iris links
// against, so Iris loads without Sodium under VulkanMod. See sodium-compat/README.md.
package net.caffeinemc.mods.sodium.client.render.chunk.terrain;

/** A terrain pass: name plus the two behaviour flags VulkaIris's terrain door reads. */
public class TerrainRenderPass {
	private final String name;
	private final boolean translucent;
	private final boolean fragmentDiscard;

	public TerrainRenderPass(String name, boolean translucent, boolean fragmentDiscard) {
		this.name = name;
		this.translucent = translucent;
		this.fragmentDiscard = fragmentDiscard;
	}

	public boolean isTranslucent() {
		return translucent;
	}

	public boolean supportsFragmentDiscard() {
		return fragmentDiscard;
	}

	@Override
	public String toString() {
		return name;
	}
}
