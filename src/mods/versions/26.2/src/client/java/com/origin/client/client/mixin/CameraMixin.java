package com.origin.client.client.mixin;

import com.origin.client.client.OriginFreelookState;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Freelook's camera feed. The old approach injected Entity.getViewYRot — but
// LivingEntity OVERRIDES getViewYRot (bytecode-confirmed), so for players the
// Entity method body (and the injection in it) never ran: third person
// engaged, yaw never moved. Redirecting the setRotation call inside
// Camera.setup targets the exact invocation the camera makes (owner = Camera
// itself, confirmed via javap), before the third-person zoom-out positions
// the camera — so the freelook angles orbit exactly like F5 does.
// require = 1: if this ever stops matching, fail LOUDLY at launch instead of
// silently shipping a broken freelook (defaultRequire is 0 in our config).
@Mixin(Camera.class)
public abstract class CameraMixin {
	@Shadow
	protected abstract void setRotation(float yRot, float xRot);

	// Zoom: the WORLD fov (calculateFov; the first-person hand uses calculateHudFov
	// and stays its normal size, like OptiFine/Lunar zoom). The ease advances once
	// per frame in GameRendererMixin; this only reads it.
	@Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
	private void originclient$applyZoom(float partialTick, CallbackInfoReturnable<Float> cir) {
		try {
			double anim = com.origin.client.client.OriginClientMod.zoomAnim;
			if (anim <= 0.001) {
				return;
			}
			double vanilla = cir.getReturnValueF();
			double tfov = com.origin.client.client.mods.Mods.num("zoom", "fov") * com.origin.client.client.OriginClientMod.zoomScrollFactor;
			tfov = Math.max(1.0, Math.min(vanilla, tfov));   // zoom only narrows the FOV
			cir.setReturnValue((float) (vanilla + (tfov - vanilla) * anim));
		} catch (Throwable t) {
			// A zoom error must never crash the frame — just skip zoom this frame.
		}
	}

	@Redirect(method = "alignWithEntity",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setRotation(FF)V"),
			require = 1)
	private void originclient$freelookRotation(Camera instance, float yRot, float xRot) {
		if (OriginFreelookState.active) {
			this.setRotation(OriginFreelookState.cameraYaw, OriginFreelookState.cameraPitch);
		} else {
			this.setRotation(yRot, xRot);
		}
	}
}
