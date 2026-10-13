package com.origin.client.client.mixin.world;

import com.origin.client.client.hooks.WorldHooks;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Freelook input capture. 1.8.9's EntityRenderer.updateCameraAndRender turns the
// player with Entity.setAngles(yawDelta, pitchDelta) -- its only caller, with
// vanilla's sensitivity curve and smooth-camera filter already applied. While
// freelook is held that turn goes to the camera accumulator instead
// (WorldHooks.freelookTurn) and is cancelled, so the player's real rotation is
// never touched and releasing the key snaps the view back. Entity is not an
// OptiFine-replaced class; HEAD anyway.
@Mixin(Entity.class)
public abstract class FreelookTurnMixin {
	@Inject(method = "setAngles", at = @At("HEAD"), cancellable = true)
	private void originclient$freelookTurn(float yaw, float pitch, CallbackInfo ci) {
		try {
			if (WorldHooks.freelookTurn((Entity) (Object) this, yaw, pitch)) {
				ci.cancel();
			}
		} catch (Throwable ignored) {
			// vanilla turn
		}
	}
}
