package com.origin.client.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.origin.client.client.render.OffMapShadows;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * SETTINGS > Performance > Off-Map Shadows, chunk-section half (render/OffMapShadows).
 * Target: Sodium 0.9.2+mc26.2 Viewport (final class), javap 2026-10-20:
 *   isBoxVisibleDirect(FFFF)Z       -- frustum.testAab(x-h .. x+h), the tree walk's LEAF test
 *                                      (TraversableTree.testLeafNode, half size 9.125 around the center)
 *   getBoxIntersectionDirect(FFFF)I -- frustum.intersectAab(...), the tree walk's NODE test
 *                                      (TraversableTree.testChild: -2 inside, -1 crossing, else outside)
 * Both take a camera-relative box center and a cube half size; handlers read them with
 * @Local(argsOnly = true), no declared argument list. 1.21.1's isBoxVisible(IIIFFF) is gone in 0.9.
 * @Pseudo + string target: without Sodium the mixin is skipped, never a load error.
 * Why: during Iris's shadow-pass walk (RenderSectionManager.renderOutOfGraph, armed with ITS viewport by
 * SodiumRenderSectionManagerHooksMixin), a box entirely off the shadow map's x/y edges answers
 * "not visible", so its sections never enter the shadow render list (no draw, no block entities).
 * Any other viewport (the main pass, the async cull threads), or the option off: one reference
 * compare and the value is returned untouched.
 * What breaks if it stops applying: nothing visible -- off-map sections keep being drawn into the shadow
 * map as before and the F3 line reads 0 chunk checks.
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.viewport.Viewport", remap = false)
public abstract class SodiumViewportShadowMapMixin {
	@ModifyReturnValue(method = "isBoxVisibleDirect", at = @At("RETURN"), remap = false)
	private boolean originclient$offShadowMapLeaf(boolean visible, @Local(argsOnly = true, ordinal = 0) float x,
			@Local(argsOnly = true, ordinal = 1) float y, @Local(argsOnly = true, ordinal = 2) float z,
			@Local(argsOnly = true, ordinal = 3) float h) {
		return OffMapShadows.keepBox(this, visible, x, y, z, h);
	}

	@ModifyReturnValue(method = "getBoxIntersectionDirect", at = @At("RETURN"), remap = false)
	private int originclient$offShadowMapNode(int result, @Local(argsOnly = true, ordinal = 0) float x,
			@Local(argsOnly = true, ordinal = 1) float y, @Local(argsOnly = true, ordinal = 2) float z,
			@Local(argsOnly = true, ordinal = 3) float h) {
		return OffMapShadows.intersectBox(this, result, x, y, z, h);
	}
}