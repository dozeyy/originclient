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
 * Target: Sodium 0.8.12+mc1.21.11 SodiumWorldRenderer (javap 2026-10-20). This module serves one Sodium
 * build, so the handlers name the arguments.
 * <ul>
 * <li>setupTerrain(Camera, Viewport, FogParameters (Sodium's own record), boolean spectator, boolean
 *     updateChunksImmediately, ChunkRenderMatrices) HEAD: Smart Terrain Updates fingerprints the main
 *     pass's camera, matrices and fog. Its one markGraphDirty call (offset 306) is Sodium's camera-change
 *     check; it is bracketed so that call is judged by the fingerprint instead.</li>
 * <li>isEntityVisible(EntityRenderer, Entity) RETURN: dev-shots only (ORIGIN_SHOTS) counts the main
 *     pass's entity culling results, so CI can prove reused frames cull exactly like walked ones. For
 *     players the handler returns the value untouched after one constant-false branch
 *     (ModifyReturnValue: no allocation).</li>
 * </ul>
 * What breaks if it stops applying: the feature refuses to reuse (setupTerrain must have been seen, and a
 * missing camera fingerprint always counts as changed); the dev entity counts read zero.
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
