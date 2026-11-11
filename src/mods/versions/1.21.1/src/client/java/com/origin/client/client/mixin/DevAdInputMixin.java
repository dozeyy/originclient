package com.origin.client.client.mixin;

import com.origin.client.client.OriginDevAd;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Dev-only ad capture (ORIGIN_SHOTS_SET=ad, never set for players): the real mouse and keyboard are cut off from the
 * game, so only the harness's scripted events (OriginDevAd.synthetic) reach it, and the game never grabs or moves
 * the OS pointer. Priority 100 so the cut-off runs before Origin's own HEAD observers (KeyTaps, MouseTaps).
 */
@Mixin(value = {MouseHandler.class}, priority = 100)
public abstract class DevAdInputMixin {
	@Inject(method = {"onPress", "onMove", "onScroll"}, at = @At("HEAD"), cancellable = true)
	private void originad$onlyScripted(CallbackInfo ci) {
		if (OriginDevAd.ON && !OriginDevAd.synthetic) {
			ci.cancel();
		}
	}

	@Inject(method = {"grabMouse", "releaseMouse"}, at = @At("HEAD"), cancellable = true)
	private void originad$neverGrab(CallbackInfo ci) {
		if (OriginDevAd.ON) {
			ci.cancel();
		}
	}
}
