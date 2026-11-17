// Origin compatibility stub for VulkaIris (LGPL-3.0-only). Written by Origin from the call sites in
// Iris 1.8.14-beta.1 and VulkaIris; no Sodium code. Same binary name as the Sodium type Iris links
// against, so Iris loads without Sodium under VulkanMod. See sodium-compat/README.md.
package net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.impl;

import net.caffeinemc.mods.sodium.client.gl.attribute.GlVertexAttributeFormat;
import net.caffeinemc.mods.sodium.client.render.vertex.VertexFormatAttribute;

/**
 * The four attributes of a compact chunk vertex, which Iris's FormatAnalyzer copies into its extended
 * layouts. Under VulkanMod nothing draws with these layouts (VulkanMod meshes chunks itself); they only
 * have to exist and describe a self-consistent 20-byte vertex.
 */
public final class DefaultChunkMeshAttributes {
	public static final VertexFormatAttribute POSITION =
			new VertexFormatAttribute("POSITION", GlVertexAttributeFormat.UNSIGNED_INT, 2, false, true);
	public static final VertexFormatAttribute COLOR =
			new VertexFormatAttribute("COLOR", GlVertexAttributeFormat.UNSIGNED_BYTE, 4, true, false);
	public static final VertexFormatAttribute TEXTURE =
			new VertexFormatAttribute("TEXTURE", GlVertexAttributeFormat.UNSIGNED_INT, 1, false, true);
	public static final VertexFormatAttribute LIGHT_MATERIAL_INDEX =
			new VertexFormatAttribute("LIGHT_MATERIAL_INDEX", GlVertexAttributeFormat.UNSIGNED_BYTE, 4, false, true);

	private DefaultChunkMeshAttributes() { }
}
