package com.origin.client.client.mods;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * Pack Icon Downscale (SETTINGS > Performance). The resource pack list uploads each
 * pack's pack.png as a texture and draws it at 32x32 GUI px, but packs ship icons
 * of 256 up to 4096 px square: a 4096 px icon is a 64 MB RGBA decode + upload per
 * pack, on the render thread, every time the list opens. This shrinks anything over
 * {@link #SIZE} px down to {@link #SIZE} px (enough for the 32 GUI px slot even at
 * GUI scale 2) before it becomes a texture. The idea is Patcher's; the code is ours.
 * Halves repeatedly rather than one big bilinear step, which skips source pixels
 * and shimmers.
 */
public final class PackIcons {
	private PackIcons() {
	}

	public static final int SIZE = 64;

	/** The icon to use: {@code img} itself when small, off, or on any failure; else a SIZE x SIZE copy. */
	public static BufferedImage shrink(BufferedImage img) {
		try {
			if (img == null || (img.getWidth() <= SIZE && img.getHeight() <= SIZE)
					|| !Mods.bool(Mods.PERFORMANCE_ID, "packIconDownscale")) {
				return img;
			}
			BufferedImage cur = img;
			while (cur.getWidth() > SIZE * 2 || cur.getHeight() > SIZE * 2) {
				cur = scale(cur, Math.max(SIZE, cur.getWidth() / 2), Math.max(SIZE, cur.getHeight() / 2));
			}
			return scale(cur, SIZE, SIZE);
		} catch (Throwable t) {
			return img;   // the full-size icon, as vanilla
		}
	}

	private static BufferedImage scale(BufferedImage src, int w, int h) {
		BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.drawImage(src, 0, 0, w, h, null);
		} finally {
			g.dispose();
		}
		return out;
	}
}
