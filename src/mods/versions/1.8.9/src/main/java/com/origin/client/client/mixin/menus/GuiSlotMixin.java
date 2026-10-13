package com.origin.client.client.mixin.menus;

import com.origin.client.client.render.OriginScreenRenderer;
import net.minecraft.client.gui.GuiSlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Option / world / server / resource-pack / language lists tile their OWN dirt
// background -- behind the rows and as top/bottom strips over the title/button
// areas. Together those cover the whole screen, painting over the Origin
// backdrop, so the list should sit transparently on it instead (1.16.5's
// AbstractSelectionListMixin flips the list's renderBackground /
// renderTopAndBottom flags for the same reason).
//
// 1.8.9: GuiSlot has no such flags -- drawScreen calls drawContainerBackground
// (the row backdrop; a FORGE-added method, so its name is never obfuscated:
// remap = false) and overlayBackground(top...) / overlayBackground(bottom...)
// (the strips; vanilla, remapped). Both are cancelled at HEAD. Like 1.16.5 this
// runs in-world too: there the screen's own drawDefaultBackground (vanilla's
// darkened world, or Menu Blur) shows through, instead of dirt over the game.
// The list's 4 px edge fades, selection boxes and scrollbar still draw.
// Fail-soft: gated only on isActive() -- a broken Origin renderer leaves
// vanilla's dirt untouched.
@Mixin(GuiSlot.class)
public abstract class GuiSlotMixin {

	@Inject(method = "drawContainerBackground", at = @At("HEAD"), cancellable = true, remap = false)
	private void originclient$transparentRows(CallbackInfo ci) {
		if (OriginScreenRenderer.isActive()) {
			ci.cancel();
		}
	}

	@Inject(method = "overlayBackground", at = @At("HEAD"), cancellable = true)
	private void originclient$transparentStrips(int startY, int endY, int startAlpha, int endAlpha, CallbackInfo ci) {
		if (OriginScreenRenderer.isActive()) {
			ci.cancel();
		}
	}
}
