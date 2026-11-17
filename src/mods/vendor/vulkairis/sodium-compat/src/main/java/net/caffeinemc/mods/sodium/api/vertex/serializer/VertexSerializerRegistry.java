// Origin compatibility stub for VulkaIris (LGPL-3.0-only). Written by Origin from the call sites in
// Iris 1.8.14-beta.1 and VulkaIris; no Sodium code. Same binary name as the Sodium type Iris links
// against, so Iris loads without Sodium under VulkanMod. See sodium-compat/README.md.
package net.caffeinemc.mods.sodium.api.vertex.serializer;

import com.mojang.blaze3d.vertex.VertexFormat;

/**
 * Iris.onEarlyInitialize registers its entity/terrain serializers here. Without Sodium nobody reads them
 * back (VulkanMod writes its own vertex data), so this only has to accept and remember them.
 */
public interface VertexSerializerRegistry {
	static VertexSerializerRegistry instance() {
		return SimpleVertexSerializerRegistry.INSTANCE;
	}

	void registerSerializer(VertexFormat from, VertexFormat to, VertexSerializer serializer);

	/** The serializer registered for this pair, or null. */
	VertexSerializer get(VertexFormat from, VertexFormat to);
}
