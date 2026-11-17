// Origin compatibility stub for VulkaIris (LGPL-3.0-only). Written by Origin from the call sites in
// Iris 1.8.14-beta.1 and VulkaIris; no Sodium code. Same binary name as the Sodium type Iris links
// against, so Iris loads without Sodium under VulkanMod. See sodium-compat/README.md.
package net.caffeinemc.mods.sodium.client.gl;

/** Base of the GL object stubs; handle() is what Iris's SodiumPrograms reads. */
public abstract class GlObject {
	private int handle;

	protected final void setHandle(int handle) {
		this.handle = handle;
	}

	public int handle() {
		return handle;
	}
}
