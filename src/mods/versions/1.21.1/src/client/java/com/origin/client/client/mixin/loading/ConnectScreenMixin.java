package com.origin.client.client.mixin.loading;

import com.origin.client.client.render.OriginScreenRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ConnectScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// "Connecting to the server..." Unlike the pure loading screens this has a live
// Cancel button + status text, so we do NOT take over render: the Origin menu
// background already comes from ScreenBackgroundMixin (level == null while
// connecting) and the button is already Origin-styled -- we only add the
// sweeping bar on top at TAIL, after vanilla has drawn its content and widgets.
@Mixin(ConnectScreen.class)
public class ConnectScreenMixin {

	@Inject(method = "render", at = @At("TAIL"))
	private void originclient$originBar(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		OriginScreenRenderer.renderConnectingBar(guiGraphics);
	}
}
