package com.origin.client.client.mixin;

import com.origin.client.client.OriginClientMod;
import com.origin.client.client.OriginFreelookState;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// 26.2 port of the camera hooks. The render/input overhaul moved FOV off
// GameRenderer onto Camera (Camera.getFov()/calculateFov(float)) and replaced
// Camera.setup(...) with update()->alignWithEntity(float), so both the Zoom and
// Freelook camera feeds now bind to Camera instead of GameRenderer.
//   - Zoom: calculateFov(float partialTick) is the per-frame FOV compute (the
//     direct successor to the old GameRenderer.getFov), so easing here glides
//     frame-rate-independently. Zoom only ever NARROWS the FOV.
//   - Freelook feed: alignWithEntity(float) is where the camera points itself at
//     the entity's facing via setRotation(yaw,pitch); redirecting that single
//     call orbits the camera on the freelook angles while the player's real body
//     rotation is never touched. require = 1 -> fail LOUDLY at launch if the call
//     site ever moves (defaultRequire is 0 in our config), never a silent no-op.
@Mixin(Camera.class)
public abstract class CameraMixin {
	@Shadow
	protected abstract void setRotation(float yRot, float xRot);

	// Eased zoom progress + last-frame timestamp. calculateFov runs every FRAME,
	// so easing here (time-based, frame-rate independent) makes Smooth Zoom glide
	// instead of the choppy 20Hz tick-side easing.
	@Unique private static double originclient$zoomAnim = 0;
	@Unique private static long originclient$lastNanos = 0;

	@Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
	private void originclient$applyZoom(float partialTick, CallbackInfoReturnable<Float> cir) {
		boolean active = Mods.on("zoom") && OriginClientMod.zoomActive;
		double target = active ? 1.0 : 0.0;

		long now = System.nanoTime();
		double dt = originclient$lastNanos == 0 ? 16.7 : Math.min(64.0, (now - originclient$lastNanos) / 1_000_000.0);
		originclient$lastNanos = now;

		if (Mods.on("zoom") && Mods.bool("zoom", "smoothZoom")) {
			double rate = 1.0 - Math.exp(-dt / 70.0);   // ~70ms time constant, per-frame
			originclient$zoomAnim += (target - originclient$zoomAnim) * rate;
			if (Math.abs(target - originclient$zoomAnim) < 0.0015) {
				originclient$zoomAnim = target;
			}
		} else {
			originclient$zoomAnim = target;
		}

		if (originclient$zoomAnim > 0.001) {
			double vanilla = cir.getReturnValueF();
			double tfov = Mods.num("zoom", "fov") * OriginClientMod.zoomScrollFactor;
			tfov = Math.max(1.0, Math.min(vanilla, tfov));   // zoom only narrows the FOV
			cir.setReturnValue((float) (vanilla + (tfov - vanilla) * originclient$zoomAnim));
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
