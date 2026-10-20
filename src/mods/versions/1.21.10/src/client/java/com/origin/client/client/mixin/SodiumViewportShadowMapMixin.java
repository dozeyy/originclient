package com.origin.client.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.origin.client.client.render.OffMapShadows;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * SETTINGS > Performance > Off-Map Shadows, chunk-section half (render/OffMapShadows).
 * Target: Sodium 0.7.3+mc1.21.10 Viewport (javap 2026-10-20), its private final field transform
 * (CameraTransform) and three box tests at RETURN:
 * <ul>
 * <li>isBoxVisible(IIIFFF)Z -- OcclusionCuller's per-section test (box = section center minus camera);</li>
 * <li>isBoxVisibleDirect(FFFF)Z and getBoxIntersectionDirect(FFFF)I -- camera-relative center plus half
 *     size. PER-VERSION (0.7.3): Iris 1.9.7 forces RenderSectionManager.isOutOfGraph to true in the shadow
 *     pass, so the shadow walk is Sodium's section-tree traversal (TraversableTree.testChild / testLeafNode),
 *     which only ever calls these two. getBoxIntersectionDirect returns JOML's codes: -2 inside (the
 *     tree then skips frustum tests for the whole subtree), -1 intersecting, 0..5 outside.</li>
 * </ul>
 * Why: during Iris's shadow-pass walk, a box entirely off the shadow map's x/y edges answers "not
 * visible" (outside), so its sections never enter the shadow render list (no draw, and their block
 * entities are never collected); a box only partly on the map is downgraded from inside to intersecting
 * so the tree tests its children one by one. Outside the shadow pass, or with the option off, the value
 * is returned untouched.
 * What breaks if it stops applying: nothing visible -- off-map sections keep being drawn into the
 * shadow map as before and the F3 line reads 0 chunk checks skipped.
 */
@Mixin(value = Viewport.class, remap = false)
public abstract class SodiumViewportShadowMapMixin {
	@Shadow
	@Final
	private CameraTransform transform;

	@ModifyReturnValue(method = "isBoxVisible", at = @At("RETURN"))
	private boolean originclient$offShadowMap(boolean visible, int ox, int oy, int oz, float sx, float sy, float sz) {
		return visible && OffMapShadows.keepSection(this.transform, ox, oy, oz, sx, sy, sz);
	}

	@ModifyReturnValue(method = "isBoxVisibleDirect", at = @At("RETURN"))
	private boolean originclient$offShadowMapLeaf(boolean visible, float x, float y, float z, float half) {
		return visible && OffMapShadows.keepBox(x, y, z, half);
	}

	@ModifyReturnValue(method = "getBoxIntersectionDirect", at = @At("RETURN"))
	private int originclient$offShadowMapNode(int result, float x, float y, float z, float half) {
		return OffMapShadows.intersectBox(result, x, y, z, half);
	}
}
