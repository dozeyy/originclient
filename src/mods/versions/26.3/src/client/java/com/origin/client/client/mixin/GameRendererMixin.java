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
	@Unique private static long originclient$lastNanos = 0;

	// The ease advances ONCE per frame, here, and getFov below only READS it.
	// It used to advance inside getFov, but getFov runs several times a frame
	// (the world pass, the hand pass, Iris's passes, WaypointHud's invoker), so
	// each caller in the same frame saw a different FOV mid-ease -- the world and
	// anything drawn with a later getFov call (block outline, hitboxes, waypoint
	// markers) disagreed for ~0.5s after every zoom in/out. `render` is matched
	// by name with a CallbackInfo-only handler, so it binds whatever the era's
	// GameRenderer.render(DeltaTracker, boolean) parameters are.
	// Motion Blur (26.3): GameRenderer.update() rebuilds the frame's requested post
	// effects (clear, END_OF_FRAME, the player's, the spectated entity's -- javap -c,
	// 26.3); vanilla applies that list in order and drops any effect that fails to
	// load (failedPostEffects). Appending Origin's chain here is the whole hook.
	@Inject(method = "update", at = @At("RETURN"))
	private void originclient$motionBlur(CallbackInfo ci) {
		try {
			net.minecraft.resources.Identifier effect = com.origin.client.client.mods.MotionBlur.requested();
			if (effect != null) {
				((GameRenderer) (Object) this).getRequestedPostEffects().add(effect);
			}
		} catch (Throwable t) {
			// no blur this frame
		}
	}

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
				OriginClientMod.zoomAnim += (target - OriginClientMod.zoomAnim) * rate;
				if (Math.abs(target - OriginClientMod.zoomAnim) < 0.0015) {
					OriginClientMod.zoomAnim = target;
				}
			} else {
				OriginClientMod.zoomAnim = target;
			}
		} catch (Throwable t) {
			// A zoom error must never crash the frame — the ease just holds still.
		}
	}

	// 26.2: the FOV moved to Camera (calculateFov = world, calculateHudFov = the
	// hand pass), so the zoom itself is applied in CameraMixin; the hand never zooms.
}
