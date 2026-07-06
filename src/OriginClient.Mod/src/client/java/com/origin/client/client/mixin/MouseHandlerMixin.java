package com.origin.client.client.mixin;

import com.origin.client.client.OriginFreelookState;
import net.minecraft.client.MouseHandler;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// While freelook is held, intercepts the two rotation writes turnPlayer()
// normally applies to the player entity and redirects them into a
// camera-only accumulator instead — the entity's real yaw/pitch stay frozen
// (used for movement/hitbox), only the rendered view rotates.
//
// Known limitation: vanilla clamps pitch relative to the entity's frozen
// angle before the redirected call arrives here, so extreme freelook pitch
// past +-90 from the angle you engaged at may not accumulate further.
@Mixin(MouseHandler.class)
public class MouseHandlerMixin {
	@Redirect(method = "turnPlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;setYRot(F)V"))
	private void originclient$redirectYaw(Entity entity, float newYaw) {
		if (OriginFreelookState.active) {
			OriginFreelookState.cameraYaw += newYaw - entity.getYRot();
		} else {
			entity.setYRot(newYaw);
		}
	}

	@Redirect(method = "turnPlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;setXRot(F)V"))
	private void originclient$redirectPitch(Entity entity, float newPitch) {
		if (OriginFreelookState.active) {
			OriginFreelookState.cameraPitch += newPitch - entity.getXRot();
		} else {
			entity.setXRot(newPitch);
		}
	}
}
