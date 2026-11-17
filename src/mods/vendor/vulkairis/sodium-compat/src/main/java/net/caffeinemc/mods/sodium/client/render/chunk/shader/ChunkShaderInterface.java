// Origin compatibility stub for VulkaIris (LGPL-3.0-only). Written by Origin from the call sites in
// Iris 1.8.14-beta.1 and VulkaIris; no Sodium code. Same binary name as the Sodium type Iris links
// against, so Iris loads without Sodium under VulkanMod. See sodium-compat/README.md.
package net.caffeinemc.mods.sodium.client.render.chunk.shader;

import org.joml.Matrix4fc;

/** The chunk shader contract Iris's SodiumShader implements; only Sodium's GL renderer calls it. */
public interface ChunkShaderInterface {
	void setupState();

	void resetState();

	void setProjectionMatrix(Matrix4fc matrix);

	void setModelViewMatrix(Matrix4fc matrix);

	void setRegionOffset(float x, float y, float z);
}
