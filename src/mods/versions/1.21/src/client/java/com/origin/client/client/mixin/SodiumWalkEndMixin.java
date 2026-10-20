package com.origin.client.client.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.origin.client.client.render.OffMapShadows;
import com.origin.client.client.render.SmartTerrainUpdates;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;
import me.jellysquid.mods.sodium.client.render.viewport.Viewport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Target: Sodium 0.5.11 RenderSectionManager.createTerrainRenderList(Camera, Viewport, int,
 * boolean)V at RETURN (private; javap 2026-10-20), the Viewport taken with @Local(argsOnly).
 * Why: Smart Terrain Updates closes its walk timer and stamp recording; Off-Map Shadows disarms its
 * section test and, in dev-shots only (ORIGIN_SHOTS), counts what a shadow-pass walk put in the
 * shadow render lists (render/OffMapShadows.afterWalk). Kept apart from SodiumGraphReuseMixin so an
 * argument change can only lose this handler, never the chunk-change hooks.
 * What breaks if it stops applying: nothing visible. Smart Terrain's stamp recording and Off-Map's
 * section test stay armed until the next walk begins, and both walkBegin hooks disarm them first
 * (no section is stamped or frustum-tested between walks: Viewport.isBoxVisible's only caller is
 * OcclusionCuller); the walk timer and the dev counts read zero.
 */
@Mixin(value = RenderSectionManager.class, remap = false)
public abstract class SodiumWalkEndMixin {
	@Inject(method = "createTerrainRenderList", at = @At("RETURN"))
	private void originclient$walkEnd(CallbackInfo ci, @Local(argsOnly = true) Viewport viewport) {
		SmartTerrainUpdates.walkEnd();
		OffMapShadows.afterWalk((RenderSectionManager) (Object) this, viewport);
	}
}
