package com.origin.client.client.mixin;

import com.origin.client.client.mods.Mods;
import com.origin.client.client.render.OriginCrosshair;
import net.minecraft.client.gui.Gui;
import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.gui.Gfx;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Crosshair mod. Gui.renderCrosshair(PoseStack) is the crosshair draw on 1.16.5
// (private; same shape in decompiled 1.16.4). While the
// mod is on, the whole method is replaced: OriginCrosshair draws the custom
// shape -- also in third person when Show In F5 is on, which vanilla never does
// -- plus vanilla's attack indicator, which lives in this same method. The one
// view left to vanilla is the F3 3D-axes crosshair (render() returns false).
// Fail-soft: any error falls through to the vanilla crosshair.
@Mixin(Gui.class)
public class GuiCrosshairMixin {

	@Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true)
	private void originclient$customCrosshair(PoseStack poseStack, CallbackInfo ci) {
		if (!Mods.on("crosshair")) {
			return;
		}
		try {
			if (OriginCrosshair.render(new Gfx(poseStack))) {
				ci.cancel();
			}
		} catch (Throwable t) {
			// leave vanilla's crosshair in place
		}
	}
}
