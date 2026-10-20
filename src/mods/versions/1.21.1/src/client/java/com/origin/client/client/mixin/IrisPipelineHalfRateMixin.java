package com.origin.client.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.origin.client.client.render.HalfRateShadows;
import net.irisshaders.iris.gl.buffer.ShaderStorageBufferHolder;
import net.irisshaders.iris.gl.image.GlImage;
import net.irisshaders.iris.gui.option.IrisVideoSettings;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.shadows.ShadowRenderTargets;
import net.irisshaders.iris.shadows.ShadowRenderer;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Target: Iris 1.8.8 IrisRenderingPipeline.beginLevelRendering()V, its first read of the
 * shadowRenderTargets field (bytecode 156, `getfield shadowRenderTargets; ifnull 445`: the
 * "if (shadowRenderTargets != null)" around the per-frame shadow clear, the pack's shadow compute
 * dispatches and the shadow clear passes). Fields read with javap from the jar 2026-10-19:
 * clearImages GlImage[] (final), shaderStorageBufferHolder ShaderStorageBufferHolder,
 * shadowRenderer ShadowRenderer (final).
 * Why: Half-Rate Shadows (render/HalfRateShadows). This is where each frame decides whether it keeps
 * the last shadow map; on a kept frame the read answers null, so the clear + computes are skipped
 * and the map from the last draw survives into this frame.
 * What breaks if it stops applying: nothing -- no frame is ever kept, shadows draw every frame.
 */
@Mixin(value = IrisRenderingPipeline.class, remap = false)
public abstract class IrisPipelineHalfRateMixin {
	@Shadow
	@Final
	private GlImage[] clearImages;

	@Shadow
	private ShaderStorageBufferHolder shaderStorageBufferHolder;

	@Shadow
	@Final
	private ShadowRenderer shadowRenderer;

	@ModifyExpressionValue(method = "beginLevelRendering", at = @At(value = "FIELD",
			target = "Lnet/irisshaders/iris/pipeline/IrisRenderingPipeline;shadowRenderTargets:Lnet/irisshaders/iris/shadows/ShadowRenderTargets;",
			opcode = Opcodes.GETFIELD, ordinal = 0))
	private ShadowRenderTargets originclient$keepShadowMap(ShadowRenderTargets targets) {
		// Packs that clear custom images every frame or use storage buffers may write those from the
		// shadow pass: a kept frame would leave them cleared or stale, so those packs never keep.
		boolean keep = HalfRateShadows.decide(this.shadowRenderer, targets != null && this.shadowRenderer != null,
				targets != null && targets.isFullClearRequired(),
				this.clearImages.length > 0 || this.shaderStorageBufferHolder != null,
				IrisVideoSettings.shadowDistance);
		return keep ? null : targets;
	}
}
