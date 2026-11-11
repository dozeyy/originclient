package com.origin.client.client.mixin;

import com.origin.client.client.OriginDevAd;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Dev-only ad capture: for the keyed HUD clip, paint one flat key colour over the world before the HUD draws, so
 * every Origin HUD module (and toast) lands on a background the edit can key out exactly (no module backgrounds).
 */
@Mixin(Gui.class)
public abstract class DevAdGuiMixin {
	@Inject(method = "render", at = @At("HEAD"))
	private void originad$keyBackground(GuiGraphics g, DeltaTracker delta, CallbackInfo ci) {
		if (OriginDevAd.ON && OriginDevAd.keyBackground != 0) {
			g.fill(0, 0, g.guiWidth(), g.guiHeight(), OriginDevAd.keyBackground);
		}
	}
}
