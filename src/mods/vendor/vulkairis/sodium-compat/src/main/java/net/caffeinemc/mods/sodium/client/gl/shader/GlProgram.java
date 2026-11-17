// Origin compatibility stub for VulkaIris (LGPL-3.0-only). Written by Origin from the call sites in
// Iris 1.8.14-beta.1 and VulkaIris; no Sodium code. Same binary name as the Sodium type Iris links
// against, so Iris loads without Sodium under VulkanMod. See sodium-compat/README.md.
package net.caffeinemc.mods.sodium.client.gl.shader;

import net.caffeinemc.mods.sodium.client.gl.GlObject;
import net.caffeinemc.mods.sodium.client.gl.NoGlUnderVulkan;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ShaderBindingContext;
import net.minecraft.resources.ResourceLocation;

import java.util.function.Function;

/** Would link a GL program. VulkaIris overwrites the only caller (SodiumPrograms.createShader). */
public class GlProgram<T> extends GlObject {
	private GlProgram() {
	}

	public static Builder builder(ResourceLocation name) {
		return new Builder(name);
	}

	public static final class Builder {
		private final ResourceLocation name;

		private Builder(ResourceLocation name) {
			this.name = name;
		}

		public Builder attachShader(GlShader shader) {
			return this;
		}

		public Builder bindAttribute(String attribute, int index) {
			return this;
		}

		public <U> GlProgram<U> link(Function<ShaderBindingContext, U> factory) {
			throw new NoGlUnderVulkan("GlProgram " + name);
		}
	}
}
