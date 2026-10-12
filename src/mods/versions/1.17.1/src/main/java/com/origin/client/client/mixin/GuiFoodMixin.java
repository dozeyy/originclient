package com.origin.client.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.gui.Gfx;
import com.origin.client.client.mods.SaturationOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Saturation mod. 1.17.1 has no Gui.renderFood (that split is 1.20+): the 10
// hunger shanks are drawn inline in Gui.renderPlayerHealth(PoseStack), right-to-
// left from screenWidth/2 + 91 on row screenHeight - 39, and only when the
// player isn't riding a mount that shows its own health (decompiled 1.16.4 and
// 1.18.1 are identical here, so 1.17.1 is too). Drawing at the method's TAIL with
// those same numbers puts the overlay on exactly vanilla's shank positions.
@Mixin(Gui.class)
public class GuiFoodMixin {

	@Inject(method = "renderPlayerHealth", at = @At("TAIL"))
	private void originclient$saturationOverlay(PoseStack poseStack, CallbackInfo ci) {
		try {
			Minecraft mc = Minecraft.getInstance();
			if (!(mc.getCameraEntity() instanceof Player player)) {
				return;
			}
			// vanilla skips the hunger row while a mount's hearts take its place
			Entity vehicle = player.getVehicle();
			if (vehicle instanceof LivingEntity mount && mount.showVehicleHealth()
					&& (int) (mount.getMaxHealth() + 0.5F) / 2 > 0) {
				return;
			}
			Gfx g = new Gfx(poseStack);
			SaturationOverlay.render(g, player, g.guiHeight() - 39, g.guiWidth() / 2 + 91);
		} catch (Throwable t) {
			// the hunger bar must never go down over an overlay
		}
	}
}
