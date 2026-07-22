package com.origin.client.client.mixin;

import com.origin.client.client.OriginClientMod;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public class GameRendererMixin {
	// Eased zoom progress + last-frame timestamp. getFov runs every FRAME, so
	// easing here (frame-rate independent, time-based) makes Smooth Zoom glide
	// instead of the old tick-side easing that only updated 20x/sec and looked
	// choppy at high FPS.
	@Unique private static double originclient$zoomAnim = 0;
	@Unique private static long originclient$lastNanos = 0;

	@Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
	private void originclient$applyZoom(Camera camera, float partialTick, boolean usePerspective, CallbackInfoReturnable<Double> cir) {
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
			double vanilla = cir.getReturnValue();
			double tfov = Mods.num("zoom", "fov") * OriginClientMod.zoomScrollFactor;
			tfov = Math.max(1.0, Math.min(vanilla, tfov));   // zoom only narrows the FOV
			cir.setReturnValue(vanilla + (tfov - vanilla) * originclient$zoomAnim);
		}
	}
}
