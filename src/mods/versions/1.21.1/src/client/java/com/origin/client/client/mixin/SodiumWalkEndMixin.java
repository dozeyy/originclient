package com.origin.client.client.mixin;

import com.origin.client.client.render.OffMapShadows;
import com.origin.client.client.render.SmartTerrainUpdates;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Target: Sodium 0.8.13+mc1.21.1 RenderSectionManager (javap 2026-10-25):
 * <ul>
 * <li>createTerrainRenderList RETURN (its one ireturn, offset 164): the end of a chunk-graph walk that
 *     ran (a reused main walk is answered at HEAD and never reaches it). Smart Terrain Updates closes its
 *     walk timer; Off-Map Shadows disarms its section test.</li>
 * <li>finalizeRenderLists(Viewport) RETURN: dev-shots only (ORIGIN_SHOTS) -- Off-Map Shadows counts what a
 *     shadow-pass walk put in the shadow render lists. PER-VERSION (1.21.1 on Sodium 0.8): Sodium 0.8 builds the
 *     render lists here, after the walk (SectionCollector.createRenderLists), not inside it.</li>
 * </ul>
 * Kept apart from SodiumGraphReuseMixin so the chunk-change hooks there never share a class with an
 * argument-capturing handler.
 * What breaks if it stops applying: Off-Map Shadows' section test would stay armed after a shadow walk
 * until the next walk's HEAD disarms it, so a Sodium frustum test in between could be answered against
 * the shadow map; the dev counts and Smart Terrain's walk-time F3 numbers read zero.
 */
@Mixin(value = RenderSectionManager.class, remap = false)
public abstract class SodiumWalkEndMixin {
	@Inject(method = "createTerrainRenderList", at = @At("RETURN"))
	private void originclient$walkEnd(CallbackInfoReturnable<Boolean> cir) {
		SmartTerrainUpdates.walkEnd();
		OffMapShadows.walkEnd();
	}

	@Inject(method = "finalizeRenderLists", at = @At("RETURN"))
	private void originclient$listsBuilt(Viewport viewport, CallbackInfo ci) {
		OffMapShadows.afterWalk((RenderSectionManager) (Object) this, viewport);
	}
}
