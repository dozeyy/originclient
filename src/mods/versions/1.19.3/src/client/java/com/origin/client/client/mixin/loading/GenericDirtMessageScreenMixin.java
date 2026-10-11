package com.origin.client.client.mixin.loading;

import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.gui.Gfx;
import com.origin.client.client.render.OriginScreenRenderer;
import net.minecraft.client.gui.screens.GenericDirtMessageScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// The "Preparing Resources..." / "Saving world" screen is GenericDirtMessageScreen
// before 1.20.5 (GenericMessageScreen after). 1.21.1 shows the Origin loading
// scene (backdrop + sweeping bar) behind that message; here the scene is painted
// at the HEAD of render(), the dirt it would normally draw is already suppressed
// by ScreenBackgroundMixin while out of a world, and the screen's own text renders
// on top as usual. Non-cancelling and fail-soft: if the scene cannot draw, vanilla
// shows its dirt as before.
@Mixin(GenericDirtMessageScreen.class)
public class GenericDirtMessageScreenMixin {

	@Inject(method = "render", at = @At("HEAD"))
	private void originclient$originBackground(PoseStack poseStack, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		OriginScreenRenderer.renderLoadingScene(new Gfx(poseStack), null);
	}
}
