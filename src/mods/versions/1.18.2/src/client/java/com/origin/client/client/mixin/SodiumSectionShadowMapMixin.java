package com.origin.client.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.origin.client.client.render.OffMapShadows;
import me.jellysquid.mods.sodium.client.render.chunk.graph.ChunkGraphInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Target: Sodium 0.4.1 me.jellysquid.mods.sodium.client.render.chunk.graph.ChunkGraphInfo
 * .isCulledByFrustum(Lme/jellysquid/mods/sodium/client/util/frustum/Frustum;)Z at RETURN (javap
 * 2026-10-20): RenderSectionManager.bfsEnqueue asks it for each section of a region that only
 * INTERSECTs the frustum (a fully INSIDE region runs no section test; SodiumRegionShadowMapMixin turns
 * an INSIDE region that crosses the shadow-map edge into INTERSECT so its sections get here).
 * ModifyReturnValue reads no target argument; getOriginX/Y/Z are public on ChunkGraphInfo in this build.
 * Why: Off-Map Shadows (render/OffMapShadows). Iris 1.6.11's shadow frustum culls only by distance and
 * the sun direction, not by the shadow map's own x/y edges, so the shadow walk keeps sections that land
 * outside the map; this answers "culled" for those. Outside an armed shadow walk keepSection answers true.
 * What breaks if it stops applying: nothing visible -- off-map sections keep being drawn into the shadow
 * pass and the saving is gone.
 */
@Pseudo
@Mixin(targets = "me.jellysquid.mods.sodium.client.render.chunk.graph.ChunkGraphInfo", remap = false)
public abstract class SodiumSectionShadowMapMixin {
	@ModifyReturnValue(method = "isCulledByFrustum", at = @At("RETURN"), remap = false)
	private boolean originclient$offShadowMap(boolean culled) {
		if (culled) {
			return true;
		}
		ChunkGraphInfo self = (ChunkGraphInfo) (Object) this;
		return !OffMapShadows.keepSection(self.getOriginX(), self.getOriginY(), self.getOriginZ());
	}
}
