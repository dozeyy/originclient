package com.origin.client.client.mixin;

import com.origin.client.client.render.OriginScreenRenderer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.LoadingOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Draws the custom Origin loading screen (charcoal + orbital rings + grain +
// wordmark + progress bar) OVER vanilla's, injected at the TAIL of render().
//
// TAIL, non-cancelling, is deliberate: vanilla's render() also drives the
// reload-progress smoothing, the fade timing, and the setOverlay(null)
// transition that leaves the loading screen. Injecting at HEAD with ci.cancel()
// skips all of that and hangs the game on the loading screen (a prior attempt
// hit exactly that -- MEMORY.md). At TAIL, vanilla has already run fully this
// frame; we just paint an opaque scene on top, so the completion logic is
// untouched and a hang is impossible.
//
// currentProgress (private float, already smoothed 0..1) was confirmed by
// javap against the mapped 1.21.1 client jar -- it's read here for a real
// progress bar rather than a time-based fake.
@Mixin(LoadingOverlay.class)
public class LoadingOverlayMixin {
	@Shadow
	private float currentProgress;

	// 26.2: Overlay.render -> extractRenderState (retained-mode). TAIL/non-cancel
	// keeps vanilla's fade + setOverlay(null) transition intact (see class note).
	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void originclient$drawOriginLoading(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		OriginScreenRenderer.renderLoading(guiGraphics, this.currentProgress);
	}
}
