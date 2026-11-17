// Origin compatibility stub for VulkaIris (LGPL-3.0-only). Written by Origin from the call sites in
// Iris 1.8.14-beta.1 and VulkaIris; no Sodium code. Same binary name as the Sodium type Iris links
// against, so Iris loads without Sodium under VulkanMod. See sodium-compat/README.md.
package net.caffeinemc.mods.sodium.client.gl.shader;

/** Holder for the parsed-source record Iris builds before it would create a GL shader. */
public final class ShaderParser {
	private ShaderParser() { }

	public record ParsedShader(String src, String[] includeIds) { }
}
