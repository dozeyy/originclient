package com.origin.client.client.shaders;

import com.origin.client.client.OriginStyle;
import com.origin.client.client.gui.OriginUi;
import com.origin.client.client.theme.OriginTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

// A dark, Origin-themed button injected into Iris's Shader Packs screen so the
// "Download Shaders" entry point reads as ours and stands out against Iris's
// lighter vanilla widgets. Same behavior as a vanilla Button; only the paint
// is overridden (rounded dark panel + centered label).
public class OriginShaderButton extends Button {
	public OriginShaderButton(int x, int y, int w, int h, Component label, OnPress onPress) {
		super(x, y, w, h, label, onPress, DEFAULT_NARRATION);
	}

	// 1.21.11: renderWidget AND renderDefaultSprite are final; renderContents
	// is the override point. Our panel is near-opaque, so it fully covers the
	// vanilla sprite the final renderWidget draws first.
	@Override
	protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		// Button Style = Minecraft: this entry is a plain stock button like its neighbours.
		if (OriginStyle.vanillaButtonsOn(Minecraft.getInstance().gui.screen())) {
			extractDefaultSprite(g);
			extractDefaultLabel(g.textRendererForWidget(this, GuiGraphicsExtractor.HoveredTextEffects.NONE));
			return;
		}
		boolean hover = isHoveredOrFocused();
		float hp = OriginUi.hover("shader-entry:" + System.identityHashCode(this), hover);
		OriginUi.panel(g, getX(), getY(), getWidth(), getHeight(), 7,
				OriginTheme.lerpColor(0xF0161616, 0xF0242424, hp),
				OriginTheme.lerpColor(OriginTheme.STROKE, OriginTheme.STROKE_STRONG, hp));
		Font font = Minecraft.getInstance().font;
		int tw = font.width(getMessage());
		g.text(font, getMessage(), getX() + (getWidth() - tw) / 2,
				getY() + (getHeight() - 8) / 2,
				OriginTheme.lerpColor(OriginTheme.TEXT_DIM, OriginTheme.TEXT, hp), false);
	}
}
