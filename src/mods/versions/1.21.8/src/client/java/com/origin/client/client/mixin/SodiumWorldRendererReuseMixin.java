package com.origin.client.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.origin.client.client.render.SmartTerrainUpdates;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Target: Sodium 0.7.3+mc1.21.8 SodiumWorldRenderer.setupTerrain(Camera, Viewport, FogParameters,
 * boolean, boolean, ChunkRenderMatrices) (javap 2026-10-20: 0.7 takes Sodium's own util.FogParameters
 * and the ChunkRenderMatrices whose projection it compares with the last call's; its only
 * markGraphDirty call, offset 306, is that camera-change one). This module is only ever paired with
 * this one Sodium build (PerformanceModCatalog.Data.cs, 1.21.6, 1.21.7 and 1.21.8 alike), so the HEAD handler
 * takes the full argument list. Iris 1.9.6's MixinSodiumWorldRenderer redirects the needsUpdate calls
 * in setupTerrain; nothing here touches those.
 * Why: Smart Terrain Updates fingerprints the main pass's camera, matrices and fog at HEAD, and brackets
 * Sodium's own camera-change markGraphDirty so that call is judged by the fingerprint (Iris's shadow
 * setupTerrain makes it fire on every main frame). What breaks if it stops applying: the feature refuses
 * to replay (setupTerrain must have been seen, and a missing camera fingerprint always counts as changed).
 *
 * <p>isEntityVisible(EntityRenderer, Entity) RETURN: dev-shots only (ORIGIN_SHOTS) counts the main
 * pass's entity culling results, so CI can prove replayed frames cull exactly like walked ones. For
 * players the handler returns the value untouched after one constant-false branch (ModifyReturnValue:
 * no allocation). If it stops applying the CI counts read zero; gameplay is unaffected.
 */
@Mixin(value = SodiumWorldRenderer.class, remap = false)
public abstract class SodiumWorldRendererReuseMixin {
	@Inject(method = "setupTerrain", at = @At("HEAD"))
	private void originclient$cameraFingerprint(Camera camera, Viewport viewport, FogParameters fog,
			boolean spectator, boolean updateChunksImmediately, ChunkRenderMatrices matrices, CallbackInfo ci) {
		SmartTerrainUpdates.onSetupTerrain(camera, fog, spectator, updateChunksImmediately, matrices);
	}

	@Inject(method = "setupTerrain", at = @At(value = "INVOKE",
			target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSectionManager;markGraphDirty()V"))
	private void originclient$cameraMarkBegin(CallbackInfo ci) {
		SmartTerrainUpdates.cameraMark(true);
	}

	@Inject(method = "setupTerrain", at = @At(value = "INVOKE",
			target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSectionManager;markGraphDirty()V",
			shift = At.Shift.AFTER))
	private void originclient$cameraMarkEnd(CallbackInfo ci) {
		SmartTerrainUpdates.cameraMark(false);
	}

	@ModifyReturnValue(method = "isEntityVisible", at = @At("RETURN"))
	private boolean originclient$countEntity(boolean visible, EntityRenderer<?, ?> renderer, Entity entity) {
		return SmartTerrainUpdates.countEntity(entity, visible);
	}
}
