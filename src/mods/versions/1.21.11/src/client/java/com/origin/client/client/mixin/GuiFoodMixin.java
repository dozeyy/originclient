package com.origin.client.client.mixin;

import com.origin.client.client.mods.SaturationOverlay;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Saturation mod. In 1.21.11 (as in 1.21.1), Gui.renderFood(GuiGraphics, Player, int y, int right)
// draws the 10 hunger shanks right-to-left from `right` on row `y` (verified
// against decompiled 1.21.11 Gui; it is only called when no ridden mount shows
// its own health). Drawing at its TAIL puts the overlay on exactly vanilla's
// shank positions with no layout of our own to drift out of sync.
@Mixin(Gui.class)
public class GuiFoodMixin {

	@Inject(method = "renderFood", at = @At("TAIL"))
	private void originclient$saturationOverlay(GuiGraphics guiGraphics, Player player, int y, int right, CallbackInfo ci) {
		try {
			SaturationOverlay.render(guiGraphics, player, y, right);
		} catch (Throwable t) {
			// the hunger bar must never go down over an overlay
		}
	}
}
