package com.origin.client.client.mixin;

import com.origin.client.client.mods.Mods;
import com.origin.client.client.render.OriginCrosshair;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Crosshair mod. Gui.renderCrosshair(GuiGraphics, DeltaTracker) is the crosshair
// part of Gui.render on 1.21.10 too (verified against decompiled 1.21.10 Gui). While the
// mod is on, the whole method is replaced: OriginCrosshair draws the custom
// shape -- also in third person when Show In F5 is on, which vanilla never does
// -- plus vanilla's attack indicator, which lives in this same method. The one
// view left to vanilla is the F3 3D-axes crosshair (render() returns false).
// Fail-soft: any error falls through to the vanilla crosshair.
@Mixin(Gui.class)
public class GuiCrosshairMixin {

	@Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true)
	private void originclient$customCrosshair(GuiGraphics guiGraphics, DeltaTracker deltaTracker, CallbackInfo ci) {
		if (!Mods.on("crosshair")) {
			return;
		}
		try {
			if (OriginCrosshair.render(guiGraphics)) {
				ci.cancel();
			}
		} catch (Throwable t) {
			// leave vanilla's crosshair in place
		}
	}
}
