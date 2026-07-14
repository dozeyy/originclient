package com.origin.client.client.mixin;

import com.origin.client.client.render.OriginScreenRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// CreateWorldScreen blits a footer-separator line above its Create/Cancel
// buttons, matching the header-separator TabNavigationBarMixin already
// suppresses. On an Origin scene it reads as a stray vanilla bar over the
// rings, so it's skipped the same way. Fail-soft: if Origin rendering is
// unhealthy, the vanilla line returns.
@Mixin(value = CreateWorldScreen.class, priority = 2000)
public class CreateWorldScreenMixin {

	@Redirect(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/GuiGraphics;blit(Lnet/minecraft/resources/ResourceLocation;IIFFIIII)V"))
	private void originclient$noFooterSeparator(GuiGraphics guiGraphics, ResourceLocation texture, int x, int y,
												float u, float v, int width, int height, int texWidth, int texHeight) {
		if (Minecraft.getInstance().level == null && OriginScreenRenderer.isActive()) {
			return; // skip the footer-separator line
		}
		guiGraphics.blit(texture, x, y, u, v, width, height, texWidth, texHeight);
	}
}
