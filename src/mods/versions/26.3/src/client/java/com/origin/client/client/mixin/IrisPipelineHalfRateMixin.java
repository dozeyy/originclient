package com.origin.client.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.origin.client.client.render.HalfRateShadows;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Target: Iris 1.11.7+mc26.3 IrisRenderingPipeline.beginLevelRendering()V, its FIRST read of the
 * shadowRenderTargets field (bytecode 110, `getfield shadowRenderTargets; ifnull 397`: the
 * "if (shadowRenderTargets != null)" around the per-frame shadow clear, the pack's shadow compute
 * dispatches and the shadow clear passes), javap of the jar 2026-10-20 -- the same guard 1.21.1 has.
 * The handler takes only the field value (no method arguments). No @Shadow: the pipeline's private
 * fields are read by reflection in HalfRateShadows, so a different Iris build only turns the feature off.
 * @Pseudo + string target: without Iris the mixin is skipped, never a load error.
 * Why: Half-Rate Shadows (render/HalfRateShadows). This is where each frame decides whether it keeps
 * the last shadow map; on a kept frame the read answers null, so the clear + computes are skipped and
 * the map from the last draw survives into this frame.
 * What breaks if it stops applying: nothing -- no frame is ever kept, shadows draw every frame.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.IrisRenderingPipeline", remap = false)
public abstract class IrisPipelineHalfRateMixin {
	@ModifyExpressionValue(method = "beginLevelRendering", at = @At(value = "FIELD",
			target = "Lnet/irisshaders/iris/pipeline/IrisRenderingPipeline;shadowRenderTargets:Lnet/irisshaders/iris/shadows/ShadowRenderTargets;",
			opcode = Opcodes.GETFIELD, ordinal = 0), remap = false)
	private net.irisshaders.iris.shadows.ShadowRenderTargets originclient$keepShadowMap(net.irisshaders.iris.shadows.ShadowRenderTargets targets) {
		return HalfRateShadows.decide(this, targets) ? null : targets;
	}
}