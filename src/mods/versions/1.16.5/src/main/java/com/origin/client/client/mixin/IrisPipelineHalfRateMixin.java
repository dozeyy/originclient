package com.origin.client.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.origin.client.client.render.HalfRateShadows;
import net.coderbot.iris.shadows.ShadowRenderTargets;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Target: Iris 1.4.5+mc1.16.5 net.coderbot.iris.pipeline.DeferredWorldRenderingPipeline
 * .prepareRenderTargets()V (private; called once per frame from beginLevelRendering, bytecode 93), its
 * first read of the shadowRenderTargets field (bytecode 16, `getfield shadowRenderTargets; ifnull 227`:
 * the "if (shadowRenderTargets != null)" around the shadow full clear, the shadow depth clear, the
 * pack's shadow compute dispatch and the per-frame shadow clear passes), javap 2026-10-20.
 * PER-VERSION (pre-1.17): 1.16.5 has no core shaders, so this Iris draws through the Deferred pipeline,
 * and the shadow clear sits in prepareRenderTargets instead of beginLevelRendering -- the same null guard
 * 1.21.1's IrisRenderingPipeline has. No @Shadow: the pipeline's private fields are read by reflection
 * in HalfRateShadows, so a different Iris build only turns the feature off.
 * Why: Half-Rate Shadows (render/HalfRateShadows). This is where each frame decides whether it keeps the
 * last shadow map; on a kept frame the read answers null, so the clear is skipped and the map from the
 * last draw survives into this frame.
 * What breaks if it stops applying: nothing -- no frame is ever kept, shadows draw every frame.
 */
@Pseudo
@Mixin(targets = "net.coderbot.iris.pipeline.DeferredWorldRenderingPipeline", remap = false)
public abstract class IrisPipelineHalfRateMixin {
	@ModifyExpressionValue(method = "prepareRenderTargets", remap = false, at = @At(value = "FIELD",
			target = "Lnet/coderbot/iris/pipeline/DeferredWorldRenderingPipeline;shadowRenderTargets:Lnet/coderbot/iris/shadows/ShadowRenderTargets;",
			opcode = Opcodes.GETFIELD, ordinal = 0, remap = false))
	private ShadowRenderTargets originclient$keepShadowMap(ShadowRenderTargets targets) {
		return HalfRateShadows.decide(this, targets) ? null : targets;
	}
}
