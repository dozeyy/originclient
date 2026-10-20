package com.origin.client.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.origin.client.client.render.OffMapShadows;
import me.jellysquid.mods.sodium.client.render.viewport.CameraTransform;
import me.jellysquid.mods.sodium.client.render.viewport.Viewport;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * SETTINGS > Performance > Off-Map Shadows, chunk-section half (render/OffMapShadows).
 * Target: Sodium 0.5.5 Viewport.isBoxVisible(IIIF)Z at RETURN -- ONE radius for all three axes
 * (0.5.8+ takes three, IIIFFF) -- and its private final field transform (CameraTransform), javap
 * 2026-10-20. Its only caller is OcclusionCuller.isOutsideFrustum (section center, radius 9.125f)
 * for every section the chunk-graph walk reaches; the box it tests is section center minus camera
 * +/- radius, which OffMapShadows.keepSection rebuilds the same way.
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
	private boolean originclient$offShadowMap(boolean visible, int ox, int oy, int oz, float radius) {
		return visible && OffMapShadows.keepSection(this.transform, ox, oy, oz, radius, radius, radius);
	}
}
