package com.origin.client.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.origin.client.client.OriginFreelookState;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// Freelook's camera feed. The old approach injected Entity.getViewYRot — but
// LivingEntity OVERRIDES getViewYRot (bytecode-confirmed), so for players the
// Entity method body (and the injection in it) never ran: third person
// engaged, yaw never moved. Wrapping the setRotation call inside
// Camera.setup targets the exact invocation the camera makes (owner = Camera
// itself, confirmed via javap), before the third-person zoom-out positions
// the camera — so the freelook angles orbit exactly like F5 does.
// @WrapOperation, not @Redirect: other mods hook this same call (Sable
// redirects it for riding physics sub-levels, Do a Barrel Roll wraps it).
// Two @Redirects on one call cannot coexist — the second finds 0 targets and,
// with require = 1, crashes at launch (Sable 2.0.6 did exactly this). Wraps
// chain with each other and with a redirect, and original.call keeps theirs.
// require = 1: if this ever stops matching, fail LOUDLY at launch instead of
// silently shipping a broken freelook (defaultRequire is 0 in our config).
@Mixin(Camera.class)
public abstract class CameraMixin {
	@WrapOperation(method = "setup",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setRotation(FF)V"),
			require = 1)
	private void originclient$freelookRotation(Camera instance, float yRot, float xRot, Operation<Void> original) {
		if (OriginFreelookState.active) {
			original.call(instance, OriginFreelookState.cameraYaw, OriginFreelookState.cameraPitch);
		} else {
			original.call(instance, yRot, xRot);
		}
	}
}
