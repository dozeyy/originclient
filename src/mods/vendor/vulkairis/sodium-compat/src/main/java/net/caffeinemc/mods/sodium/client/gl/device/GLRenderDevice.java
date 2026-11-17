// Origin compatibility stub for VulkaIris (LGPL-3.0-only). Written by Origin from the call sites in
// Iris 1.8.14-beta.1 and VulkaIris; no Sodium code. Same binary name as the Sodium type Iris links
// against, so Iris loads without Sodium under VulkanMod. See sodium-compat/README.md.
package net.caffeinemc.mods.sodium.client.gl.device;

/**
 * Device facts Iris's SodiumShader would read. No GL query is possible under VulkanMod; 8 sub-texel bits is
 * what desktop GPUs report, so it is the safe answer. Iris has a mixin into Sodium's ImmediateDrawCommandList
 * (an inner class of the real GLRenderDevice); that class is deliberately absent so the mixin finds no target.
 */
public final class GLRenderDevice implements RenderDevice {
	GLRenderDevice() {
	}

	@Override
	public int getSubTexelPrecisionBits() {
		return 8;
	}
}
