package com.origin.client.client.mixin;

import com.origin.client.client.OriginClientMod;
import com.origin.client.client.OriginKeyBindings;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public class GameRendererMixin {
	@Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
	private void originclient$applyZoom(Camera camera, float partialTick, boolean usePerspective, CallbackInfoReturnable<Double> cir) {
		if (OriginClientMod.FEATURES.zoomEnabled && OriginKeyBindings.zoom.isDown()) {
			cir.setReturnValue(OriginClientMod.FEATURES.zoomFov);
		}
	}
}
