package com.origin.client.client.shaders;

import com.origin.client.client.gui.OriginUi;
import com.origin.client.client.theme.OriginTheme;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.gui.Gfx;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

// A dark, Origin-themed button injected into Iris's Shader Packs screen so the
// "Download Shaders" entry point reads as ours and stands out against Iris's
// lighter vanilla widgets. Same behavior as a vanilla Button; only the paint
// is overridden (rounded dark panel + centered label).
public class OriginShaderButton extends Button {
	public OriginShaderButton(int x, int y, int w, int h, Component label, OnPress onPress) {
		// 1.18.2 Button: plain (x,y,w,h,label,onPress) ctor — Button.builder
		// and the narration supplier are 1.19.3+.
		super(x, y, w, h, label, onPress);
	}

	// 1.16.5: the widget paint method is renderButton (renderWidget is 1.19.4+);
	// position is the public x/y fields (getX/getY are 1.19.3+).
	@Override
	public void renderButton(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
		Gfx g = new Gfx(poseStack);
		boolean hover = isHovered();   // 1.16.5: isHovered() covers keyboard focus too
		float hp = OriginUi.hover("shader-entry:" + System.identityHashCode(this), hover);
		OriginUi.panel(g, this.x, this.y, getWidth(), getHeight(), 7,
				OriginTheme.lerpColor(0xF0161616, 0xF0242424, hp),
				OriginTheme.lerpColor(OriginTheme.STROKE, OriginTheme.STROKE_STRONG, hp));
		Font font = Minecraft.getInstance().font;
		int tw = font.width(getMessage());
		g.drawString(font, getMessage(), this.x + (getWidth() - tw) / 2,
				this.y + (getHeight() - 8) / 2,
				OriginTheme.lerpColor(OriginTheme.TEXT_DIM, OriginTheme.TEXT, hp), false);
	}
}
