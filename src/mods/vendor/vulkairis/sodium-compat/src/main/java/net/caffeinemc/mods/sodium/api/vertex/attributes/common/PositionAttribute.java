// Origin compatibility stub for VulkaIris (LGPL-3.0-only). Written by Origin from the call sites in
// Iris 1.8.14-beta.1 and VulkaIris; no Sodium code. Same binary name as the Sodium type Iris links
// against, so Iris loads without Sodium under VulkanMod. See sodium-compat/README.md.
package net.caffeinemc.mods.sodium.api.vertex.attributes.common;

import org.lwjgl.system.MemoryUtil;

/** Writes a float3 position at ptr. */
public final class PositionAttribute {
	private PositionAttribute() { }

	public static void put(long ptr, float x, float y, float z) {
		MemoryUtil.memPutFloat(ptr, x);
		MemoryUtil.memPutFloat(ptr + 4, y);
		MemoryUtil.memPutFloat(ptr + 8, z);
	}
}
