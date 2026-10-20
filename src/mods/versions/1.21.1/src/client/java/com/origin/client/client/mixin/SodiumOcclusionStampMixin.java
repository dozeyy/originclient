package com.origin.client.client.mixin;

import com.origin.client.client.render.SmartTerrainUpdates;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.OcclusionCuller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Target: Sodium 0.6.13 OcclusionCuller, its three RenderSection.setLastVisibleFrame(I)V calls
 * (javap 2026-10-19: static visitNode, addNearbySections, initWithinWorld; nothing else in Sodium
 * or Iris 1.8.8 stamps sections). Why: main-pass entity culling (isSectionVisible) asks "was this
 * section stamped by the last walk". Iris's shadow walk runs BEFORE the main walk each frame and
 * stamps the same sections, so a reused main walk must re-apply the main walk's stamps; Smart
 * Terrain Updates records which sections the main walk stamped. Redirect (not Inject) so the hot
 * path allocates nothing: the handler is the original call plus one static boolean read.
 * What breaks if it stops applying: visitNode and initWithinWorld must have been seen recording
 * before the feature reuses anything (REQUIRED_HOOKS), and a walk that recorded nothing is never
 * reused, so a missing redirect turns the feature off instead of hiding entities.
 */
@Mixin(value = OcclusionCuller.class, remap = false)
public abstract class SodiumOcclusionStampMixin {
	@Redirect(method = "visitNode", at = @At(value = "INVOKE",
			target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;setLastVisibleFrame(I)V"))
	private static void originclient$stampVisit(RenderSection section, int frame) {
		section.setLastVisibleFrame(frame);
		SmartTerrainUpdates.onStamp(section, 9);
	}

	@Redirect(method = "addNearbySections", at = @At(value = "INVOKE",
			target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;setLastVisibleFrame(I)V"))
	private void originclient$stampNearby(RenderSection section, int frame) {
		section.setLastVisibleFrame(frame);
		SmartTerrainUpdates.onStamp(section, 10);
	}

	@Redirect(method = "initWithinWorld", at = @At(value = "INVOKE",
			target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;setLastVisibleFrame(I)V"))
	private void originclient$stampInit(RenderSection section, int frame) {
		section.setLastVisibleFrame(frame);
		SmartTerrainUpdates.onStamp(section, 11);
	}
}
