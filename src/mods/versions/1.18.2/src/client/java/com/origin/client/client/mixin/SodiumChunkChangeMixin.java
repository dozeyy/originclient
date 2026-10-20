package com.origin.client.client.mixin;

import com.origin.client.client.render.HalfRateShadows;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Target: Sodium 0.4.1 me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager, HEAD of
 * scheduleRebuild(IIIZ)V (a block changed), onChunkRenderUpdates(IIILme/.../ChunkRenderData;)V (a finished
 * rebuild landed: RenderSection.setData -> SodiumWorldRenderer.onChunkRenderUpdated), onChunkAdded(II)V and
 * onChunkRemoved(II)V (javap 2026-10-20). Every handler takes only CallbackInfo: this is the must-run half
 * of Half-Rate Shadows, kept apart from every argument-dependent handler so nothing here can fail to apply
 * on a build whose argument lists differ. markGraphDirty is NOT used: Iris 1.6.11 forces it on every
 * shadow frame, so it would mark every frame changed.
 * Why: Half-Rate Shadows (render/HalfRateShadows) must not keep a shadow map across a frame where the
 * terrain changed, or a placed/broken block's shadow would lag a frame behind.
 * What breaks if it stops applying: with Half-Rate Shadows on, a changed block's shadow can lag one
 * frame (until the next real draw, at most every other frame). Nothing crashes; the mixin has no
 * argument-dependent handler, so on the pinned Sodium it always applies (dev-shots run.log proves it).
 */
@Pseudo
@Mixin(targets = "me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager", remap = false)
public abstract class SodiumChunkChangeMixin {
	@Inject(method = {"scheduleRebuild", "onChunkRenderUpdates", "onChunkAdded", "onChunkRemoved"},
			at = @At("HEAD"), remap = false)
	private void originclient$chunksChanged(CallbackInfo ci) {
		HalfRateShadows.onChunksChanged();
	}
}
