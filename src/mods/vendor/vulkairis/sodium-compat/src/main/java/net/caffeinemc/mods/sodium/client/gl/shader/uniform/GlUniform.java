// Origin compatibility stub for VulkaIris (LGPL-3.0-only). Written by Origin from the call sites in
// Iris 1.8.14-beta.1 and VulkaIris; no Sodium code. Same binary name as the Sodium type Iris links
// against, so Iris loads without Sodium under VulkanMod. See sodium-compat/README.md.
package net.caffeinemc.mods.sodium.client.gl.shader.uniform;

/** A uniform at a GL location; Iris's GlUniformMatrix3f extends it. */
public abstract class GlUniform<T> {
	protected final int index;

	protected GlUniform(int index) {
		this.index = index;
	}

	public abstract void set(T value);
}
