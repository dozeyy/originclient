package com.origin.client.client.mixin;

import com.origin.client.client.render.SmartTerrainUpdates;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Target: Sodium 0.6.13+mc1.21.4 RenderSectionManager (names and signatures read from that jar with
 * javap 2026-10-20: update(Camera, Viewport, FogParameters, boolean); the hooked methods below kept
 * their 1.21.1 names and the single setPendingUpdate call each).
 * Why: Smart Terrain Updates (SmartTerrainUpdates has the full proof). update() is the chunk-graph
 * walk; with a shader pack Iris forces it every frame. HEAD may cancel the MAIN pass's walk when
 * nothing changed (after restoring the last walk's lists); every method that changes what a walk
 * would find marks the cache dirty first; destroy() drops everything held for the manager.
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
		// Off-Map Shadows arms its per-section test for a shadow-pass walk (render/OffMapShadows).
		com.origin.client.client.render.OffMapShadows.walkBegin();
	}

	// Also: Off-Map Shadows disarms its section test, and in dev-shots only (ORIGIN_SHOTS) counts what a
	// shadow-pass walk put in the shadow render lists (render/OffMapShadows.afterWalk).
	// Signature on 1.21.4 (Camera, Viewport, FogParameters, int, boolean)V -- 1.21.4's Sodium passes the
	// fog record where 1.21.1's read RenderSystem; javap of sodium-fabric-0.6.13+mc1.21.4, 2026-10-20.
	@Inject(method = "createTerrainRenderList", at = @At("RETURN"))
	private void originclient$walkEnd(net.minecraft.client.Camera camera,
			net.caffeinemc.mods.sodium.client.render.viewport.Viewport viewport,
			net.minecraft.client.renderer.FogParameters fog, int frame, boolean spectator, CallbackInfo ci) {
		SmartTerrainUpdates.walkEnd();
		com.origin.client.client.render.OffMapShadows.afterWalk((RenderSectionManager) (Object) this, viewport);
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
	// setPendingUpdate call each, javap 2026-10-20). Vanilla calls setSectionDirty for unbuilt
	// sections thousands of times while chunks light up; those change nothing a walk would find.
	@Inject(method = "scheduleRebuild", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;setPendingUpdate(Lnet/caffeinemc/mods/sodium/client/render/chunk/ChunkUpdateType;)V"))
	private void originclient$rebuild(CallbackInfo ci) {
		SmartTerrainUpdates.graphChanged(2);
		// Half-Rate Shadows: a chunk is about to change, so the next frame redraws the shadow map.
		com.origin.client.client.render.HalfRateShadows.onChunksChanged();
	}

	@Inject(method = "scheduleSort", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;setPendingUpdate(Lnet/caffeinemc/mods/sodium/client/render/chunk/ChunkUpdateType;)V"))
	private void originclient$sort(CallbackInfo ci) {
		SmartTerrainUpdates.graphChanged(3);
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
	// snapshot and recorded sections so they don't keep the old ClientLevel alive.
	@Inject(method = "destroy", at = @At("HEAD"))
	private void originclient$destroyed(CallbackInfo ci) {
		SmartTerrainUpdates.onDestroy();
	}
}
