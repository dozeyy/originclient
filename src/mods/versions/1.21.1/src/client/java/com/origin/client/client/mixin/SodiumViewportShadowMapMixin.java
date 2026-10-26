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
 * Target: Sodium 0.8.13+mc1.21.1 Viewport (javap 2026-10-25), its three frustum tests, at RETURN:
 * <ul>
 * <li>getBoxIntersectionDirect(FFFF)I -- camera-relative center + half size; TraversableTree.testChild
 *     tests each tree node with it. -2 = fully inside (the node's children then skip their own tests),
 *     -1 = intersecting, anything else = outside (the node is not traversed).</li>
 * <li>isBoxVisibleDirect(FFFF)Z -- same box shape; TraversableTree.testLeafNode tests each leaf section.</li>
 * <li>isBoxVisible(III)Z -- a section center in block coordinates, made camera-relative with the private
 *     final field transform; OcclusionCuller.isWithinFrustum (the occlusion-culled walk).</li>
 * </ul>
 * PER-VERSION (1.21.1 on Sodium 0.8): Iris 1.8.14-beta.1 forces its shadow-pass walk onto Sodium's tree path
 * (MixinRenderSectionManagerShadow iris$setOutOfGraph), so the shadow walk reaches only the two *Direct
 * tests; the Sodium 0.6 build hooked isBoxVisible(IIIFFF), which the occlusion walk used. isBoxVisible(III) is
 * still hooked in case a shadow walk ever takes the occlusion path.
 * Why: during Iris's shadow-pass walk, a section or tree node entirely off the shadow map's x/y edges
 * answers "not visible", so it never enters the shadow render list (no draw, and its block entities are
 * never extracted). A node only partly off the map loses "fully inside", so its children are each tested.
 * Outside the shadow pass, or with the option off, the value is returned untouched (one static read).
 * What breaks if it stops applying: nothing visible -- off-map sections keep being drawn into the
 * shadow map as before and the F3 line reads 0 chunk checks skipped.
 */
@Mixin(value = Viewport.class, remap = false)
public abstract class SodiumViewportShadowMapMixin {
	@Shadow
	@Final
	private CameraTransform transform;

	@ModifyReturnValue(method = "getBoxIntersectionDirect", at = @At("RETURN"))
	private int originclient$offShadowMapNode(int result, float x, float y, float z, float radius) {
		return OffMapShadows.nodeIntersection(result, x, y, z, radius);
	}

	@ModifyReturnValue(method = "isBoxVisibleDirect", at = @At("RETURN"))
	private boolean originclient$offShadowMapLeaf(boolean visible, float x, float y, float z, float radius) {
		return visible && OffMapShadows.keepBox(x, y, z, radius);
	}

	@ModifyReturnValue(method = "isBoxVisible", at = @At("RETURN"))
	private boolean originclient$offShadowMapSection(boolean visible, int x, int y, int z) {
		return visible && OffMapShadows.keepSection(this.transform, x, y, z);
	}
}
