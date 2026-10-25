package com.origin.client.client.mixin;

import com.origin.client.client.gui.OriginButtonRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.TooltipRenderUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Title screen only: replaces vanilla's purple gradient tooltip box with the Workbench tooltip panel
// (DESIGN-SYSTEM 11.8). OriginButtonRenderer.renderTitleTooltip answers false for every other screen
// (Mod Menu and all of Minecraft's own menus keep vanilla's box byte for byte), for the vanilla Main
// Menu Style and after a latched failure, so this inject then does nothing.
// Target: TooltipRenderUtil.renderTooltipBackground(GuiGraphics, x, y, w, h, z) -- x/y/w/h are the text
// block (vanilla draws 3 px around it). If it stops applying, title tooltips fall back to the purple box.
@Mixin(TooltipRenderUtil.class)
public class TooltipRenderUtilMixin {

	@Inject(method = "renderTooltipBackground", at = @At("HEAD"), cancellable = true)
	// 1.21.2+ (javap 1.21.4): renderTooltipBackground(GuiGraphics, IIIII, ResourceLocation style) -- the trailing
	// tooltip-style sprite id (null = vanilla's default). The title tooltip ignores it.
	private static void originclient$titleTooltip(GuiGraphics guiGraphics, int x, int y, int width, int height, int z,
			net.minecraft.resources.ResourceLocation style, CallbackInfo ci) {
		if (OriginButtonRenderer.renderTitleTooltip(guiGraphics, x, y, width, height, z)) {
			ci.cancel();
		}
	}
}
