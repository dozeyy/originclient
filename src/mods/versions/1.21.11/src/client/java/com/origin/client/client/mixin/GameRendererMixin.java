package com.origin.client.client.mixin;

import com.origin.client.client.OriginClientMod;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// 1.21.2+ : GameRenderer.getFov returns FLOAT (it was double on <=1.21.1). This
// forked copy uses CallbackInfoReturnable<Float>; the shared copy stays the
// <Double> version for 1.20.x / 1.21 / 1.21.1. Using the wrong box type makes the
// mixin cast Float<->Double and throw ClassCastException the first time zoom fires.
//
// Both handlers are fail-soft: a zoom hiccup must never take the frame down.
// They run INSIDE vanilla's render/getFov, outside OriginScreenRenderer's
// try/catch, so each needs its own guard (mandate: degrade to vanilla, never crash).
@Mixin(GameRenderer.class)
public class GameRendererMixin {
	// Eased zoom progress (0 = off, 1 = fully zoomed) + last-frame timestamp.
	// Easing is time-based so Smooth Zoom glides at any frame rate.
	@Unique private static double originclient$zoomAnim = 0;
	@Unique private static long originclient$lastNanos = 0;

	// The ease advances ONCE per frame, here, and getFov below only READS it.
	// It used to advance inside getFov, but getFov runs several times a frame
	// (the world pass, the hand pass, Iris's passes, WaypointHud's invoker), so
	// each caller in the same frame saw a different FOV mid-ease -- the world and
	// anything drawn with a later getFov call (block outline, hitboxes, waypoint
	// markers) disagreed for ~0.5s after every zoom in/out. `render` is matched
	// by name with a CallbackInfo-only handler, so it binds whatever the era's
	// GameRenderer.render(DeltaTracker, boolean) parameters are.
	@Inject(method = "render", at = @At("HEAD"))
	private void originclient$advanceZoom(CallbackInfo ci) {
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
		} catch (Throwable t) {
			// A zoom error must never crash the frame — the ease just holds still.
		}
	}

	@Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
	private void originclient$applyZoom(Camera camera, float partialTick, boolean usePerspective, CallbackInfoReturnable<Float> cir) {
		try {
			// usePerspective == false is the first-person HAND pass (renderItemInHand).
			// Zoom never touches it: the held item stays its normal size, like
			// OptiFine/Lunar zoom.
			if (!usePerspective || originclient$zoomAnim <= 0.001) {
				return;
			}
			double vanilla = cir.getReturnValue();
			double tfov = Mods.num("zoom", "fov") * OriginClientMod.zoomScrollFactor;
			tfov = Math.max(1.0, Math.min(vanilla, tfov));   // zoom only narrows the FOV
			cir.setReturnValue((float) (vanilla + (tfov - vanilla) * originclient$zoomAnim));
		} catch (Throwable t) {
			// A zoom error must never crash the frame — just skip zoom this frame.
		}
	}
}
