// Origin compatibility stub for VulkaIris (LGPL-3.0-only). Written by Origin from the call sites in
// Iris 1.8.14-beta.1 and VulkaIris; no Sodium code. Same binary name as the Sodium type Iris links
// against, so Iris loads without Sodium under VulkanMod. See sodium-compat/README.md.
package net.caffeinemc.mods.sodium.client.render.chunk.vertex.format;

import net.caffeinemc.mods.sodium.client.gl.attribute.GlVertexFormat;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.impl.DefaultChunkMeshAttributes;

/**
 * Iris's WorldRenderingSettings and VanillaRenderingPipeline select COMPACT as the default chunk layout.
 * The value is only compared and passed around under VulkanMod, never drawn.
 */
public final class ChunkMeshFormats {
	private static final GlVertexFormat COMPACT_FORMAT = GlVertexFormat.builder(20)
			.addElement(DefaultChunkMeshAttributes.POSITION, 0, 0)
			.addElement(DefaultChunkMeshAttributes.COLOR, 1, 8)
			.addElement(DefaultChunkMeshAttributes.TEXTURE, 2, 12)
			.addElement(DefaultChunkMeshAttributes.LIGHT_MATERIAL_INDEX, 3, 16)
			.build();

	private static final ChunkVertexEncoder NO_ENCODER = new ChunkVertexEncoder() { };

	public static final ChunkVertexType COMPACT = new ChunkVertexType() {
		@Override
		public GlVertexFormat getVertexFormat() {
			return COMPACT_FORMAT;
		}

		@Override
		public ChunkVertexEncoder getEncoder() {
			return NO_ENCODER;
		}
	};

	private ChunkMeshFormats() { }
}
