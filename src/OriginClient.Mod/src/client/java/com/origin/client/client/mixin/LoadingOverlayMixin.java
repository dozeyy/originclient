package com.origin.client.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.LoadingOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Draws the Origin wordmark over vanilla's resource-loading screen instead of
// replacing the progress bar itself — keeps the real loading behavior intact,
// just brands it.
@Mixin(LoadingOverlay.class)
public class LoadingOverlayMixin {
	@Inject(method = "render", at = @At("TAIL"))
	private void originclient$drawBranding(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		Minecraft client = Minecraft.getInstance();
		int width = client.getWindow().getGuiScaledWidth();
		int height = client.getWindow().getGuiScaledHeight();
		guiGraphics.drawCenteredString(client.font, "ORIGIN CLIENT", width / 2, height / 2 + 40, 0xE0E0E0);
	}
}
