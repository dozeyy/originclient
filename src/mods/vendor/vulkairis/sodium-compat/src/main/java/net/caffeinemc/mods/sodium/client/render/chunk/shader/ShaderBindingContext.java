// Origin compatibility stub for VulkaIris (LGPL-3.0-only). Written by Origin from the call sites in
// Iris 1.8.14-beta.1 and VulkaIris; no Sodium code. Same binary name as the Sodium type Iris links
// against, so Iris loads without Sodium under VulkanMod. See sodium-compat/README.md.
package net.caffeinemc.mods.sodium.client.render.chunk.shader;

import net.caffeinemc.mods.sodium.client.gl.shader.uniform.GlUniform;

import java.util.function.IntFunction;

/** Uniform lookup handed to a chunk shader while its GL program links (never happens under VulkanMod). */
public interface ShaderBindingContext {
	<U extends GlUniform<?>> U bindUniformOptional(String name, IntFunction<U> factory);
}
