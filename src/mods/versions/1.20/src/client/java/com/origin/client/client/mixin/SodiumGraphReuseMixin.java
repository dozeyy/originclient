package com.origin.client.client.mixin;

import com.origin.client.client.render.HalfRateShadows;
import com.origin.client.client.render.OffMapShadows;
import com.origin.client.client.render.SmartTerrainUpdates;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Target: Sodium 0.5.13 RenderSectionManager (names and signatures read from the jar 2026-10-20).
 * Why: Smart Terrain Updates (SmartTerrainUpdates has the full proof) and Half-Rate Shadows' chunk
 * invalidation. Every method that changes what a chunk-graph walk would find marks the cache dirty
 * first; destroy() drops everything held for the manager; update() RETURN snapshots a real walk.
 * This mixin holds ONLY CallbackInfo handlers (no target arguments), so a changed signature can
 * never fail it at apply time: these are the must-run hooks. The arg-dependent ones live apart:
 * SodiumUpdateReuseMixin (update HEAD, needs the frame argument) and SodiumWalkEndMixin
 * (createTerrainRenderList RETURN, needs the Viewport).
 * What breaks if it stops applying: nothing -- every graph-change hook only ever makes the feature
 * walk; the feature is version-gated to the exact Sodium + Iris builds this was read from and
 * refuses to reuse until the graph-change hooks have been seen firing (REQUIRED_HOOKS). Half-Rate
 * Shadows likewise keeps no frame until it has seen a chunk-change hook (HalfRateShadows).
 */
@Mixin(value = RenderSectionManager.class, remap = false)
public abstract class SodiumGraphReuseMixin {
	// update(Camera, Viewport, int, boolean)V RETURN: the main pass only (Iris cancels the shadow
	// pass's update at its createTerrainRenderList call, before RETURN).
	@Inject(method = "update", at = @At("RETURN"))
	private void originclient$graphWalked(CallbackInfo ci) {
		SmartTerrainUpdates.afterUpdate((RenderSectionManager) (Object) this);
	}

	// createTerrainRenderList(Camera, Viewport, int, boolean)V HEAD: start of either pass's walk.
	@Inject(method = "createTerrainRenderList", at = @At("HEAD"))
	private void originclient$walkBegin(CallbackInfo ci) {
		SmartTerrainUpdates.walkBegin();
		// Off-Map Shadows arms its per-section test for a shadow-pass walk (render/OffMapShadows).
		OffMapShadows.walkBegin();
	}

	@Inject(method = "onSectionAdded", at = @At("HEAD"))
	private void originclient$sectionAdded(CallbackInfo ci) {
		SmartTerrainUpdates.graphChanged(0);
	}

	@Inject(method = "onSectionRemoved", at = @At("HEAD"))
	private void originclient$sectionRemoved(CallbackInfo ci) {
		SmartTerrainUpdates.graphChanged(1);
	}

	// scheduleRebuild(IIIZ)V: only when a section's pending update really changes (its one
	// setPendingUpdate call, bytecode offset 97, javap 2026-10-20). Vanilla calls setSectionDirty for
	// unbuilt sections thousands of times while chunks light up; those change nothing a walk would
	// find. Sodium 0.5.13 has no scheduleSort (0.6.13's translucency sort queue does not exist yet).
	@Inject(method = "scheduleRebuild", at = @At(value = "INVOKE", target = "Lme/jellysquid/mods/sodium/client/render/chunk/RenderSection;setPendingUpdate(Lme/jellysquid/mods/sodium/client/render/chunk/ChunkUpdateType;)V"))
	private void originclient$rebuild(CallbackInfo ci) {
		SmartTerrainUpdates.graphChanged(2);
		// Half-Rate Shadows: a chunk is about to change, so the next frame redraws the shadow map.
		HalfRateShadows.onChunksChanged();
	}

	// processChunkBuildResults(ArrayList)V -- void on 0.5.13 (0.6.13 returns boolean). uploadChunks
	// calls it only when there are results, then sets needsUpdate.
	@Inject(method = "processChunkBuildResults", at = @At("HEAD"))
	private void originclient$buildResults(CallbackInfo ci) {
		SmartTerrainUpdates.graphChanged(4);
		HalfRateShadows.onChunksChanged();
	}

	@Inject(method = "markGraphDirty", at = @At("HEAD"))
	private void originclient$markDirty(CallbackInfo ci) {
		SmartTerrainUpdates.onMarkGraphDirty();
	}

	// Leaving a world, a render-distance change or a shader reload destroys the manager: drop the
	// snapshot and recorded sections so they don't keep the old ClientLevel alive.
	@Inject(method = "destroy", at = @At("HEAD"))
	private void originclient$destroyed(CallbackInfo ci) {
		SmartTerrainUpdates.onDestroy();
	}
}
