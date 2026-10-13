package com.origin.client.client.mixin.world;

import com.origin.client.client.hooks.WorldHooks;
import net.minecraft.client.renderer.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Zoom: EntityRenderer.getFOVModifier(partialTicks, useFOVSetting) is the FOV of
// every camera pass; useFOVSetting is false for the first-person HAND pass, which
// zoom never touches. Forge's EntityViewRenderEvent.FOVModifier can't tell the
// two apart, hence a mixin. EntityRenderer is OptiFine-replaced: RETURN only
// (OptiFine's own zoom is already folded into the value; WorldHooks parks
// OptiFine's zoom key while it clashes with Origin's). The ease itself advances
// once per frame in WorldHooks; this only reads it.
@Mixin(EntityRenderer.class)
public abstract class ZoomFovMixin {
	@Inject(method = "getFOVModifier", at = @At("RETURN"), cancellable = true)
	private void originclient$applyZoom(float partialTicks, boolean useFOVSetting, CallbackInfoReturnable<Float> cir) {
		try {
			float vanilla = cir.getReturnValueF();
			float fov = WorldHooks.zoomFov(vanilla, useFOVSetting);
			if (fov != vanilla) {
				cir.setReturnValue(fov);
			}
		} catch (Throwable ignored) {
			// vanilla FOV this pass
		}
	}
}
