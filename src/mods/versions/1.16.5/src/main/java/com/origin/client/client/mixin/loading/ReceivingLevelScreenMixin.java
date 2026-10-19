package com.origin.client.client.mixin.loading;

import com.origin.client.client.OriginStyle;
import com.origin.client.client.render.OriginScreenRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.gui.Gfx;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// "Loading terrain..." when joining a server or changing dimension. HEAD-cancel
// takeover -> the Origin loading scene. Display-only render; safe to replace.
@Mixin(ReceivingLevelScreen.class)
public class ReceivingLevelScreenMixin {

	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void originclient$originLoadingScene(PoseStack poseStack, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		// Only cancel vanilla when the Origin scene actually drew (fail-soft).
		if (OriginStyle.vanillaMenusOn(this)) {
			return;   // vanilla mode: stock screen
		}
		if (OriginScreenRenderer.renderLoadingScene(new Gfx(poseStack), ((Screen) (Object) this).getTitle())) {
			ci.cancel();
		}
	}
}
