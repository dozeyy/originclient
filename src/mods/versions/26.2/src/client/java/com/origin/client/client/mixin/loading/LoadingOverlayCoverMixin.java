package com.origin.client.client.mixin.loading;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.origin.client.client.render.OriginScreenRenderer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Vanilla's Mojang logo and progress bar are not drawn while Origin's loading
// picture covers them (1.21.1's LoadingOverlayCoverMixin, B-30). LoadingOverlayMixin
// draws that picture at TAIL, opaque (flat color.bg over the whole window, LoadingLook),
// so vanilla's logo and bar were recorded, uploaded and drawn every loading frame only
// to be covered. On 1.21.1 that was most of the first overlay frame (CI frame log
// 2026-10-25: logo 620 ms with the texture's first load, bar 282 ms, of 1052 ms).
//
// Only the drawing is skipped: vanilla's background clear, its fade alpha, the reload's
// completion step and the overlay hand-off all run untouched. The test is
// OriginScreenRenderer.coversLoadingOverlay(): exactly when the TAIL draw paints
// (Origin rendering not latched off, Main Menu Style = Origin, LoadingOverlayMixin
// applied). Otherwise vanilla draws its own logo and bar as always.
//
// 26.x targets (javap): the logo halves are the two
// GuiGraphicsExtractor.blit(RenderPipeline, Identifier, IIFFIIIIIII) calls in
// extractRenderState (26.3 moves RenderPipeline to com.mojang.renderpearl.api.pipeline);
// the bar is the private extractProgressBar(GuiGraphicsExtractor, IIIIF), skipped at its
// HEAD (its only caller is extractRenderState; the handler binds by name). In the
// isolated required:false loading config with defaultRequire 0: a target that moves
// just leaves vanilla drawing it under Origin's picture again, nothing breaks.
@Mixin(LoadingOverlay.class)
public abstract class LoadingOverlayCoverMixin {
	@WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blit(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIIIIII)V"))
	private void originclient$coveredLogo(GuiGraphicsExtractor g, RenderPipeline pipeline, Identifier id, int x, int y,
			float u, float v, int w, int h, int uw, int vh, int tw, int th, int color, Operation<Void> original) {
		if (!OriginScreenRenderer.coversLoadingOverlay()) {
			original.call(g, pipeline, id, x, y, u, v, w, h, uw, vh, tw, th, color);
		}
	}

	@Inject(method = "extractProgressBar", at = @At("HEAD"), cancellable = true)
	private void originclient$coveredBar(CallbackInfo ci) {
		if (OriginScreenRenderer.coversLoadingOverlay()) {
			ci.cancel();
		}
	}
}
