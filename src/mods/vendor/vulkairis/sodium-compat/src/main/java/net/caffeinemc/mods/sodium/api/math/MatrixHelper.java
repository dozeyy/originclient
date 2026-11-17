// Origin compatibility stub for VulkaIris (LGPL-3.0-only). Written by Origin from the call sites in
// Iris 1.8.14-beta.1 and VulkaIris; no Sodium code. Same binary name as the Sodium type Iris links
// against, so Iris loads without Sodium under VulkanMod. See sodium-compat/README.md.
package net.caffeinemc.mods.sodium.api.math;

import org.joml.Matrix4f;

/** Transforms one coordinate of a point by a matrix (column-major JOML). Iris's cloud vertices use it. */
public final class MatrixHelper {
	private MatrixHelper() { }

	public static float transformPositionX(Matrix4f m, float x, float y, float z) {
		return m.m00() * x + m.m10() * y + m.m20() * z + m.m30();
	}

	public static float transformPositionY(Matrix4f m, float x, float y, float z) {
		return m.m01() * x + m.m11() * y + m.m21() * z + m.m31();
	}

	public static float transformPositionZ(Matrix4f m, float x, float y, float z) {
		return m.m02() * x + m.m12() * y + m.m22() * z + m.m32();
	}
}
