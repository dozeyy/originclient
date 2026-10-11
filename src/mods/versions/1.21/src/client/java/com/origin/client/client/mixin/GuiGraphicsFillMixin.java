package com.origin.client.client.mixin;

import com.origin.client.client.gui.OriginColorPicker;
import com.origin.client.client.hud.HudElements;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

// Scoreboard Background Color, 1.20.3+ half. Vanilla draws the sidebar's two
// background bands with fill(x1, y1, x2, y2, color) from inside a drawManaged
// lambda (see GuiScoreboardMixin for why that can't be redirected directly).
// While HudElements.sidebarBgActive is up -- only for the span of that one
// drawManaged call, with the Scoreboard mod on -- the colour argument becomes
// the mod's bgColor (default fully transparent). Every other fill() in the
// game passes through unchanged.
@Mixin(GuiGraphics.class)
public class GuiGraphicsFillMixin {

	// ordinal 4 = the fifth int parameter of fill(IIIII)V: x1, y1, x2, y2, COLOR.
	@ModifyVariable(method = "fill(IIIII)V", at = @At("HEAD"), argsOnly = true, ordinal = 4)
	private int originclient$sidebarBgColor(int color) {
		return HudElements.sidebarBgActive ? OriginColorPicker.liveColor("scoreboard", "bgColor") : color;
	}
}
