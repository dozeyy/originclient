// Origin compatibility stub for VulkaIris (LGPL-3.0-only). Written by Origin from the call sites in
// Iris 1.8.14-beta.1 and VulkaIris; no Sodium code. Same binary name as the Sodium type Iris links
// against, so Iris loads without Sodium under VulkanMod. See sodium-compat/README.md.
package net.caffeinemc.mods.sodium.client.gl.shader;

/** Shader stages by their GL enum value (plain numbers here, no GL calls). */
public enum ShaderType {
	VERTEX(0x8B31),
	FRAGMENT(0x8B30),
	GEOMETRY(0x8DD9),
	TESS_CONTROL(0x8E88),
	TESS_EVALUATION(0x8E87);

	public final int id;

	ShaderType(int id) {
		this.id = id;
	}

	/** The stage for a GL shader type enum, or null when there is none. */
	public static ShaderType fromGlShaderType(int glType) {
		for (ShaderType type : values()) {
			if (type.id == glType) {
				return type;
			}
		}
		return null;
	}
}
