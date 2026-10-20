package com.origin.client.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.origin.client.client.render.SmartTerrainUpdates;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.FogParameters;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Target: Sodium 0.6.13+mc1.21.4 SodiumWorldRenderer.setupTerrain(Camera, Viewport, FogParameters,
 * boolean, boolean) (javap of that jar 2026-10-20: 1.21.4 added the FogParameters argument, which
 * setupTerrain passes straight into RenderSectionManager.update; its only markGraphDirty call is
 * still the camera-change one). Why: Smart Terrain Updates fingerprints the main pass's camera and
 * fog at HEAD, and brackets Sodium's own camera-change markGraphDirty so that call is judged by the
 * fingerprint. What breaks if it stops applying: the feature refuses to reuse (setupTerrain must have
 * been seen, and a missing camera fingerprint always counts as changed).
 *
 * <p>Applied only on Sodium 0.6.13 + Iris 1.8.8 (OriginMixinPlugin, SodiumIrisBuilds.SMART_TERRAIN): the
 * HEAD handler takes the FogParameters argument, and Sodium 0.6.1/0.6.8 (1.21.2/1.21.3) have
 * setupTerrain(Camera, Viewport, boolean, boolean) (javap 2026-10-20), where it would fail to apply.
 * Smart Terrain is off on those builds anyway.
 *
 * <p>isEntityVisible(EntityRenderer, Entity) RETURN (1.21.4 takes the renderer too; 1.21.1 took only
 * the entity): dev-shots only (ORIGIN_SHOTS) counts the main pass's entity culling results, so CI can
 * prove reused frames cull exactly like walked ones. For players the handler returns the value
 * untouched after one constant-false branch (ModifyReturnValue: no allocation). If it stops applying
 * the CI counts read zero; gameplay is unaffected.
 */
@Mixin(value = SodiumWorldRenderer.class, remap = false)
public abstract class SodiumWorldRendererReuseMixin {
	@Inject(method = "setupTerrain", at = @At("HEAD"))
	private void originclient$cameraFingerprint(Camera camera, Viewport viewport, FogParameters fog,
			boolean spectator, boolean updateChunksImmediately, CallbackInfo ci) {
		SmartTerrainUpdates.onSetupTerrain(camera, fog, spectator, updateChunksImmediately);
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
