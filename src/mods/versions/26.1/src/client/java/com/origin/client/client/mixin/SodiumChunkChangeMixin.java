package com.origin.client.client.mixin;

import com.origin.client.client.render.HalfRateShadows;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Target: RenderSectionManager in BOTH Sodium builds this jar meets (javap 2026-10-20): 0.9.2+mc26.1.2
 *   public scheduleRebuild(IIIZ)V -- a block/light change asked for a section rebuild;
 *   private processChunkBuildResults(ArrayList, Viewport, UniformBufferManager)I -- built sections are
 *     uploaded (processChunkBuilds calls it only when results are non-empty).
 * and 0.8.9+mc26.1.1 (26.1 / 26.1.1): the same scheduleRebuild(IIIZ)V, and
 *   private processChunkBuildResults(ArrayList)Z -- a different argument list AND return type, which is why
 *     the handler takes a wildcard CallbackInfoReturnable and no arguments: one descriptor fits both.
 * Callback-object-only handlers (no captured arguments), matched by name, no argument-dependent handler anywhere in this mixin: this
 * is the hook Half-Rate Shadows MUST have to stay correct, so nothing else may make it fail to apply.
 * @Pseudo + string target: without Sodium the mixin is skipped.
 * Why: Half-Rate Shadows (render/HalfRateShadows.onChunksChanged). A kept shadow map must never hide
 * a block edit: either event forces the next frame to draw the shadow map.
 * What breaks if it stops applying: after an edit the shadow map could lag one frame (one kept frame
 * at most, since a kept frame is always followed by a real draw).
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager", remap = false)
public abstract class SodiumChunkChangeMixin {
	@Inject(method = "scheduleRebuild", at = @At("HEAD"), remap = false)
	private void originclient$rebuildScheduled(CallbackInfo ci) {
		HalfRateShadows.onChunksChanged();
	}

	// processChunkBuildResults returns int, so the handler takes a CallbackInfoReturnable (a bare
	// CallbackInfo failed apply with "Invalid descriptor", dev-shots 2026-10-20). Still no arguments.
	// Sodium calls it only when there are results, so the callback object is not a per-frame cost.
	@Inject(method = "processChunkBuildResults", at = @At("HEAD"), remap = false)
	private void originclient$chunksUploaded(CallbackInfoReturnable<?> cir) {
		HalfRateShadows.onChunksChanged();
	}
}