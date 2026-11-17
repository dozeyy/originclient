// Origin compatibility stub for VulkaIris (LGPL-3.0-only). Written by Origin from the call sites in
// Iris 1.8.14-beta.1 and VulkaIris; no Sodium code. Same binary name as the Sodium type Iris links
// against, so Iris loads without Sodium under VulkanMod. See sodium-compat/README.md.
package net.caffeinemc.mods.sodium.client.gl.shader.uniform;

import net.caffeinemc.mods.sodium.client.gl.NoGlUnderVulkan;

/** vec3 uniform. Only Iris's SodiumShader creates one, and VulkaIris never builds a SodiumShader. */
public class GlUniformFloat3v extends GlUniform<float[]> {
	public GlUniformFloat3v(int index) {
		super(index);
	}

	@Override
	public void set(float[] value) {
		set(value[0], value[1], value[2]);
	}

	public void set(float x, float y, float z) {
		throw new NoGlUnderVulkan("GlUniformFloat3v");
	}
}
