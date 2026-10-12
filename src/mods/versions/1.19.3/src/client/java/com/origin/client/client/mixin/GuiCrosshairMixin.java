package com.origin.client.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.gui.Gfx;
import com.origin.client.client.mods.Mods;
import com.origin.client.client.render.OriginCrosshair;
import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Crosshair mod. Gui.renderCrosshair(PoseStack) draws the crosshair (and the
// attack indicator) on every pre-1.20 version (verified against the decompiled
// 1.19.4 / 1.19.3 / 1.19.1 / 1.18.1 Gui; it checks first person itself). While
// the mod is on, the whole method is replaced: OriginCrosshair draws the custom
// shape -- also in third person when Show In F5 is on, which vanilla never does
// -- plus vanilla's attack indicator. The one view left to vanilla is the F3
// 3D-axes crosshair (render() returns false). Fail-soft: any error falls
// through to the vanilla crosshair.
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
