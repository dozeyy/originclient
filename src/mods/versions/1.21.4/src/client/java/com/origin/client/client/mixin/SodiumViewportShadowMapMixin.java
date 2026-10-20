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
 * Target: Sodium 0.6.13 Viewport.isBoxVisible(IIIFFF)Z at RETURN, and its private final field
 * transform (CameraTransform) -- javap of 0.6.13+mc1.21.4 2026-10-20 (unchanged from 1.21.1). OcclusionCuller calls it for every section the
 * chunk-graph walk reaches (isWithinFrustum / isWithinNearbySectionFrustum); the box it tests is
 * section center minus camera, which the handler rebuilds the same way.
 * Why: during Iris's shadow-pass walk, a section entirely off the shadow map's x/y edges answers
 * "not visible", so it never enters the shadow render list (no draw, and its block entities are
 * never collected). Outside the shadow pass, or with the option off, the value is returned untouched.
 * What breaks if it stops applying: nothing visible -- off-map sections keep being drawn into the
 * shadow map as before and the F3 line reads 0 sections skipped.
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
}
