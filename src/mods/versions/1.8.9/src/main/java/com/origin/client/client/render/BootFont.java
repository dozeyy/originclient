package com.origin.client.client.render;

import com.origin.client.client.gui.Gfx;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.TextureUtil;

import java.awt.image.BufferedImage;

/**
 * 1.8.9 ONLY: Minecraft's own font for the startup loading screen's caption, read straight from
 * vanilla's ascii.png (1.21.1's "boot font", OriginScreenRenderer.bootText there).
 *
 * <p>Why: the startup screen (StartupLoading) draws from FML's mod loading on, long before
 * Minecraft.startGame creates fontRendererObj or the texture manager. So the glyphs come from
 * the jar's ascii.png, uploaded as one raw GL texture, with FontRenderer's own rules (1.8.9,
 * FontRenderer.readFontTexture / renderDefaultChar): a glyph's advance is its last inked column
 * scaled to 8 px plus 1, space is 4, the quad is (advance - 1.01) wide and 7.99 tall, and the
 * shadow is the colour's RGB / 4 drawn 1 px down-right first. Same pixels as the game's font.
 * ASCII only (the caption is "Loading resources NN%"), no unicode fallback.
 *
 * <p>Render thread only; nothing allocated per frame. Fail-soft: if ascii.png can't be read the
 * caption is simply not drawn (the logo and bar still are). {@link #release} frees the texture
 * when the startup screen ends.
 */
final class BootFont {
	private static final String ASCII = "/assets/minecraft/textures/font/ascii.png";

	private static int tex;            // GL texture id, 0 = not loaded
	private static boolean failed;
	private static int cell;           // glyph cell size in the sheet (8 for vanilla's 128 px sheet)
	private static float sheet;        // sheet width in px (UV divisor)
	private static final int[] ADVANCE = new int[256];

	private BootFont() {
	}

	private static boolean load() {
		if (tex > 0) {
			return true;
		}
		if (failed) {
			return false;
		}
		try {
			BufferedImage img = Gfx.readImage(ASCII);
			int w = img.getWidth(), h = img.getHeight();
			int[] px = new int[w * h];
			img.getRGB(0, 0, w, h, px, 0, w);
			int cw = w / 16, ch = h / 16;
			float k = 8.0f / cw;
			// FontRenderer.readFontTexture: the last column with any ink, scaled to 8 px, plus 1.
			for (int i = 0; i < 256; i++) {
				int col = i % 16, row = i / 16;
				int last;
				for (last = cw - 1; last >= 0; last--) {
					boolean empty = true;
					for (int y = 0; y < ch && empty; y++) {
						if (((px[(row * cw + y) * w + col * cw + last] >> 24) & 0xFF) != 0) {
							empty = false;
						}
					}
					if (!empty) {
						break;
					}
				}
				ADVANCE[i] = (int) (0.5 + (last + 1) * k) + 1;
			}
			ADVANCE[' '] = 4;   // FontRenderer.getCharWidth / renderChar: a space is always 4
			int id = TextureUtil.glGenTextures();
			TextureUtil.uploadTextureImageAllocate(id, img, false, false);
			cell = cw;
			sheet = w;
			tex = id;
			return true;
		} catch (Throwable t) {
			failed = true;
			com.origin.client.OriginClient.LOGGER.warn("Origin startup caption font (vanilla ascii.png) failed to load; no caption", t);
			return false;
		}
	}

	/** The advance width of {@code text} in font px, as FontRenderer.getStringWidth (0 if the font can't load). */
	static int width(String text) {
		if (!load()) {
			return 0;
		}
		int w = 0;
		for (int i = 0; i < text.length(); i++) {
			w += ADVANCE[text.charAt(i) & 0xFF];
		}
		return w;
	}

	/** Draws {@code text} at (x, y) in font px with Minecraft's shadow (the caption's drawString(..., true)). */
	static void drawWithShadow(Gfx g, String text, float x, float y, int argb) {
		if (!load()) {
			return;
		}
		int shadow = (argb & 0xFCFCFC) >> 2 | (argb & 0xFF000000);
		Gfx.defaultBlend();
		GlStateManager.enableTexture2D();
		GlStateManager.bindTexture(tex);
		glyphs(g, text, x + 1, y + 1, shadow);
		glyphs(g, text, x, y, argb);
		Gfx.resetColor();
	}

	private static void glyphs(Gfx g, String text, float x, float y, int argb) {
		GlStateManager.color(((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f,
				(argb & 0xFF) / 255f, ((argb >>> 24) & 0xFF) / 255f);
		float pen = x;
		float scale = cell / 8f;   // sheet px per font px (1 for vanilla's 128 px sheet)
		for (int i = 0; i < text.length(); i++) {
			int c = text.charAt(i) & 0xFF;
			int adv = ADVANCE[c];
			if (c != ' ') {
				// FontRenderer.renderDefaultChar: (advance - 0.01) wide minus the 1 px gap, 7.99 tall.
				float qw = adv - 1.01f;
				float u0 = (c % 16) * cell / sheet, v0 = (c / 16) * cell / sheet;
				float u1 = u0 + qw * scale / sheet, v1 = v0 + 7.99f * scale / sheet;
				g.blitBound(pen, y, qw, 7.99f, u0, v0, u1, v1);
			}
			pen += adv;
		}
	}

	/** Frees the glyph texture (the startup screen is over; the game's own font takes over). */
	static void release() {
		if (tex > 0) {
			try {
				TextureUtil.deleteTexture(tex);
			} catch (Throwable ignored) {
				// harmless leak of one small texture
			}
		}
		tex = 0;
	}
}
