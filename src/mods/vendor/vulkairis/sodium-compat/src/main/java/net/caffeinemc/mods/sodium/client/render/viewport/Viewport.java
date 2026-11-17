// Origin compatibility stub for VulkaIris (LGPL-3.0-only). Written by Origin from the call sites in
// Iris 1.8.14-beta.1 and VulkaIris; no Sodium code. Same binary name as the Sodium type Iris links
// against, so Iris loads without Sodium under VulkanMod. See sodium-compat/README.md.
package net.caffeinemc.mods.sodium.client.render.viewport;

import net.caffeinemc.mods.sodium.client.render.viewport.frustum.Frustum;
import org.joml.Vector3d;

/** A frustum plus camera position, as Iris's shadow frustums construct it. Data only. */
public final class Viewport {
	private final Frustum frustum;
	private final Vector3d position;

	public Viewport(Frustum frustum, Vector3d position) {
		this.frustum = frustum;
		this.position = new Vector3d(position);
	}

	public Frustum getFrustum() {
		return frustum;
	}

	public Vector3d getPosition() {
		return new Vector3d(position);
	}

	public boolean isBoxVisible(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
		return frustum.testAab(minX, minY, minZ, maxX, maxY, maxZ);
	}
}
