package com.origin.client.client.mixin;

import com.origin.client.client.render.OffMapShadows;
import com.origin.client.client.render.SmartTerrainUpdates;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Target: Sodium 0.2.0+mc1.16.5 me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderManager
 * .update(Lnet/minecraft/class_4184;Lme/jellysquid/mods/sodium/client/util/math/FrustumExtended;IZ)V, HEAD
 * and RETURN (javap 2026-10-20): the chunk walk -- reset, setup(camera), then iterateChunks ->
 * ChunkGraphCuller.computeVisible (initSearch + bfsEnqueue -> isCulledByFrustum). Iris 1.4.5 runs it once
 * for the main view and once for the shadow pass (shadow_map MixinSodiumWorldRenderer forces the shadow
 * walk through SodiumWorldRenderer.updateChunks). Only CallbackInfo is taken, so a build with a different
 * update() argument list still applies.
 * Why: Off-Map Shadows (render/OffMapShadows) arms its section test only inside a shadow walk whose shadow
 * matrices it has this frame, and disarms at the end. SmartTerrainUpdates counts walks per pass (dev
 * builds only) -- the measured proof that Sodium 0.2 already skips the main walk while nothing moves.
 * What breaks if it stops applying: nothing -- Off-Map Shadows never arms (sections keep their Sodium
 * answer), the F3 line reads 0 checks.
 */
@Pseudo
@Mixin(targets = "me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderManager", remap = false)
public abstract class SodiumShadowWalkMixin {
	@Inject(method = "update", at = @At("HEAD"), remap = false)
	private void originclient$walkBegin(CallbackInfo ci) {
		OffMapShadows.walkBegin();
		SmartTerrainUpdates.walkBegin();
	}

	@Inject(method = "update", at = @At("RETURN"), remap = false)
	private void originclient$walkEnd(CallbackInfo ci) {
		OffMapShadows.walkEnd();
		SmartTerrainUpdates.walkEnd();
	}
}
