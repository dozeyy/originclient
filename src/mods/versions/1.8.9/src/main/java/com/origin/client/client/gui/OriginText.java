package com.origin.client.client.gui;

import net.minecraft.client.gui.FontRenderer;

/** Minecraft-native text bridge for every Origin-owned screen. 1.8.9: FontRenderer. */
public final class OriginText {
	private OriginText() {
	}

	public static int draw(Gfx g, FontRenderer font, String s, int x, int y, int color, boolean shadow) {
		return g.drawString(font, s, x, y, color, shadow);
	}

	public static int drawBold(Gfx g, FontRenderer font, String s, int x, int y, int color, boolean shadow) {
		return g.drawString(font, s, x, y, color, shadow);
	}

	public static int width(FontRenderer font, String s) {
		return font.getStringWidth(s);
	}

	public static int widthBold(FontRenderer font, String s) {
		return font.getStringWidth(s);
	}

	public static String ellipsize(FontRenderer font, String s, int maxW) {
		if (font.getStringWidth(s) <= maxW) {
			return s;
		}
		// 1.8.9's default font has no "…" glyph in ascii.png (it falls back to the
		// unicode page), so three dots read the same everywhere.
		String ellipsis = "...";
		return font.trimStringToWidth(s, Math.max(0, maxW - font.getStringWidth(ellipsis))) + ellipsis;
	}
}
