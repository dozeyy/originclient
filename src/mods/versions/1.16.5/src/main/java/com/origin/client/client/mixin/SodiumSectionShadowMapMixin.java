package com.origin.client.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.origin.client.client.render.OffMapShadows;
import me.jellysquid.mods.sodium.client.render.chunk.cull.graph.ChunkGraphNode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Target: Sodium 0.2.0+mc1.16.5 me.jellysquid.mods.sodium.client.render.chunk.cull.graph.ChunkGraphNode
 * .isCulledByFrustum(Lme/jellysquid/mods/sodium/client/util/math/FrustumExtended;)Z at RETURN (javap
 * 2026-10-20): ChunkGraphCuller asks it for every section the walk reaches (initSearch for the start
 * section, bfsEnqueue for each neighbour). PER-VERSION: this Sodium has no render regions, so there is no
 * region hook -- every section comes through here. ModifyReturnValue reads no target argument;
 * getOriginX/Y/Z are public on ChunkGraphNode in this build.
 * Why: Off-Map Shadows (render/OffMapShadows). Iris 1.4.5's shadow frustum culls only by distance and
 * the sun direction, not by the shadow map's own x/y edges, so the shadow walk keeps sections that land
 * outside the map; this answers "culled" for those. Outside an armed shadow walk keepSection answers true.
 * What breaks if it stops applying: nothing visible -- off-map sections keep being drawn into the shadow
 * pass and the saving is gone.
 */
@Pseudo
@Mixin(targets = "me.jellysquid.mods.sodium.client.render.chunk.cull.graph.ChunkGraphNode", remap = false)
public abstract class SodiumSectionShadowMapMixin {
	@ModifyReturnValue(method = "isCulledByFrustum", at = @At("RETURN"), remap = false)
	private boolean originclient$offShadowMap(boolean culled) {
		if (culled) {
			return true;
		}
		ChunkGraphNode self = (ChunkGraphNode) (Object) this;
		return !OffMapShadows.keepSection(self.getOriginX(), self.getOriginY(), self.getOriginZ());
	}
}
