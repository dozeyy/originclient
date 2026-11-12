package com.origin.client.client.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import com.origin.client.client.OriginDevAd;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Dev-only ad capture: raw key state (InputConstants.isKeyDown, what Snaplook and Origin's other hold-keys read) comes
 * from the script's held keys only. The window is never focused, so GLFW would report every key up; this also keeps
 * the real keyboard out of the take, like DevAdKeyboardMixin does for key events.
 */
@Mixin(InputConstants.class)
public abstract class DevAdKeysMixin {
	@Inject(method = "isKeyDown", at = @At("HEAD"), cancellable = true, require = 1)
	private static void originad$scriptedKeys(long window, int key, CallbackInfoReturnable<Boolean> cir) {
		if (OriginDevAd.ON) {
			cir.setReturnValue(OriginDevAd.heldKeys.contains(key));
		}
	}
}
