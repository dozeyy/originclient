package com.origin.client.client.mixin.menus;

import com.origin.client.client.gui.Gfx;
import com.origin.client.client.render.OriginScreenRenderer;
import net.minecraft.client.multiplayer.GuiConnecting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// "Connecting to the server..." Unlike the pure loading screens this has a live
// Cancel button + status text, so render is NOT taken over: the Origin menu
// backdrop already comes from MenuHooks' DrawScreenEvent.Pre (no world while
// connecting), the Cancel button is Origin-skinned, and this only adds the
// sweeping bar on top at TAIL, after vanilla has drawn its text and button.
@Mixin(GuiConnecting.class)
public abstract class GuiConnectingMixin {

	@Inject(method = "drawScreen", at = @At("TAIL"))
	private void originclient$originBar(int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
		try {
			OriginScreenRenderer.renderConnectingBar(new Gfx());
		} catch (Throwable t) {
			// the vanilla connect screen as it is
		}
	}
}
