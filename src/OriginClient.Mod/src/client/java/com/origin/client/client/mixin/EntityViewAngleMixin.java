package com.origin.client.client.mixin;

import com.origin.client.client.OriginFreelookState;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Feeds the accumulated freelook camera angle into the rendered view for the
// local player only — every other entity (and the local player when
// freelook isn't active) keeps vanilla's real interpolated rotation.
@Mixin(Entity.class)
public abstract class EntityViewAngleMixin {
	@Inject(method = "getViewYRot", at = @At("RETURN"), cancellable = true)
	private void originclient$overrideViewYaw(float partialTicks, CallbackInfoReturnable<Float> cir) {
		if (OriginFreelookState.active && (Object) this == Minecraft.getInstance().player) {
			cir.setReturnValue(OriginFreelookState.cameraYaw);
		}
	}

	@Inject(method = "getViewXRot", at = @At("RETURN"), cancellable = true)
	private void originclient$overrideViewPitch(float partialTicks, CallbackInfoReturnable<Float> cir) {
		if (OriginFreelookState.active && (Object) this == Minecraft.getInstance().player) {
			cir.setReturnValue(OriginFreelookState.cameraPitch);
		}
	}
}
