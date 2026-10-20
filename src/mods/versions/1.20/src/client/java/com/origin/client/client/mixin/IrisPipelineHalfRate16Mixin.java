package com.origin.client.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.origin.client.client.render.HalfRateShadows;
import net.coderbot.iris.pipeline.newshader.NewWorldRenderingPipeline;
import net.coderbot.iris.shadows.ShadowRenderTargets;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * MC 1.20 only (OriginMixinPlugin: Iris 1.6.4, net.coderbot; MC 1.20.1 uses IrisPipelineHalfRateMixin).
 * Target: Iris 1.6.4 NewWorldRenderingPipeline.beginLevelRendering()V, its first read of the
 * shadowRenderTargets field (bytecode 79, `getfield shadowRenderTargets; ifnull 304`: the
 * "if (shadowRenderTargets != null)" around the per-frame shadow clear, the pack's shadow compute
 * dispatches and the shadow clear passes), javap of the jar 2026-10-20. No @Shadow: the pipeline's
 * private fields are read by reflection in HalfRateShadows, so a different Iris build only turns the
 * feature off.
 * Why: Half-Rate Shadows (render/HalfRateShadows). This is where each frame decides whether it keeps
 * the last shadow map; on a kept frame the read answers null, so the clear + computes are skipped
 * and the map from the last draw survives into this frame.
 * What breaks if it stops applying: nothing -- no frame is ever kept, shadows draw every frame.
 */
@Mixin(value = NewWorldRenderingPipeline.class, remap = false)
public abstract class IrisPipelineHalfRate16Mixin {
	@ModifyExpressionValue(method = "beginLevelRendering", at = @At(value = "FIELD",
			target = "Lnet/coderbot/iris/pipeline/newshader/NewWorldRenderingPipeline;shadowRenderTargets:Lnet/coderbot/iris/shadows/ShadowRenderTargets;",
			opcode = Opcodes.GETFIELD, ordinal = 0))
	private ShadowRenderTargets originclient$keepShadowMap(ShadowRenderTargets targets) {
		return HalfRateShadows.decide(this, targets) ? null : targets;
	}
}
