package com.origin.client.client.mixin;

import com.origin.client.client.render.SmartTerrainUpdates;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Target: Sodium 0.6.13 RenderSectionManager.update(Camera, Viewport, boolean)V at HEAD (javap
 * 2026-10-25: update() bumps lastUpdatedFrame itself at offset 2-7, walks at 18, clears
 * needsGraphUpdate at 23; 0.5.11's update was handed the frame instead).
 * Why: Smart Terrain Updates. HEAD may cancel the MAIN pass's walk when nothing changed, after
 * restoring the last walk's lists and stamping its sections with the bumped frame. Kept apart from
 * SodiumGraphReuseMixin so a signature change can only ever lose this handler, never the
 * chunk-change hooks.
 * What breaks if it stops applying: nothing -- no walk is ever skipped; the terrain looks and
 * culls exactly as without the feature.
 */
@Mixin(value = RenderSectionManager.class, remap = false)
public abstract class SodiumUpdateReuseMixin {
	@Inject(method = "update", at = @At("HEAD"), cancellable = true)
	private void originclient$reuseGraph(CallbackInfo ci) {
		if (SmartTerrainUpdates.beforeUpdate((RenderSectionManager) (Object) this)) {
			ci.cancel();
		}
	}
}
