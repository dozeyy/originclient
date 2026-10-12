package com.origin.client.client.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.origin.client.client.mods.GlintColorizer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

// Glint Colorizer, the blend half on 1.21.6-1.21.8 (the texture half is still
// GlintColorizerMixin's texturing hook). On this era the blend is part of the
// render PIPELINE, not GL state: CompositeRenderType.draw (package-private,
// verified against the decompiled 1.21.6 and 1.21.8 RenderType) runs the
// shards' setupRenderState first -- where GlintColorizer.onGlintSetup decides
// -- and only then opens the RenderPass and calls setPipeline(renderPipeline).
// GlintColorizer.pipeline returns vanilla's argument untouched unless that
// decision asked for a variant AND the pipeline is RenderPipelines.GLINT, so
// every other render type draws exactly as before.
@Mixin(targets = "net.minecraft.client.renderer.RenderType$CompositeRenderType")
public class GlintPipelineMixin {
	@ModifyArg(method = "draw",
			at = @At(value = "INVOKE",
					target = "Lcom/mojang/blaze3d/systems/RenderPass;setPipeline(Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V"))
	private RenderPipeline originclient$glintBlend(RenderPipeline pipeline) {
		try {
			return GlintColorizer.pipeline(pipeline);
		} catch (Throwable t) {
			return pipeline;
		}
	}
}
