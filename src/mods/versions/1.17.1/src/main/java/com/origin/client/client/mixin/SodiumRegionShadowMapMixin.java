package com.origin.client.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.origin.client.client.render.OffMapShadows;
import me.jellysquid.mods.sodium.client.render.chunk.region.RenderRegion;
import me.jellysquid.mods.sodium.client.util.frustum.Frustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Target: Sodium 0.3.4 me.jellysquid.mods.sodium.client.render.chunk.region.RenderRegion
 * .updateVisibility(Lme/jellysquid/mods/sodium/client/util/frustum/Frustum;)V, the value of its one
 * Frustum.testBox(FFFFFF)Frustum$Visibility call (bytecode 61, stored into the visibility field at 66;
 * javap 2026-10-20). With a shader pack the frustum is Iris's shadow frustum
 * (MixinAdvancedShadowCullingFrustum implements testBox: OUTSIDE / INSIDE / INTERSECT). bfsEnqueue then
 * skips an OUTSIDE region, runs no section test in an INSIDE one, and tests each section of an INTERSECT one.
 * The handler reads no target argument (the region's origin comes from its own public getters).
 * Why: Off-Map Shadows (render/OffMapShadows). A region entirely off the shadow map's x/y edges becomes
 * OUTSIDE (the whole 8x4x8-section block skipped in one test); one crossing the edge that Iris called
 * INSIDE becomes INTERSECT so SodiumSectionShadowMapMixin can drop its off-map sections. Never applies
 * outside an armed shadow walk (classifyRegion answers 0 = leave).
 * What breaks if it stops applying: nothing visible -- regions keep Iris's answer; the section-level
 * test still drops off-map sections inside INTERSECT regions.
 */
@Pseudo
@Mixin(targets = "me.jellysquid.mods.sodium.client.render.chunk.region.RenderRegion", remap = false)
public abstract class SodiumRegionShadowMapMixin {
	@ModifyExpressionValue(method = "updateVisibility", remap = false, at = @At(value = "INVOKE",
			target = "Lme/jellysquid/mods/sodium/client/util/frustum/Frustum;testBox(FFFFFF)Lme/jellysquid/mods/sodium/client/util/frustum/Frustum$Visibility;",
			remap = false))
	private Frustum.Visibility originclient$offShadowMap(Frustum.Visibility visibility) {
		if (visibility == Frustum.Visibility.OUTSIDE) {
			return visibility;
		}
		RenderRegion self = (RenderRegion) (Object) this;
		int c = OffMapShadows.classifyRegion(self.getOriginX(), self.getOriginY(), self.getOriginZ());
		if (c == 1) {
			return Frustum.Visibility.OUTSIDE;
		}
		if (c == 2 && visibility == Frustum.Visibility.INSIDE) {
			return Frustum.Visibility.INTERSECT;
		}
		return visibility;
	}
}
