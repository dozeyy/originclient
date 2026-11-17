// Origin compatibility stub for VulkaIris (LGPL-3.0-only). Written by Origin from the call sites in
// Iris 1.8.14-beta.1 and VulkaIris; no Sodium code. Same binary name as the Sodium type Iris links
// against, so Iris loads without Sodium under VulkanMod. See sodium-compat/README.md.
package net.caffeinemc.mods.sodium.client.render.vertex;

import net.caffeinemc.mods.sodium.client.gl.attribute.GlVertexAttributeFormat;

/** One named vertex attribute: component type, count, and whether it is normalized or read as integers. */
public record VertexFormatAttribute(String name, GlVertexAttributeFormat format, int count, boolean normalized,
		boolean intType) {
	/** Size in bytes. */
	public int size() {
		return format.size() * count;
	}
}
