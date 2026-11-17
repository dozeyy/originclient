// Origin compatibility stub for VulkaIris (LGPL-3.0-only). Written by Origin from the call sites in
// Iris 1.8.14-beta.1 and VulkaIris; no Sodium code. Same binary name as the Sodium type Iris links
// against, so Iris loads without Sodium under VulkanMod. See sodium-compat/README.md.
package net.caffeinemc.mods.sodium.api.util;

/** Colour packed as ABGR (alpha in the top byte). Only Iris's Sodium terrain encoder calls it. */
public final class ColorABGR {
	private ColorABGR() { }

	/** The same colour with its alpha set to alpha (0..1). */
	public static int withAlpha(int color, float alpha) {
		int a = Math.round(Math.max(0.0f, Math.min(1.0f, alpha)) * 255.0f);
		return (color & 0x00FFFFFF) | (a << 24);
	}
}
