package com.origin.client.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.origin.client.client.render.SmartTerrainUpdates;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Target: Sodium 0.7.3+mc1.21.8 RenderSectionManager (javap 2026-10-20): update(Camera, Viewport,
 * FogParameters, boolean), createTerrainRenderList, onSectionAdded(III), onSectionRemoved(III),
 * scheduleRebuild(IIIZ) / scheduleSort(JZ) (each changes a section's pending update only through
 * upgradePendingUpdate(RenderSection, int)Z, which returns true when it really changed it),
 * processChunkBuildResults(ArrayList)Z (uploadChunks calls it only with a non-empty result list),
 * markGraphDirty(), destroy(). No handler here takes a target argument: these are the must-run
 * chunk-change hooks Half-Rate Shadows needs, so nothing argument-dependent shares this class.
 * Why: Smart Terrain Updates (render/SmartTerrainUpdates has the full proof). update() HEAD decides
 * whether this update's chunk-graph search is replayed (never cancelled: on 0.7 the rest of update()
 * must run); every method that changes what a walk would find marks the recording dirty first;
 * destroy() drops everything held for the manager. Half-Rate Shadows redraws the shadow map when a
 * rebuild is scheduled or a build result arrives.
 * What breaks if it stops applying: nothing visible -- every graph-change hook only ever makes the
 * feature walk; the feature is version-gated to the exact Sodium + Iris builds this was read from and
 * refuses to replay until the graph-change hooks have been seen firing. Without the rebuild /
 * build-result hooks Half-Rate Shadows could keep a shadow map one frame past a block change.
 */
@Mixin(value = RenderSectionManager.class, remap = false)
public abstract class SodiumGraphReuseMixin {
	@Inject(method = "update", at = @At("HEAD"))
	private void originclient$decideReplay(CallbackInfo ci) {
		SmartTerrainUpdates.beforeUpdate((RenderSectionManager) (Object) this);
	}

	@Inject(method = "update", at = @At("RETURN"))
	private void originclient$graphWalked(CallbackInfo ci) {
		SmartTerrainUpdates.afterUpdate((RenderSectionManager) (Object) this);
	}

	@Inject(method = "createTerrainRenderList", at = @At("HEAD"))
	private void originclient$walkBegin(CallbackInfoReturnable<Boolean> cir) {
		SmartTerrainUpdates.walkBegin();
		// Off-Map Shadows arms its box tests for a shadow-pass walk (render/OffMapShadows).
		com.origin.client.client.render.OffMapShadows.walkBegin();
	}

	// The walk's RETURN hook (needs the Viewport argument) lives in SodiumWalkEndMixin and the findVisible
	// replay in SodiumWalkReplayMixin, so no handler in this class depends on a target's argument list.

	@Inject(method = "onSectionAdded", at = @At("HEAD"))
	private void originclient$sectionAdded(CallbackInfo ci) {
		SmartTerrainUpdates.graphChanged(0);
	}

	@Inject(method = "onSectionRemoved", at = @At("HEAD"))
	private void originclient$sectionRemoved(CallbackInfo ci) {
		SmartTerrainUpdates.graphChanged(1);
	}

	// scheduleRebuild / scheduleSort: only when a section's pending update really changes (their one
	// upgradePendingUpdate call each returns true). Vanilla calls setSectionDirty for sections thousands
	// of times while chunks light up; most change nothing a walk would find.
	@ModifyExpressionValue(method = "scheduleRebuild", at = @At(value = "INVOKE",
			target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSectionManager;upgradePendingUpdate(Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;I)Z"))
	private boolean originclient$rebuild(boolean changed) {
		if (changed) {
			SmartTerrainUpdates.graphChanged(2);
			// Half-Rate Shadows: a chunk is about to change, so the next frame redraws the shadow map.
			com.origin.client.client.render.HalfRateShadows.onChunksChanged();
		}
		return changed;
	}

	@ModifyExpressionValue(method = "scheduleSort", at = @At(value = "INVOKE",
			target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSectionManager;upgradePendingUpdate(Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;I)Z"))
	private boolean originclient$sort(boolean changed) {
		if (changed) {
			SmartTerrainUpdates.graphChanged(3);
		}
		return changed;
	}

	@Inject(method = "processChunkBuildResults", at = @At("HEAD"))
	private void originclient$buildResults(CallbackInfoReturnable<Boolean> cir) {
		SmartTerrainUpdates.graphChanged(4);
		com.origin.client.client.render.HalfRateShadows.onChunksChanged();
	}

	@Inject(method = "markGraphDirty", at = @At("HEAD"))
	private void originclient$markDirty(CallbackInfo ci) {
		SmartTerrainUpdates.onMarkGraphDirty();
	}

	// Leaving a world, a render-distance change or a shader reload destroys the manager: drop the
	// recorded sections so they don't keep the old ClientLevel alive.
	@Inject(method = "destroy", at = @At("HEAD"))
	private void originclient$destroyed(CallbackInfo ci) {
		SmartTerrainUpdates.onDestroy();
	}
}
