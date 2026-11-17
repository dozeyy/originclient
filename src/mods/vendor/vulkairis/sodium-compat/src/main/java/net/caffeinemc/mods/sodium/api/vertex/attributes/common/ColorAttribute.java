// Origin compatibility stub for VulkaIris (LGPL-3.0-only). Written by Origin from the call sites in
// Iris 1.8.14-beta.1 and VulkaIris; no Sodium code. Same binary name as the Sodium type Iris links
// against, so Iris loads without Sodium under VulkanMod. See sodium-compat/README.md.
package net.caffeinemc.mods.sodium.api.vertex.attributes.common;

import org.lwjgl.system.MemoryUtil;

/** Writes a packed colour int at ptr. */
public final class ColorAttribute {
	private ColorAttribute() { }

	public static void set(long ptr, int color) {
		MemoryUtil.memPutInt(ptr, color);
	}
}
