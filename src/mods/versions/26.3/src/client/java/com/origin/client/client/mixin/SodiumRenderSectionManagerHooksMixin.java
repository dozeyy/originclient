package com.origin.client.client.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.origin.client.client.render.OffMapShadows;
import com.origin.client.client.render.ShadowTerrainStats;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Target: Sodium 0.9.2+mc26.3 RenderSectionManager (javap 2026-10-20):
 *   private renderOutOfGraph(Viewport, FogParameters)V -- the synchronous walk: FallbackVisibleChunkCollector,
 *     RemovableMultiForest.traverse(visitor, viewport, distance), renderLists = createRenderLists(viewport).
 *     finalizeRenderLists calls it for Iris's shadow pass (update immediately) and for main-pass sync frames.
 *   private readRenderListFromTree(Viewport, FogParameters)V -- main-pass list rebuild from a cull tree.
 *   private scheduleAsyncWork(Viewport, FogParameters, boolean)V -- an async cull job.
 * Handlers are CallbackInfo-only (matched by name); only renderOutOfGraph reads its Viewport, through
 * @Local(argsOnly = true). @Pseudo + string target: without Sodium the mixin is skipped.
 * Why: (1) Off-Map Shadows (render/OffMapShadows) arms its box test with the shadow walk's viewport at
 * HEAD and disarms at RETURN (dev-shots also counts the walk's sections there). (2) ShadowTerrainStats
 * counts main-pass rebuilds: the proof that Smart Terrain Updates has nothing to save on 26.3.
 * What breaks if it stops applying: Off-Map Shadows never arms (shadows draw as before), the F3 terrain
 * line reads 0. Half-Rate Shadows' must-run chunk invalidation is NOT here (SodiumChunkChangeMixin).
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager", remap = false)
public abstract class SodiumRenderSectionManagerHooksMixin {
	@Inject(method = "renderOutOfGraph", at = @At("HEAD"), remap = false)
	private void originclient$walkBegin(CallbackInfo ci, @Local(argsOnly = true) Viewport viewport) {
		ShadowTerrainStats.outOfGraphBegin();
		OffMapShadows.walkBegin(viewport);
	}

	@Inject(method = "renderOutOfGraph", at = @At("RETURN"), remap = false)
	private void originclient$walkEnd(CallbackInfo ci, @Local(argsOnly = true) Viewport viewport) {
		OffMapShadows.afterWalk((RenderSectionManager) (Object) this, viewport);
		ShadowTerrainStats.outOfGraphEnd();
	}

	@Inject(method = "readRenderListFromTree", at = @At("HEAD"), remap = false)
	private void originclient$readTree(CallbackInfo ci) {
		ShadowTerrainStats.readTree();
	}

	@Inject(method = "scheduleAsyncWork", at = @At("HEAD"), remap = false)
	private void originclient$asyncWork(CallbackInfo ci) {
		ShadowTerrainStats.asyncWork();
	}
}