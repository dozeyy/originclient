// Origin compatibility stub for VulkaIris (LGPL-3.0-only). Written by Origin from the call sites in
// Iris 1.8.14-beta.1 and VulkaIris; no Sodium code. Same binary name as the Sodium type Iris links
// against, so Iris loads without Sodium under VulkanMod. See sodium-compat/README.md.
package net.caffeinemc.mods.sodium.api.vertex.serializer;

/** Copies vertexCount vertices from one vertex layout at src to another at dst (Iris implements it). */
public interface VertexSerializer {
	void serialize(long src, long dst, int vertexCount);
}
