package com.origin.client.client.mixin;

import com.origin.client.client.gui.OriginButtonRenderer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.TooltipRenderUtil;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Title screen only (1.21.1's Workbench look): replaces vanilla's tooltip box with the Workbench tooltip
// panel (DESIGN-SYSTEM 11.8). OriginButtonRenderer.renderTitleTooltip answers false for every other
// screen (the Mod Menu and all of Minecraft's own menus keep vanilla's box), for the vanilla Main Menu
// Style and after a latched failure, so this inject then does nothing.
// Target (javap 26.1.2 / 26.2 / 26.3): static TooltipRenderUtil.extractTooltipBackground(
// GuiGraphicsExtractor, x, y, w, h, Identifier background), called from GuiGraphicsExtractor's tooltip
// pass inside its own pose push; x/y/w/h are the text block. No z in this era. If it stops applying,
// title tooltips fall back to vanilla's box.
@Mixin(TooltipRenderUtil.class)
public class TooltipRenderUtilMixin {

	@Inject(method = "extractTooltipBackground", at = @At("HEAD"), cancellable = true)
	private static void originclient$titleTooltip(GuiGraphicsExtractor g, int x, int y, int width, int height,
			Identifier background, CallbackInfo ci) {
		if (OriginButtonRenderer.renderTitleTooltip(g, x, y, width, height)) {
			ci.cancel();
		}
	}
}
