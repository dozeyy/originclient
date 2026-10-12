package com.origin.client.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/** Minecraft-native text bridge for every Origin-owned screen. */
public final class OriginText {
	private OriginText() {
	}

	public static int draw(GuiGraphicsExtractor g, Font font, String s, int x, int y, int color, boolean shadow) {
		g.text(font, Component.literal(s), x, y, color, shadow);
		return x + font.width(s);
	}

	public static int drawBold(GuiGraphicsExtractor g, Font font, String s, int x, int y, int color, boolean shadow) {
		g.text(font, Component.literal(s), x, y, color, shadow);
		return x + font.width(s);
	}

	public static int width(Font font, String s) {
		return font.width(s);
	}

	public static int widthBold(Font font, String s) {
		return font.width(s);
	}

	public static String ellipsize(Font font, String s, int maxW) {
		if (font.width(s) <= maxW) {
			return s;
		}
		String ellipsis = "…";
		return font.plainSubstrByWidth(s, Math.max(0, maxW - font.width(ellipsis))) + ellipsis;
	}
}
