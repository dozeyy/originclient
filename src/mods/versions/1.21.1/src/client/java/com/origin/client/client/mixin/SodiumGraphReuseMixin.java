package com.origin.client.client.mixin;

import com.origin.client.client.render.SmartTerrainUpdates;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Target: Sodium 0.6.13 RenderSectionManager (names and signatures read from the jar 2026-10-19).
 * Why: Smart Terrain Updates (SmartTerrainUpdates has the full proof). update() is the chunk-graph
 * walk; with a shader pack Iris forces it every frame. HEAD may cancel the MAIN pass's walk when
 * nothing changed (after restoring the last walk's lists); every method that changes what a walk
 * would find marks the cache dirty first. isSectionVisible answers "visible" between a reused
 * frame and that frame's shadow walk, so entity culling can only draw more, never less.
 * What breaks if it stops applying: nothing -- every graph-change hook only ever makes the feature
 * walk; the feature is version-gated to the exact Sodium + Iris builds this was read from and
 * refuses to reuse until the graph-change hooks have been seen firing.
 */
@Mixin(value = RenderSectionManager.class, remap = false)
public abstract class SodiumGraphReuseMixin {
	@Inject(method = "update", at = @At("HEAD"), cancellable = true)
	private void originclient$reuseGraph(CallbackInfo ci) {
		if (SmartTerrainUpdates.beforeUpdate((RenderSectionManager) (Object) this)) {
			ci.cancel();
		}
	}

	@Inject(method = "update", at = @At("RETURN"))
	private void originclient$graphWalked(CallbackInfo ci) {
		SmartTerrainUpdates.afterUpdate((RenderSectionManager) (Object) this);
	}

	@Inject(method = "createTerrainRenderList", at = @At("HEAD"))
	private void originclient$walkBegin(CallbackInfo ci) {
		SmartTerrainUpdates.walkBegin();
	}

	@Inject(method = "createTerrainRenderList", at = @At("RETURN"))
	private void originclient$walkEnd(CallbackInfo ci) {
		SmartTerrainUpdates.walkEnd();
	}

	@Inject(method = "onSectionAdded", at = @At("HEAD"))
	private void originclient$sectionAdded(CallbackInfo ci) {
		SmartTerrainUpdates.graphChanged(0);
	}

	@Inject(method = "onSectionRemoved", at = @At("HEAD"))
	private void originclient$sectionRemoved(CallbackInfo ci) {
		SmartTerrainUpdates.graphChanged(1);
	}

	// scheduleRebuild / scheduleSort: only when a section's pending update really changes (their one
	// setPendingUpdate call each, javap 2026-10-19). Vanilla calls setSectionDirty for unbuilt
	// sections thousands of times while chunks light up; those change nothing a walk would find.
	@Inject(method = "scheduleRebuild", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;setPendingUpdate(Lnet/caffeinemc/mods/sodium/client/render/chunk/ChunkUpdateType;)V"))
	private void originclient$rebuild(CallbackInfo ci) {
		SmartTerrainUpdates.graphChanged(2);
	}

	@Inject(method = "scheduleSort", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;setPendingUpdate(Lnet/caffeinemc/mods/sodium/client/render/chunk/ChunkUpdateType;)V"))
	private void originclient$sort(CallbackInfo ci) {
		SmartTerrainUpdates.graphChanged(3);
	}

	@Inject(method = "processChunkBuildResults", at = @At("HEAD"))
	private void originclient$buildResults(CallbackInfoReturnable<Boolean> cir) {
		SmartTerrainUpdates.graphChanged(4);
	}

	@Inject(method = "markGraphDirty", at = @At("HEAD"))
	private void originclient$markDirty(CallbackInfo ci) {
		SmartTerrainUpdates.onMarkGraphDirty();
	}

	@Inject(method = "isSectionVisible", at = @At("HEAD"), cancellable = true)
	private void originclient$visibleWhileReused(int x, int y, int z, CallbackInfoReturnable<Boolean> cir) {
		if (SmartTerrainUpdates.forceVisible()) {
			cir.setReturnValue(true);
		}
	}
}
