// Origin compatibility stub for VulkaIris (LGPL-3.0-only). Written by Origin from the call sites in
// Iris 1.8.14-beta.1 and VulkaIris; no Sodium code. Same binary name as the Sodium type Iris links
// against, so Iris loads without Sodium under VulkanMod. See sodium-compat/README.md.
package net.caffeinemc.mods.sodium.client.gl;

/**
 * Thrown by every stub that would create an OpenGL object. Under VulkanMod there is no GL context, and
 * VulkaIris replaces Iris's Sodium terrain programs (SodiumProgramsMixin), so reaching one is a VulkaIris bug.
 */
public final class NoGlUnderVulkan extends UnsupportedOperationException {
	public NoGlUnderVulkan(String what) {
		super(what + " needs Sodium's OpenGL renderer, which does not exist under VulkanMod");
	}
}
