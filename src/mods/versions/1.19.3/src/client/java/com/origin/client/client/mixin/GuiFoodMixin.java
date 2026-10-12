package com.origin.client.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.gui.Gfx;
import com.origin.client.client.mods.HypixelBedwars;
import com.origin.client.client.mods.SaturationOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Saturation mod. Before 1.20.5 there is no Gui.renderFood: the hunger shanks
// are drawn inline by Gui.renderPlayerHealth(PoseStack) (verified against the
// decompiled 1.19.4 / 1.19.3 / 1.19.1 / 1.18.1 Gui), right-to-left from
// screenWidth/2 + 91 on row screenHeight - 39, and only when the camera player's
// mount shows no health of its own (getVehicleMaxHearts == 0). Drawing at the
// method's TAIL with that same geometry and the same mount rule puts the overlay
// exactly on vanilla's shanks. Hypixel Bed Wars' Hide Food Bar hides the shanks
// (HypixelBedwarsGuiMixin), so the overlay is skipped with them.
@Mixin(Gui.class)
public class GuiFoodMixin {

	@Inject(method = "renderPlayerHealth", at = @At("TAIL"))
	private void originclient$saturationOverlay(PoseStack poseStack, CallbackInfo ci) {
		try {
			Minecraft mc = Minecraft.getInstance();
			if (!(mc.getCameraEntity() instanceof Player player) || HypixelBedwars.hideFood()) {
				return;
			}
			// Gui.getVehicleMaxHearts: a living mount that shows its health replaces the food row.
			if (player.getVehicle() instanceof LivingEntity mount && mount.showVehicleHealth()
					&& (int) (mount.getMaxHealth() + 0.5F) / 2 > 0) {
				return;
			}
			int sw = mc.getWindow().getGuiScaledWidth(), sh = mc.getWindow().getGuiScaledHeight();
			SaturationOverlay.render(new Gfx(poseStack), player, sh - 39, sw / 2 + 91);
		} catch (Throwable t) {
			// the hunger bar must never go down over an overlay
		}
	}
}
