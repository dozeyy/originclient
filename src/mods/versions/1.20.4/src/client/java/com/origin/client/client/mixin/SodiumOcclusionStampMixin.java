package com.origin.client.client.mixin;

import com.origin.client.client.render.SmartTerrainUpdates;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.occlusion.OcclusionCuller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Target: Sodium 0.5.8 OcclusionCuller, its two RenderSection.setLastVisibleFrame(I)V calls
 * (javap 2026-10-20: static visitNode at offset 10, initWithinWorld at offset 37; nothing else in
 * Sodium 0.5.8 or Iris 1.7.2 stamps sections -- 0.6.13's third site, addNearbySections, does not
 * exist here). Why: main-pass entity culling (isSectionVisible) asks "was this section stamped by
 * the last walk". Iris's shadow walk runs BEFORE the main walk each frame and stamps the same
 * sections, so a reused main walk must re-apply the main walk's stamps; Smart Terrain Updates
 * records which sections the main walk stamped. Redirect (not Inject) so the hot path allocates
 * nothing: the handler is the original call plus one static boolean read. The handlers take only
 * the redirected call's own receiver + argument, which is the call's descriptor, not the method's.
 * What breaks if it stops applying: visitNode and initWithinWorld must both have been seen
 * recording before the feature reuses anything (REQUIRED_HOOKS), and a walk that recorded nothing
 * is never reused, so a missing redirect turns the feature off instead of hiding entities.
 */
@Mixin(value = OcclusionCuller.class, remap = false)
public abstract class SodiumOcclusionStampMixin {
	@Redirect(method = "visitNode", at = @At(value = "INVOKE",
			target = "Lme/jellysquid/mods/sodium/client/render/chunk/RenderSection;setLastVisibleFrame(I)V"))
	private static void originclient$stampVisit(RenderSection section, int frame) {
		section.setLastVisibleFrame(frame);
		SmartTerrainUpdates.onStamp(section, 9);
	}

	@Redirect(method = "initWithinWorld", at = @At(value = "INVOKE",
			target = "Lme/jellysquid/mods/sodium/client/render/chunk/RenderSection;setLastVisibleFrame(I)V"))
	private void originclient$stampInit(RenderSection section, int frame) {
		section.setLastVisibleFrame(frame);
		SmartTerrainUpdates.onStamp(section, 11);
	}
}
