package com.origin.client.client.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.origin.client.client.render.OffMapShadows;
import com.origin.client.client.render.ShadowTerrainStats;
import com.origin.client.client.render.SmartTerrainUpdates;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * SODIUM 0.8 ONLY (CompatMixinPlugin (originclient.compat-gates.txt) applies it only on Sodium 0.8.x: 26.1 / 26.1.1).
 * Target: Sodium 0.8.9+mc26.1.1 RenderSectionManager (javap 2026-10-20, same code as 0.8.12+mc1.21.11):
 * <ul>
 * <li>createTerrainRenderList(Camera, Viewport, FogParameters, int, boolean)Z HEAD / RETURN: one chunk-graph
 *     walk. Off-Map Shadows arms its box test with THIS walk's viewport (it arms only in Iris's shadow
 *     pass) and disarms at the end; Smart Terrain Updates closes its walk timer; ShadowTerrainStats times
 *     shadow walks (the dev-shots shadow-walk counts both generations share). The Viewport comes in
 *     through @Local(argsOnly = true), no declared argument list. A main walk Smart Terrain reuses is
 *     answered at HEAD by SodiumGraphReuseMixin and never reaches RETURN (it never armed either).</li>
 * <li>finalizeRenderLists(Viewport) RETURN: dev-shots only (ORIGIN_SHOTS) -- Off-Map Shadows counts what a
 *     shadow walk put in the shadow render lists. Sodium 0.8 builds the lists here, after the walk.</li>
 * </ul>
 * PER-VERSION (26.1): Sodium 0.9 (26.1.2) has neither method; its walk is renderOutOfGraph
 * (SodiumRenderSectionManagerHooksMixin). Kept apart from SodiumChunkChangeMixin so the chunk-change hooks
 * Half-Rate Shadows needs never share a class with an argument-capturing handler.
 * What breaks if it stops applying: Off-Map Shadows never arms (shadows draw as before) and the dev counts
 * and Smart Terrain's walk-time F3 numbers read zero.
 */
@Mixin(value = RenderSectionManager.class, remap = false)
public abstract class SodiumWalkEndMixin {
	@Inject(method = "createTerrainRenderList", at = @At("HEAD"))
	private void originclient$walkBegin(CallbackInfoReturnable<Boolean> cir, @Local(argsOnly = true) Viewport viewport) {
		ShadowTerrainStats.shadowWalkBegin();
		OffMapShadows.walkBegin(viewport);
	}

	@Inject(method = "createTerrainRenderList", at = @At("RETURN"))
	private void originclient$walkEnd(CallbackInfoReturnable<Boolean> cir) {
		SmartTerrainUpdates.walkEnd();
		OffMapShadows.walkEnd();
		ShadowTerrainStats.outOfGraphEnd();
	}

	@Inject(method = "finalizeRenderLists", at = @At("RETURN"))
	private void originclient$listsBuilt(CallbackInfo ci, @Local(argsOnly = true) Viewport viewport) {
		OffMapShadows.afterWalk((RenderSectionManager) (Object) this, viewport);
	}
}
