package com.origin.client.client.mixin.loading;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.origin.client.client.render.OriginScreenRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// B-30: vanilla's Mojang logo and progress bar are not drawn while Origin's loading
// picture covers them. LoadingOverlayMixin draws that picture at TAIL, opaque
// (flat color.bg over the whole window, LoadingLook), so every pixel of vanilla's
// logo and bar was painted and then covered, every loading frame. On the first one
// that was most of the frame: CI frame log 2026-10-25 (llvmpipe), the two logo
// blits 620 ms (the logo texture's first load plus a first-use blend variant) and
// the bar 282 ms, of a 1052 ms overlay draw.
//
// Only the drawing is skipped: vanilla's background clear, its fade alpha, the
// reload's completion step and setOverlay all run untouched. The test is
// OriginScreenRenderer.coversLoadingOverlay(): exactly the condition under which the
// TAIL draw runs (Origin rendering not latched off, Main Menu Style = Origin). When
// it is false (vanilla mode, or Origin's picture failed and latched off) vanilla
// draws its own logo and bar as always, so the fail-soft net is unchanged.
//
// Targets (javap of the 1.21.1 LoadingOverlay.render): the two
// GuiGraphics.blit(ResourceLocation, IIII, FF, IIII) calls (the logo halves, the only
// blits in render) and drawProgressBar(GuiGraphics, IIII, F). If a target moves,
// that wrap is skipped (required:false config, defaultRequire 0) and vanilla simply
// draws it under Origin's picture again, the cost coming back but nothing breaking.
@Mixin(LoadingOverlay.class)
public abstract class LoadingOverlayCoverMixin {
	@WrapOperation(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/GuiGraphics;blit(Lnet/minecraft/resources/ResourceLocation;IIIIFFIIII)V"))
	private void originclient$coveredLogo(GuiGraphics g, ResourceLocation id, int x, int y, int w, int h, float u, float v,
			int uw, int vh, int tw, int th, Operation<Void> original) {
		if (!OriginScreenRenderer.coversLoadingOverlay()) {
			original.call(g, id, x, y, w, h, u, v, uw, vh, tw, th);
		}
	}

	@WrapOperation(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/screens/LoadingOverlay;drawProgressBar(Lnet/minecraft/client/gui/GuiGraphics;IIIIF)V"))
	private void originclient$coveredBar(LoadingOverlay self, GuiGraphics g, int x0, int y0, int x1, int y1, float alpha,
			Operation<Void> original) {
		if (!OriginScreenRenderer.coversLoadingOverlay()) {
			original.call(self, g, x0, y0, x1, y1, alpha);
		}
	}
}
