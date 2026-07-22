package com.origin.client.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;

/**
 * Custom-font text for the Origin menus — Inter, the way other premium clients
 * do it: NOT a hand-rolled glyph blitter (three of those failed live, see
 * MEMORY.md), but Minecraft's OWN TrueType font pipeline. The trick is that a
 * single {@link Font} instance can render ANY registered font — you just hand it
 * a {@link Component} whose {@link net.minecraft.network.chat.Style} names the
 * font. FontManager loads every {@code assets/originclient/font/*.json} provider
 * at startup, so {@code originclient:inter} resolves to the bundled Inter TTF and
 * is rasterised by stbtt exactly like vanilla's own fonts — crisp at any size.
 *
 * In-game/world text (HUD, chat, nametags) is deliberately NOT routed through
 * here: it stays vanilla Minecraft font (Will's rule — custom font in the custom
 * menus only). And it fails soft: if the provider ever fails to load, Minecraft's
 * Font silently falls back to the default glyphs, so a bad asset degrades to
 * vanilla text instead of crashing.
 */
public final class OriginText {
	public static final ResourceLocation REGULAR =
			ResourceLocation.fromNamespaceAndPath("originclient", "inter");
	public static final ResourceLocation SEMIBOLD =
			ResourceLocation.fromNamespaceAndPath("originclient", "inter_semibold");

	private OriginText() {
	}

	/** A literal styled to render in Inter (regular weight). */
	public static MutableComponent regular(String s) {
		return Component.literal(s).withStyle(st -> st.withFont(REGULAR));
	}

	/** A literal styled to render in Inter (semibold) — for names/headers. */
	public static MutableComponent semibold(String s) {
		return Component.literal(s).withStyle(st -> st.withFont(SEMIBOLD));
	}

	// ---- draw ----

	public static int draw(GuiGraphics g, Font font, String s, int x, int y, int color, boolean shadow) {
		return g.drawString(font, regular(s), x, y, color, shadow);
	}

	public static int drawBold(GuiGraphics g, Font font, String s, int x, int y, int color, boolean shadow) {
		return g.drawString(font, semibold(s), x, y, color, shadow);
	}

	// ---- measure (matches the weight actually drawn) ----

	public static int width(Font font, String s) {
		return font.width(regular(s));
	}

	public static int widthBold(Font font, String s) {
		return font.width(semibold(s));
	}

	/** Trim `s` to fit `maxW` px in Inter, appending an ellipsis when clipped. */
	public static String ellipsize(Font font, String s, int maxW) {
		if (width(font, s) <= maxW) {
			return s;
		}
		String ell = "…";
		int ew = width(font, ell);
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < s.length(); i++) {
			if (width(font, sb.toString() + s.charAt(i)) + ew > maxW) {
				break;
			}
			sb.append(s.charAt(i));
		}
		return sb.append(ell).toString();
	}
}
