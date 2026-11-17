// Origin compatibility stub for VulkaIris (LGPL-3.0-only). Written by Origin from the call sites in
// Iris 1.8.14-beta.1 and VulkaIris; no Sodium code. Same binary name as the Sodium type Iris links
// against, so Iris loads without Sodium under VulkanMod. See sodium-compat/README.md.
package net.caffeinemc.mods.sodium.api.vertex.serializer;

import com.mojang.blaze3d.vertex.VertexFormat;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** The one registry behind VertexSerializerRegistry.instance(). Identity keys: VertexFormat instances are singletons. */
final class SimpleVertexSerializerRegistry implements VertexSerializerRegistry {
	static final SimpleVertexSerializerRegistry INSTANCE = new SimpleVertexSerializerRegistry();

	private record Key(VertexFormat from, VertexFormat to) { }

	private final Map<Key, VertexSerializer> serializers = new ConcurrentHashMap<>();

	private SimpleVertexSerializerRegistry() { }

	@Override
	public void registerSerializer(VertexFormat from, VertexFormat to, VertexSerializer serializer) {
		serializers.put(new Key(from, to), serializer);
	}

	@Override
	public VertexSerializer get(VertexFormat from, VertexFormat to) {
		return serializers.get(new Key(from, to));
	}
}
