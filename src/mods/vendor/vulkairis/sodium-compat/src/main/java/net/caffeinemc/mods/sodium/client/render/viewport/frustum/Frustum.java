// Origin compatibility stub for VulkaIris (LGPL-3.0-only). Written by Origin from the call sites in
// Iris 1.8.14-beta.1 and VulkaIris; no Sodium code. Same binary name as the Sodium type Iris links
// against, so Iris loads without Sodium under VulkanMod. See sodium-compat/README.md.
package net.caffeinemc.mods.sodium.client.render.viewport.frustum;

/**
 * Box test interface Iris's shadow frustums implement. Iris's ShadowRenderer builds those frustums, so this
 * type must load even though only Sodium's chunk culler calls the methods.
 */
public interface Frustum {
	boolean testAab(float minX, float minY, float minZ, float maxX, float maxY, float maxZ);

	/** 0 = outside, 1 = intersects, 2 = inside (only the sign matters to callers). */
	default int intersectAab(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
		return testAab(minX, minY, minZ, maxX, maxY, maxZ) ? 1 : 0;
	}
}
