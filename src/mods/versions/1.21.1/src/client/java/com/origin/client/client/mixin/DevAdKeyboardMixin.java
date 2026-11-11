package com.origin.client.client.mixin;

import com.origin.client.client.OriginDevAd;
import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Dev-only ad capture: the real keyboard is cut off; only OriginDevAd's scripted keys reach the game. */
@Mixin(value = KeyboardHandler.class, priority = 100)
public abstract class DevAdKeyboardMixin {
	@Inject(method = {"keyPress", "charTyped"}, at = @At("HEAD"), cancellable = true)
	private void originad$onlyScripted(CallbackInfo ci) {
		if (OriginDevAd.ON && !OriginDevAd.synthetic) {
			ci.cancel();
		}
	}
}
