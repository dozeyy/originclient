// Origin compatibility stub for VulkaIris (LGPL-3.0-only). Written by Origin from the call sites in
// Iris 1.8.14-beta.1 and VulkaIris; no Sodium code. Same binary name as the Sodium type Iris links
// against, so Iris loads without Sodium under VulkanMod. See sodium-compat/README.md.
package net.caffeinemc.mods.sodium.api.util;

/** Colour packed as ARGB. Only Iris's Sodium terrain encoder calls it. */
public final class ColorARGB {
	private ColorARGB() { }

	/** Multiplies the three colour channels by factor, keeping alpha. */
	public static int mulRGB(int color, float factor) {
		int r = scale((color >>> 16) & 0xFF, factor);
		int g = scale((color >>> 8) & 0xFF, factor);
		int b = scale(color & 0xFF, factor);
		return (color & 0xFF000000) | (r << 16) | (g << 8) | b;
	}

	private static int scale(int channel, float factor) {
		return Math.max(0, Math.min(255, Math.round(channel * factor)));
	}
}
