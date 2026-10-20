package com.origin.client.client.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.origin.client.client.render.OffMapShadows;
import com.origin.client.client.render.SmartTerrainUpdates;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Target: Sodium RenderSectionManager.createTerrainRenderList at RETURN, the end of a chunk-graph walk.
 * Its argument list differs between the builds this module runs with (javap 2026-10-20):
 * 0.6.13+mc1.21.4 (Camera, Viewport, FogParameters, int, boolean)V, 0.6.8 and 0.6.1 (+mc1.21.3)
 * (Camera, Viewport, int, boolean)V. So the handler takes only CallbackInfo and picks the Viewport by
 * type (@Local argsOnly: each has exactly one Viewport argument), which fits all three.
 * Why: Smart Terrain Updates closes its walk timer, and Off-Map Shadows disarms its section test and,
 * in dev-shots only (ORIGIN_SHOTS), counts what a shadow-pass walk put in the shadow lists
 * (render/OffMapShadows.afterWalk). Kept apart from SodiumGraphReuseMixin so the chunk-change hooks
 * there never share a class with an argument-capturing handler.
 * What breaks if it stops applying: Off-Map Shadows' section test would stay armed after a shadow walk
 * until the next walk's HEAD disarms it, so a Sodium frustum test in between could be answered against
 * the shadow map; the dev counts and Smart Terrain's walk-time F3 numbers read zero. dev-shots'
 * perf-gate lists it among the Sodium mixins that must apply.
 */
@Mixin(value = RenderSectionManager.class, remap = false)
public abstract class SodiumWalkEndMixin {
	@Inject(method = "createTerrainRenderList", at = @At("RETURN"))
	private void originclient$walkEnd(CallbackInfo ci, @Local(argsOnly = true) Viewport viewport) {
		SmartTerrainUpdates.walkEnd();
		OffMapShadows.afterWalk((RenderSectionManager) (Object) this, viewport);
	}
}
