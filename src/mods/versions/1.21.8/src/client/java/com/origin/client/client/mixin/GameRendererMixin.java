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

// 1.21.2+ : GameRenderer.getFov returns FLOAT (it was double on <=1.21.1). This
// forked copy uses CallbackInfoReturnable<Float>; the shared copy stays the
// <Double> version for 1.20.x / 1.21 / 1.21.1. Using the wrong box type makes the
// mixin cast Float<->Double and throw ClassCastException the first time zoom fires.
//
// The whole handler is fail-soft: a zoom hiccup must never take the frame down.
// This @Inject runs INSIDE vanilla's getFov, outside OriginScreenRenderer's
// try/catch, so it needs its own guard (mandate: degrade to vanilla, never crash).
@Mixin(GameRenderer.class)
public class GameRendererMixin {
	// Eased zoom progress + last-frame timestamp. getFov runs every FRAME, so
	// easing here (frame-rate independent, time-based) makes Smooth Zoom glide.
	@Unique private static double originclient$zoomAnim = 0;
	@Unique private static long originclient$lastNanos = 0;

	@Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
	private void originclient$applyZoom(Camera camera, float partialTick, boolean usePerspective, CallbackInfoReturnable<Float> cir) {
		try {
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
				cir.setReturnValue((float) (vanilla + (tfov - vanilla) * originclient$zoomAnim));
			}
		} catch (Throwable t) {
			// A zoom error must never crash the frame — just skip zoom this frame.
		}
	}
}
