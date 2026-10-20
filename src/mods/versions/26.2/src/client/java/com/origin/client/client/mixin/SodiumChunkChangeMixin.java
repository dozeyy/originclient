package com.origin.client.client.mixin;

import com.origin.client.client.render.HalfRateShadows;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Target: Sodium 0.9.2+mc26.2 RenderSectionManager (javap 2026-10-20):
 *   public scheduleRebuild(IIIZ)V -- a block/light change asked for a section rebuild;
 *   private processChunkBuildResults(ArrayList, Viewport, UniformBufferManager)I -- built sections are
 *     uploaded (processChunkBuilds calls it only when results are non-empty).
 * CallbackInfo-only handlers, matched by name, no argument-dependent handler anywhere in this mixin: this
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

	@Inject(method = "processChunkBuildResults", at = @At("HEAD"), remap = false)
	private void originclient$chunksUploaded(CallbackInfo ci) {
		HalfRateShadows.onChunksChanged();
	}
}