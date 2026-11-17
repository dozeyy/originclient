// Origin compatibility stub for VulkaIris (LGPL-3.0-only). Written by Origin from the call sites in
// Iris 1.8.14-beta.1 and VulkaIris; no Sodium code. Same binary name as the Sodium type Iris links
// against, so Iris loads without Sodium under VulkanMod. See sodium-compat/README.md.
package net.caffeinemc.mods.sodium.api.util;

/**
 * Normals packed as three signed bytes (x in bits 0-7, y in 8-15, z in 16-23), the layout vanilla's
 * entity vertex formats use. Iris unpacks them when it converts entity vertices.
 */
public final class NormI8 {
	private static final float SCALE = 127.0f;

	private NormI8() { }

	public static float unpackX(int packed) {
		return (byte) (packed & 0xFF) / SCALE;
	}

	public static float unpackY(int packed) {
		return (byte) ((packed >>> 8) & 0xFF) / SCALE;
	}

	public static float unpackZ(int packed) {
		return (byte) ((packed >>> 16) & 0xFF) / SCALE;
	}
}
