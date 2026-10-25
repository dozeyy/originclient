package com.origin.client.client.mixin;

import com.origin.client.client.gui.OriginButtonRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.TooltipRenderUtil;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Title screen only: replaces vanilla's tooltip box with the Workbench tooltip panel (DESIGN-SYSTEM 11.8).
// OriginButtonRenderer.renderTitleTooltip answers false for every other screen (Mod Menu and all of
// Minecraft's own menus keep vanilla's box byte for byte), for the vanilla Main Menu Style and after a
// latched failure, so this inject then does nothing.
// Target (javap 1.21.10): static TooltipRenderUtil.renderTooltipBackground(GuiGraphics, x, y, w, h,
// ResourceLocation style) -- no z in the deferred GUI; x/y/w/h are the text block. If it stops applying,
// title tooltips fall back to vanilla's box.
@Mixin(TooltipRenderUtil.class)
public class TooltipRenderUtilMixin {

	@Inject(method = "renderTooltipBackground", at = @At("HEAD"), cancellable = true)
	private static void originclient$titleTooltip(GuiGraphics guiGraphics, int x, int y, int width, int height,
												   ResourceLocation style, CallbackInfo ci) {
		if (OriginButtonRenderer.renderTitleTooltip(guiGraphics, x, y, width, height)) {
			ci.cancel();
		}
	}
}
