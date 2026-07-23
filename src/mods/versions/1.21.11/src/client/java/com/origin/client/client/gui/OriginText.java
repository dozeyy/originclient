package com.origin.client.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;

/**
 * Custom-font text for the Origin menus — Inter, the way other premium clients
 * do it: NOT a hand-rolled glyph blitter, but Minecraft's OWN TrueType font
 * pipeline. A single {@link Font} instance can render ANY registered font — you
 * just hand it a {@link Component} whose {@link net.minecraft.network.chat.Style}
 * names the font. FontManager loads every {@code assets/originclient/font/*.json}
 * provider at startup, so {@code originclient:inter} resolves to the bundled
 * Inter TTF and is rasterised by stbtt exactly like vanilla's own fonts — crisp
 * at any size.
 *
 * In-game/world text (HUD, chat, nametags) is deliberately NOT routed through
 * here: it stays vanilla Minecraft font. And it fails soft: if the provider ever
 * fails to load, Minecraft's Font silently falls back to the default glyphs, so
 * a bad asset degrades to vanilla text instead of crashing.
 */
public final class OriginText {
	public static final FontDescription REGULAR =
			new FontDescription.Resource(Identifier.fromNamespaceAndPath("originclient", "inter"));
	public static final FontDescription SEMIBOLD =
			new FontDescription.Resource(Identifier.fromNamespaceAndPath("originclient", "inter_semibold"));

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
	// DIAGNOSTIC STEP 3 (2026-07-23): step 2 confirmed the TTF font provider
	// works (Inter renders via vanilla's font pipeline). Bug is now fully
	// isolated to the MSDF shader/pipeline. Re-enabling OriginSdfFont with
	// added instrumentation (see OriginSdfFont.emit) to get real evidence of
	// what's happening at the actual draw call.

	public static int draw(GuiGraphics g, Font font, String s, int x, int y, int color, boolean shadow) {
		if (OriginSdfFont.active()) {
			OriginSdfFont.draw(g, s, x, y, color, shadow, false);
			return x + OriginSdfFont.width(s, false);
		}
		MutableComponent c = regular(s);
		g.drawString(font, c, x, y, color, shadow);
		return x + font.width(c);
	}

	public static int drawBold(GuiGraphics g, Font font, String s, int x, int y, int color, boolean shadow) {
		if (OriginSdfFont.active()) {
			OriginSdfFont.draw(g, s, x, y, color, shadow, true);
			return x + OriginSdfFont.width(s, true);
		}
		MutableComponent c = semibold(s);
		g.drawString(font, c, x, y, color, shadow);
		return x + font.width(c);
	}

	// ---- measure (matches the weight actually drawn) ----

	public static int width(Font font, String s) {
		return OriginSdfFont.active() ? OriginSdfFont.width(s, false) : font.width(regular(s));
	}

	public static int widthBold(Font font, String s) {
		return OriginSdfFont.active() ? OriginSdfFont.width(s, true) : font.width(semibold(s));
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
