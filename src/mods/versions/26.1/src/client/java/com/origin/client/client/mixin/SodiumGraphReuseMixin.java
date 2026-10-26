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
 * SODIUM 0.8 ONLY (CompatMixinPlugin (originclient.compat-gates.txt) applies it only on Sodium 0.8.x: 26.1 / 26.1.1; Sodium 0.9 has no
 * createTerrainRenderList / update / scheduleSort).
 * Target: Sodium 0.8.9+mc26.1.1 RenderSectionManager (javap 2026-10-20; the same code as 0.8.12+mc1.21.11,
 * the 1.21.11 donor). No handler here reads a
 * method argument, so a later Sodium that changes an argument list only stops the walk hooks, never the
 * chunk-change hooks Half-Rate Shadows needs.
 * Why: Smart Terrain Updates (SmartTerrainUpdates has the full proof).
 * <ul>
 * <li>createTerrainRenderList HEAD (private, (Camera, Viewport, FogParameters, int, boolean)Z, called by
 *     update() right after lastUpdatedFrame++): may answer false without walking for the MAIN pass when
 *     nothing changed (PER-VERSION 1.21.11: 1.21.1-1.21.4 cancelled update() itself and restored a
 *     snapshot; Sodium 0.8 + Iris 1.10.7 keep the main walk's lists intact, see SmartTerrainUpdates).
 *     A walk that runs starts the walk timer (Off-Map Shadows arms in SodiumWalkEndMixin).</li>
 * <li>update RETURN: prime from the main walk that just ran.</li>
 * <li>Every method that changes what a walk would find marks the cache dirty: onSectionAdded,
 *     onSectionRemoved, scheduleRebuild / scheduleSort (only when their one upgradePendingUpdate call
 *     really changed the section's pending update -- it returns true; vanilla calls setSectionDirty
 *     thousands of times while chunks light up, which changes nothing a walk would find),
 *     processChunkBuildResults (only reached with results), markGraphDirty; destroy() drops everything
 *     held for the manager.</li>
 * </ul>
 * Half-Rate Shadows' chunk hooks live in SodiumChunkChangeMixin (both Sodium generations).
 * What breaks if it stops applying: nothing -- every graph-change hook only ever makes the feature walk;
 * the feature is version-gated to the exact Sodium + Iris builds this was read from and refuses to reuse
 * until the graph-change hooks have been seen firing.
 */
@Mixin(value = RenderSectionManager.class, remap = false)
public abstract class SodiumGraphReuseMixin {
	@Inject(method = "createTerrainRenderList", at = @At("HEAD"), cancellable = true)
	private void originclient$reuseGraph(CallbackInfoReturnable<Boolean> cir) {
		if (SmartTerrainUpdates.beforeWalk((RenderSectionManager) (Object) this)) {
			cir.setReturnValue(false);   // update() stores it: needsGraphUpdate = false, as after a quiet walk
			return;
		}
		SmartTerrainUpdates.walkBegin();
	}

	@Inject(method = "update", at = @At("RETURN"))
	private void originclient$graphWalked(CallbackInfo ci) {
		SmartTerrainUpdates.afterUpdate((RenderSectionManager) (Object) this);
	}

	@Inject(method = "onSectionAdded", at = @At("HEAD"))
	private void originclient$sectionAdded(CallbackInfo ci) {
		SmartTerrainUpdates.graphChanged(0);
	}

	@Inject(method = "onSectionRemoved", at = @At("HEAD"))
	private void originclient$sectionRemoved(CallbackInfo ci) {
		SmartTerrainUpdates.graphChanged(1);
	}

	@ModifyExpressionValue(method = "scheduleRebuild", at = @At(value = "INVOKE",
			target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSectionManager;upgradePendingUpdate(Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;I)Z"))
	private boolean originclient$rebuild(boolean changed) {
		if (changed) {
			SmartTerrainUpdates.graphChanged(2);
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
	private void originclient$buildResults(CallbackInfoReturnable<?> cir) {
		SmartTerrainUpdates.graphChanged(4);
	}

	@Inject(method = "markGraphDirty", at = @At("HEAD"))
	private void originclient$markDirty(CallbackInfo ci) {
		SmartTerrainUpdates.onMarkGraphDirty();
	}

	// Leaving a world, a render-distance change or a shader reload destroys the manager: drop the
	// recorded sections and held collector so they don't keep the old ClientLevel alive.
	@Inject(method = "destroy", at = @At("HEAD"))
	private void originclient$destroyed(CallbackInfo ci) {
		SmartTerrainUpdates.onDestroy();
	}
}
