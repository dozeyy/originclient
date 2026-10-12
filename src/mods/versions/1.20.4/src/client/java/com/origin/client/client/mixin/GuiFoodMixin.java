package com.origin.client.client.mixin;

import com.origin.client.client.mods.SaturationOverlay;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Saturation mod. Before 1.20.5 there is no Gui.renderFood: the 10 hunger
// shanks are drawn inline in the private Gui.renderPlayerHealth(GuiGraphics),
// right-to-left from screenWidth/2 + 91 on row screenHeight - 39, and only when
// getVehicleMaxHearts(getPlayerVehicleWithHealth()) == 0 (no ridden mount
// showing its own health) -- verified against decompiled 1.20.1 / 1.20.2 /
// 1.20.4 Gui. Drawing at the method's TAIL with those same inputs puts the
// overlay on exactly vanilla's shank positions; the only thing drawn after the
// food row in that method is the air-bubble row, one line higher, so the
// layering matches 1.21.1's renderFood-TAIL hook.
@Mixin(Gui.class)
public abstract class GuiFoodMixin {
	@Shadow
	private int screenWidth;
	@Shadow
	private int screenHeight;

	@Shadow
	protected abstract Player getCameraPlayer();

	@Shadow
	protected abstract LivingEntity getPlayerVehicleWithHealth();

	@Shadow
	protected abstract int getVehicleMaxHearts(LivingEntity vehicle);

	@Inject(method = "renderPlayerHealth(Lnet/minecraft/client/gui/GuiGraphics;)V", at = @At("TAIL"), require = 0)
	private void originclient$saturationOverlay(GuiGraphics guiGraphics, CallbackInfo ci) {
		try {
			Player player = getCameraPlayer();
			if (player == null || getVehicleMaxHearts(getPlayerVehicleWithHealth()) != 0) {
				return;   // vanilla drew no hunger row this frame
			}
			if (com.origin.client.client.mods.HypixelBedwars.hideFood()) {
				return;   // Hypixel Bed Wars hid the food row (HypixelBedwarsGuiMixin): nothing to draw over
			}
			SaturationOverlay.render(guiGraphics, player, screenHeight - 39, screenWidth / 2 + 91);
		} catch (Throwable t) {
			// the hunger bar must never go down over an overlay
		}
	}
}
