// Origin compatibility stub for VulkaIris (LGPL-3.0-only). Written by Origin from the call sites in
// Iris 1.8.14-beta.1 and VulkaIris; no Sodium code. Same binary name as the Sodium type Iris links
// against, so Iris loads without Sodium under VulkanMod. See sodium-compat/README.md.
package net.caffeinemc.mods.sodium.client.gl.shader;

import net.caffeinemc.mods.sodium.client.gl.GlObject;
import net.caffeinemc.mods.sodium.client.gl.NoGlUnderVulkan;
import net.minecraft.resources.ResourceLocation;

/** Would compile a GL shader. VulkaIris overwrites the only caller (SodiumPrograms.createGlShaders). */
public class GlShader extends GlObject {
	public GlShader(ShaderType type, ResourceLocation name, ShaderParser.ParsedShader parsed) {
		throw new NoGlUnderVulkan("GlShader " + name);
	}

	public void delete() {
	}
}
