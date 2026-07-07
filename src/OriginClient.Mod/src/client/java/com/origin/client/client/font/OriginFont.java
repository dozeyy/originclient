package com.origin.client.client.font;

import net.minecraft.client.gui.GuiGraphics;

// Draws text from an OriginFontAtlas via GuiGraphics's existing textured-quad
// blit -- no custom shader. Requested pixel size is applied as a PoseStack
// scale around the atlas's native (64px-em) glyph quads rather than a
// separate blit-region size, so this doesn't depend on a resizing blit
// overload. Tint/color is not implemented yet (M3 only needs to confirm
// smoothness); OriginHudPanel (M5) is what needs real per-row coloring.
public final class OriginFont {
	private OriginFont() {
	}

	/** Draws text at the given top-left position and pixel size (glyph em height, roughly). Returns the rendered width in pixels. */
	public static float drawString(GuiGraphics guiGraphics, String text, float x, float y, int weight, float pxSize) {
		OriginFontAtlas atlas = OriginFontAtlas.get(weight);
		float scale = pxSize / atlas.emSize;

		guiGraphics.pose().pushPose();
		guiGraphics.pose().translate(x, y, 0);
		guiGraphics.pose().scale(scale, scale, 1f);

		float penX = 0f;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			OriginFontAtlas.Glyph glyph = atlas.glyph(c);
			if (glyph == null) {
				continue;
			}
			if (glyph.width() > 0 && glyph.height() > 0) {
				guiGraphics.blit(atlas.textureId,
						Math.round(penX + glyph.bearingX()), Math.round(glyph.bearingY()),
						glyph.x(), glyph.y(),
						glyph.width(), glyph.height(),
						atlas.atlasWidth, atlas.atlasHeight);
			}
			penX += glyph.advance();
		}

		guiGraphics.pose().popPose();
		return penX * scale;
	}

	/** Measures rendered width in pixels without drawing. */
	public static float measure(String text, int weight, float pxSize) {
		OriginFontAtlas atlas = OriginFontAtlas.get(weight);
		float scale = pxSize / atlas.emSize;
		float penX = 0f;
		for (int i = 0; i < text.length(); i++) {
			OriginFontAtlas.Glyph glyph = atlas.glyph(text.charAt(i));
			if (glyph != null) {
				penX += glyph.advance();
			}
		}
		return penX * scale;
	}
}
