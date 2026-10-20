package com.origin.client.client.mixin;

import com.origin.client.client.render.HalfRateShadows;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Half-Rate Shadows' chunk-change hooks on Sodium 0.4.10 (MC 1.20). Sodium 0.5's are in
 * SodiumGraphReuseMixin, whose other handlers don't fit 0.4.10, so OriginMixinPlugin applies this one
 * only on Sodium 0.4.10 and that one only on 0.5.13 (same class name, different members).
 *
 * <p>TARGET (javap of sodium-fabric-mc1.20-0.4.10+build.27, 2026-10-20): RenderSectionManager
 * .scheduleRebuild(IIIZ)V calls RenderSection.markForUpdate(ChunkUpdateType) at offsets 69 and 80 when a
 * built section must be rebuilt (a block edit); .performPendingUploads()Z drains the finished builds and
 * returns true when it uploaded any. Neither handler takes the target's arguments.
 *
 * <p>WHY: a kept shadow map is only valid while the world it shows is unchanged. Either event means the
 * next frame draws the shadow map for real (HalfRateShadows.onChunksChanged).
 *
 * <p>IF IT STOPS APPLYING: Half-Rate Shadows keeps a frame across a block edit, so a placed or broken
 * block's shadow appears one frame late (never wrong for longer: the next frame always draws).
 */
@Mixin(value = RenderSectionManager.class, remap = false)
public abstract class Sodium04HalfRateMixin {
	@Inject(method = "scheduleRebuild", at = @At(value = "INVOKE",
			target = "Lme/jellysquid/mods/sodium/client/render/chunk/RenderSection;markForUpdate(Lme/jellysquid/mods/sodium/client/render/chunk/ChunkUpdateType;)V"))
	private void originclient$rebuildScheduled(CallbackInfo ci) {
		HalfRateShadows.onChunksChanged();
	}

	@Inject(method = "performPendingUploads", at = @At("RETURN"))
	private void originclient$uploaded(CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValueZ()) {
			HalfRateShadows.onChunksChanged();
		}
	}
}
