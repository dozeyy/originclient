package com.origin.client.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.origin.client.client.render.SmartTerrainUpdates;
import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Target: Sodium 0.5.11 SodiumWorldRenderer.setupTerrain(Camera, Viewport, int frame,
 * boolean spectator, boolean updateChunksImmediately)V (javap 2026-10-20; its only markGraphDirty
 * call is the camera-change one at offset 194, guarded by Sodium's own position / angle /
 * RenderSystem.getShaderFogEnd checks). The arguments are taken with @Local(argsOnly) (Camera, and
 * the first / second boolean), not a full list, so only the types matter.
 * Why: Smart Terrain Updates fingerprints the main pass's camera at HEAD, and brackets Sodium's
 * own camera-change markGraphDirty so that call is judged by the fingerprint.
 * What breaks if it stops applying: the feature refuses to reuse (setupTerrain must have been
 * seen, and a missing camera fingerprint always counts as changed).
 *
 * <p>isEntityVisible(Entity)Z RETURN: dev-shots only (ORIGIN_SHOTS) counts the main pass's entity
 * culling results, so CI can prove reused frames cull exactly like walked ones. For players the
 * handler returns the value untouched after one constant-false branch (ModifyReturnValue: no
 * allocation). If it stops applying the CI counts read zero; gameplay is unaffected.
 */
@Mixin(value = SodiumWorldRenderer.class, remap = false)
public abstract class SodiumWorldRendererReuseMixin {
	@Inject(method = "setupTerrain", at = @At("HEAD"))
	private void originclient$cameraFingerprint(CallbackInfo ci, @Local(argsOnly = true) Camera camera,
			@Local(argsOnly = true, ordinal = 0) boolean spectator,
			@Local(argsOnly = true, ordinal = 1) boolean updateChunksImmediately) {
		SmartTerrainUpdates.onSetupTerrain(camera, spectator, updateChunksImmediately);
	}

	@Inject(method = "setupTerrain", at = @At(value = "INVOKE",
			target = "Lme/jellysquid/mods/sodium/client/render/chunk/RenderSectionManager;markGraphDirty()V"))
	private void originclient$cameraMarkBegin(CallbackInfo ci) {
		SmartTerrainUpdates.cameraMark(true);
	}

	@Inject(method = "setupTerrain", at = @At(value = "INVOKE",
			target = "Lme/jellysquid/mods/sodium/client/render/chunk/RenderSectionManager;markGraphDirty()V",
			shift = At.Shift.AFTER))
	private void originclient$cameraMarkEnd(CallbackInfo ci) {
		SmartTerrainUpdates.cameraMark(false);
	}

	@ModifyReturnValue(method = "isEntityVisible", at = @At("RETURN"))
	private boolean originclient$countEntity(boolean visible, @Local(argsOnly = true) Entity entity) {
		return SmartTerrainUpdates.countEntity(entity, visible);
	}
}
