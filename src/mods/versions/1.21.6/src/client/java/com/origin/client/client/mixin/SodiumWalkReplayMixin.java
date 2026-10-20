package com.origin.client.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.origin.client.client.render.SmartTerrainUpdates;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.RenderSectionVisitor;
import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.OcclusionCuller;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Target: Sodium 0.7.3+mc1.21.8 RenderSectionManager.createTerrainRenderList, its one call
 * OcclusionCuller.findVisible(RenderSectionVisitor, Viewport, float, boolean, int)V (javap 2026-10-20,
 * offset 132: the in-graph path; the out-of-graph path uses the section tree instead). The handler takes
 * only the wrapped call's own arguments, never createTerrainRenderList's. No Iris 1.9.6 mixin touches
 * this call or OcclusionCuller (grep of the jar); Iris's @WrapMethod around createTerrainRenderList
 * wraps the whole method and still runs first.
 * Why: Smart Terrain Updates replays the last main walk's search result into this frame's collector when
 * nothing changed, records a real walk otherwise (render/SmartTerrainUpdates has the proof). Kept apart
 * from SodiumGraphReuseMixin so the must-run chunk-change hooks there never share a class with it.
 * What breaks if it stops applying: the feature never replays (findVisible must be seen before any
 * replay); terrain walks as normal. dev-shots' perf-gate lists it among the Sodium mixins that must apply.
 */
@Mixin(value = RenderSectionManager.class, remap = false)
public abstract class SodiumWalkReplayMixin {
	@WrapOperation(method = "createTerrainRenderList", at = @At(value = "INVOKE",
			target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/occlusion/OcclusionCuller;findVisible(Lnet/caffeinemc/mods/sodium/client/render/chunk/lists/RenderSectionVisitor;Lnet/caffeinemc/mods/sodium/client/render/viewport/Viewport;FZI)V"))
	private void originclient$replayWalk(OcclusionCuller culler, RenderSectionVisitor visitor, Viewport viewport,
			float searchDistance, boolean useOcclusionCulling, int frame, Operation<Void> original) {
		SmartTerrainUpdates.findVisible((RenderSectionManager) (Object) this, culler, visitor, viewport,
				searchDistance, useOcclusionCulling, frame, original);
	}
}
