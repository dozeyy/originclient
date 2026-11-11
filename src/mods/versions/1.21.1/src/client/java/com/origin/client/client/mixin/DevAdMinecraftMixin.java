package com.origin.client.client.mixin;

import com.origin.client.client.OriginDevAd;
import com.origin.client.client.OriginDevAdCapture;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Dev-only ad capture: per-frame camera/input script right before the frame is drawn, the frame grab right before
 * the finished frame is blitted to the window, and the game treats its (deliberately unfocused) window as active so
 * nothing throttles or pauses.
 */
@Mixin(Minecraft.class)
public abstract class DevAdMinecraftMixin {
	@Inject(method = "runTick", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/GameRenderer;render(Lnet/minecraft/client/DeltaTracker;Z)V"))
	private void originad$beforeRender(boolean tick, CallbackInfo ci) {
		if (OriginDevAd.ON) {
			OriginDevAd.preRender((Minecraft) (Object) this);
		}
	}

	@Inject(method = "runTick", at = @At(value = "INVOKE",
			target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;blitToScreen(II)V"))
	private void originad$grabFrame(boolean tick, CallbackInfo ci) {
		if (OriginDevAd.ON) {
			OriginDevAdCapture.onFrame();
		}
	}

	@Inject(method = "isWindowActive", at = @At("HEAD"), cancellable = true)
	private void originad$alwaysActive(CallbackInfoReturnable<Boolean> cir) {
		if (OriginDevAd.ON) {
			cir.setReturnValue(true);
		}
	}
}
