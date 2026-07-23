package com.origin.client.client.mixin;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Origin always owns the player-list draw: suppress vanilla's so it can never double
// up with our overlay, which GuiHudMixin draws from the HUD pass on our own visibility
// schedule (OriginClientMod.tabListVisible — shows even on a solo local server, which
// vanilla refuses to). With the Tab Editor mod OFF the overlay renders a plain,
// vanilla-like default (OriginTabList customize=false), so the tab still works and
// looks normal; with it ON, the options apply. Import kept for the tabListVisible gate
// living in GuiHudMixin, not here.
@Mixin(PlayerTabOverlay.class)
public class PlayerTabOverlayMixin {

	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void originclient$suppressVanilla(GuiGraphics guiGraphics, int width, Scoreboard scoreboard,
											  Objective objective, CallbackInfo ci) {
		ci.cancel();
	}
}
