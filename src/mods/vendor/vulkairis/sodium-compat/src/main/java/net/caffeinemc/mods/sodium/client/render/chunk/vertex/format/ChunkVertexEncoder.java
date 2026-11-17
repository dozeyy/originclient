// Origin compatibility stub for VulkaIris (LGPL-3.0-only). Written by Origin from the call sites in
// Iris 1.8.14-beta.1 and VulkaIris; no Sodium code. Same binary name as the Sodium type Iris links
// against, so Iris loads without Sodium under VulkanMod. See sodium-compat/README.md.
package net.caffeinemc.mods.sodium.client.render.chunk.vertex.format;

/**
 * Marker only. Iris's XHFPTerrainVertex implements it with a write method whose parameter type is Sodium's
 * per-vertex scratch class, which Iris also targets with a mixin (MixinChunkVertex). That class is left out
 * on purpose so the mixin finds no target; nobody calls write without Sodium's chunk mesher.
 */
public interface ChunkVertexEncoder {
}
