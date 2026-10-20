package com.origin.client.client.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.origin.client.client.render.SmartTerrainUpdates;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Target: Sodium 0.5.11 RenderSectionManager.update(Camera, Viewport, int frame, boolean)V at HEAD
 * (javap 2026-10-20). The frame is update()'s own argument (Sodium's WorldRendererMixin passes
 * frame++ per setupRender call); it is taken with @Local(argsOnly) instead of a full argument list.
 * Why: Smart Terrain Updates. HEAD may cancel the MAIN pass's walk when nothing changed, after
 * restoring the last walk's lists and stamping its sections with this frame. Kept apart from
 * SodiumGraphReuseMixin so an argument change can only ever lose this handler, never the
 * chunk-change hooks.
 * What breaks if it stops applying: nothing -- no walk is ever skipped; the terrain looks and
 * culls exactly as without the feature.
 */
@Mixin(value = RenderSectionManager.class, remap = false)
public abstract class SodiumUpdateReuseMixin {
	@Inject(method = "update", at = @At("HEAD"), cancellable = true)
	private void originclient$reuseGraph(CallbackInfo ci, @Local(argsOnly = true) int frame) {
		if (SmartTerrainUpdates.beforeUpdate((RenderSectionManager) (Object) this, frame)) {
			ci.cancel();
		}
	}
}
