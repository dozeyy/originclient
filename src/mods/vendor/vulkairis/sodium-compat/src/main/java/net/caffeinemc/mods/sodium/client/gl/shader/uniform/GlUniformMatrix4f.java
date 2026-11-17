// Origin compatibility stub for VulkaIris (LGPL-3.0-only). Written by Origin from the call sites in
// Iris 1.8.14-beta.1 and VulkaIris; no Sodium code. Same binary name as the Sodium type Iris links
// against, so Iris loads without Sodium under VulkanMod. See sodium-compat/README.md.
package net.caffeinemc.mods.sodium.client.gl.shader.uniform;

import net.caffeinemc.mods.sodium.client.gl.NoGlUnderVulkan;
import org.joml.Matrix4fc;

/** mat4 uniform. Only Iris's SodiumShader creates one, and VulkaIris never builds a SodiumShader. */
public class GlUniformMatrix4f extends GlUniform<Matrix4fc> {
	public GlUniformMatrix4f(int index) {
		super(index);
	}

	@Override
	public void set(Matrix4fc value) {
		throw new NoGlUnderVulkan("GlUniformMatrix4f");
	}
}
