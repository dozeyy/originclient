package com.origin.client.client.mixin;

import com.origin.client.client.render.OriginScreenRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// The tab header bar (a) fills an opaque black strip across the top, then
// (b) blits a header-separator line texture, before the tabs draw. On an
// Origin scene both read as vanilla bars over the rings, so both are
// suppressed (the restyled tabs from TabButtonMixin sit directly on the
// Origin backdrop instead). Fail-soft: if Origin rendering is unhealthy,
// both vanilla draws return.
@Mixin(value = TabNavigationBar.class, priority = 2000)
public class TabNavigationBarMixin {

	@Redirect(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/GuiGraphics;fill(IIIII)V"))
	private void originclient$noBar(GuiGraphics guiGraphics, int x1, int y1, int x2, int y2, int color) {
		if (Minecraft.getInstance().level == null && OriginScreenRenderer.isActive()) {
			return; // skip the black tab-bar fill
		}
		guiGraphics.fill(x1, y1, x2, y2, color);
	}

	@Redirect(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/GuiGraphics;blit(Lnet/minecraft/resources/ResourceLocation;IIFFIIII)V"))
	private void originclient$noHeaderSeparator(GuiGraphics guiGraphics, ResourceLocation texture, int x, int y,
												float u, float v, int width, int height, int texWidth, int texHeight) {
		if (Minecraft.getInstance().level == null && OriginScreenRenderer.isActive()) {
			return; // skip the header-separator line
		}
		guiGraphics.blit(texture, x, y, u, v, width, height, texWidth, texHeight);
	}
}
