package com.origin.client.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.origin.client.client.render.SmartTerrainUpdates;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Target: Sodium 0.6.13 SodiumWorldRenderer.setupTerrain(Camera, Viewport, boolean, boolean)
 * (read from the jar 2026-10-19; its only markGraphDirty call is the camera-change one in
 * camera_setup). Why: Smart Terrain Updates fingerprints the main pass's camera at HEAD, and
 * brackets Sodium's own camera-change markGraphDirty so that call is judged by the fingerprint.
 * What breaks if it stops applying: the feature refuses to reuse (setupTerrain must have been
 * seen, and a missing camera fingerprint always counts as changed).
 *
 * <p>isEntityVisible(Entity) RETURN: dev-shots only (ORIGIN_SHOTS) counts the main pass's entity
 * culling results, so CI can prove reused frames cull exactly like walked ones. For players the
 * handler returns the value untouched after one constant-false branch (ModifyReturnValue: no
 * allocation). If it stops applying the CI counts read zero; gameplay is unaffected.
 */
@Mixin(value = SodiumWorldRenderer.class, remap = false)
public abstract class SodiumWorldRendererReuseMixin {
	@Inject(method = "setupTerrain", at = @At("HEAD"))
	private void originclient$cameraFingerprint(Camera camera, Viewport viewport, boolean spectator,
			boolean updateChunksImmediately, CallbackInfo ci) {
		SmartTerrainUpdates.onSetupTerrain(camera, spectator, updateChunksImmediately);
	}

	@Inject(method = "setupTerrain", at = @At(value = "INVOKE",
			target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSectionManager;markGraphDirty()V"))
	private void originclient$cameraMarkBegin(Camera camera, Viewport viewport, boolean spectator,
			boolean updateChunksImmediately, CallbackInfo ci) {
		SmartTerrainUpdates.cameraMark(true);
	}

	@Inject(method = "setupTerrain", at = @At(value = "INVOKE",
			target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSectionManager;markGraphDirty()V",
			shift = At.Shift.AFTER))
	private void originclient$cameraMarkEnd(Camera camera, Viewport viewport, boolean spectator,
			boolean updateChunksImmediately, CallbackInfo ci) {
		SmartTerrainUpdates.cameraMark(false);
	}

	@ModifyReturnValue(method = "isEntityVisible", at = @At("RETURN"))
	private boolean originclient$countEntity(boolean visible, Entity entity) {
		return SmartTerrainUpdates.countEntity(entity, visible);
	}
}
