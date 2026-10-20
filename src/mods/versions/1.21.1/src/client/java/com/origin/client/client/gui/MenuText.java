package com.origin.client.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;

import java.util.Optional;

/**
 * Text for the clean in-game look (Will's approved mockup, 2026-10-19): Inter
 * semibold through the MSDF renderer, sized in GUI px and snapped to whole
 * PHYSICAL pixels so labels stay crisp at every GUI scale.
 *
 * Fail-soft per string: when the MSDF font isn't ready, or the string has a
 * character the Inter atlas lacks (CJK, Cyrillic, symbols), {@link #usable}
 * is false and the caller draws with the vanilla font instead, so nothing ever
 * renders as blank gaps.
 *
 * {@link #inScope()} is the render scope the text mixins (GuiGraphics
 * drawCenteredString, StringWidget) check: true only while a vanilla Minecraft
 * screen that takes the clean look is rendering (set by MenuTextScopeMixin).
 */
public final class MenuText {
	private MenuText() {
	}

	// Set by MenuTextScopeMixin around Screen.renderWithTooltip; render thread only.
	private static boolean scope = false;

	public static void setScope(boolean on) {
		scope = on;
	}

	public static boolean inScope() {
		return scope;
	}

	/** True when this string can be drawn by the SDF face with no missing glyphs. */
	public static boolean usable(String s) {
		return s != null && !s.isEmpty() && OriginSdfFont.ready() && OriginSdfFont.covers(s, true);
	}

	/** Advance width in GUI px at em size {@code em}. */
	public static float width(String s, float em) {
		return OriginSdfFont.widthEm(s, em, 0f, true);
	}

	private static double guiScale() {
		return Math.max(1.0, Minecraft.getInstance().getWindow().getGuiScale());
	}

	/**
	 * Draws {@code s} with its left edge at x and its CAP HEIGHT centred on
	 * {@code midY} (all GUI px). Position and size are converted to physical
	 * pixels and the x/baseline rounded there, so glyph edges land on the pixel
	 * grid. {@code tracking} is extra GUI px between glyphs.
	 */
	public static void drawMid(GuiGraphics g, String s, double x, double midY, float em, float tracking, int argb) {
		if ((argb >>> 24) == 0) {
			return;
		}
		double gs = guiScale();
		float emP = (float) (em * gs);
		float capP = OriginSdfFont.capHeight(emP, true);
		float px = Math.round(x * gs);
		float base = Math.round(midY * gs + capP / 2.0);
		PoseStack pose = g.pose();
		pose.pushPose();
		pose.scale((float) (1.0 / gs), (float) (1.0 / gs), 1f);
		// A soft 1-physical-px drop keeps labels legible over the bright parts of
		// the blurred panorama without the hard vanilla shadow.
		int sa = Math.round(((argb >>> 24) & 0xFF) * 0.45f);
		if (sa > 0 && isLight(argb)) {
			OriginSdfFont.drawEm(g, s, px, base + 1f, emP, (float) (tracking * gs), (sa << 24), true);
		}
		OriginSdfFont.drawEm(g, s, px, base, emP, (float) (tracking * gs), argb, true);
		pose.popPose();
	}

	/** {@link #drawMid} with the text centred on {@code cx}. */
	public static void drawCentered(GuiGraphics g, String s, double cx, double midY, float em, float tracking, int argb) {
		float w = OriginSdfFont.widthEm(s, em, tracking, true);
		drawMid(g, s, cx - w / 2.0, midY, em, tracking, argb);
	}

	/**
	 * A label centred in a box, shrunk (down to 80%) to fit {@code maxW}. Returns
	 * false when it still doesn't fit or can't be drawn by the SDF face, so the
	 * caller falls back to the vanilla font (which clips/scrolls long text).
	 */
	public static boolean drawFitted(GuiGraphics g, String s, double cx, double midY, float em, double maxW, int argb) {
		if (!usable(s)) {
			return false;
		}
		float w = width(s, em);
		float use = em;
		if (w > maxW) {
			use = (float) (em * maxW / w);
			if (use < em * 0.8f) {
				return false;
			}
		}
		drawCentered(g, s, cx, midY, use, 0f, argb);
		return true;
	}

	/**
	 * Vanilla's colour convention: an alpha byte of 0 means opaque (GuiGraphics
	 * passes 0xFFFFFF for white). Pure white maps to the clean text token so
	 * every label is the same off-white.
	 */
	public static int vanillaColor(int color) {
		if ((color & 0xFC000000) == 0) {
			color |= 0xFF000000;
		}
		if ((color & 0xFFFFFF) == 0xFFFFFF) {
			color = (color & 0xFF000000) | (com.origin.client.client.theme.OriginTheme.CLEAN_TEXT & 0xFFFFFF);
		}
		return color;
	}

	/**
	 * The one colour a component is drawn in, or null when it mixes colours
	 * (several styled parts) — then the caller keeps the vanilla font, which
	 * renders each part in its own colour.
	 */
	public static Integer singleColor(Component c, int fallback) {
		final Integer[] seen = {null};
		final boolean[] mixed = {false};
		c.visit((style, text) -> {
			if (!text.isEmpty()) {
				TextColor tc = style.getColor();
				int col = tc == null ? fallback : (tc.getValue() | (fallback & 0xFF000000));
				if (seen[0] == null) {
					seen[0] = col;
				} else if (seen[0] != col) {
					mixed[0] = true;
				}
			}
			return Optional.empty();
		}, net.minecraft.network.chat.Style.EMPTY);
		if (mixed[0]) {
			return null;
		}
		return seen[0] == null ? fallback : seen[0];
	}

	/** Vanilla-font fallback: a label centred in a box at the vanilla size. */
	public static void vanillaCentered(GuiGraphics g, String s, double cx, double midY, int argb) {
		Font font = Minecraft.getInstance().font;
		g.drawString(font, s, (int) Math.round(cx - font.width(s) / 2.0), (int) Math.round(midY - 4), argb, true);
	}

	private static boolean isLight(int argb) {
		int r = (argb >> 16) & 0xFF, gg = (argb >> 8) & 0xFF, b = argb & 0xFF;
		return r + gg + b > 300;
	}
}
