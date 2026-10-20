package com.origin.client.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.origin.client.client.render.HalfRateShadows;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.shadows.ShadowRenderTargets;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Target: Iris 1.9.7+mc1.21.10 IrisRenderingPipeline.beginLevelRendering()V, its first read of the
 * shadowRenderTargets field (bytecode 110, `getfield shadowRenderTargets; ifnull 400`: the
 * "if (shadowRenderTargets != null)" around the per-frame shadow clear, the pack's shadow compute
 * dispatches and the shadow clear passes), javap of the 1.9.7+mc1.21.10 jar 2026-10-20 (first shadowRenderTargets read at offset 110, the same null guard as 1.21.1; the clearImages passes before it are
 * Iris's custom-image clears, which HalfRateShadows never keeps a frame for). No @Shadow: the pipeline's
 * private fields are read by reflection in HalfRateShadows, so a different Iris build only turns the
 * feature off.
 * Why: Half-Rate Shadows (render/HalfRateShadows). This is where each frame decides whether it keeps
 * the last shadow map; on a kept frame the read answers null, so the clear + computes are skipped
 * and the map from the last draw survives into this frame.
 * What breaks if it stops applying: nothing -- no frame is ever kept, shadows draw every frame.
 */
@Mixin(value = IrisRenderingPipeline.class, remap = false)
public abstract class IrisPipelineHalfRateMixin {
	@ModifyExpressionValue(method = "beginLevelRendering", at = @At(value = "FIELD",
			target = "Lnet/irisshaders/iris/pipeline/IrisRenderingPipeline;shadowRenderTargets:Lnet/irisshaders/iris/shadows/ShadowRenderTargets;",
			opcode = Opcodes.GETFIELD, ordinal = 0))
	private ShadowRenderTargets originclient$keepShadowMap(ShadowRenderTargets targets) {
		return HalfRateShadows.decide(this, targets) ? null : targets;
	}
}
