package com.origin.client.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.mods.HypixelBedwars;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;

// Hypixel Bed Wars: the vanilla-HUD half of the mod. Every hook asks
// HypixelBedwars first, which says no unless the mod is on and we're in a Bed
// Wars game on Hypixel -- everywhere else these are no-ops.
//
// Pre-1.20.5 Gui shapes (decompiled 1.19.4):
//  - there is no renderArmor / renderFood: Gui.renderPlayerHealth(PoseStack)
//    draws the armour row and the food row inline, every cell one 7-arg blit
//    from textures/gui/icons.png -- armour cells at v = 9, food cells (and their
//    Hunger-effect variants) at v = 27, air bubbles at v = 18. So the blits of
//    that one method are redirected and an armour / food cell is simply skipped
//    while Hide Armor / Hide Food Bar applies; every other blit draws as vanilla.
//    (1.19.4's 7-arg blit is GuiComponent's public static.) The Saturation
//    overlay checks hideFood itself (GuiFoodMixin), as on 1.21.1.
//  - private void renderHeart(PoseStack, Gui.HeartType, int x, int y, int v,
//    boolean blinking, boolean half): renderHearts passes v = 9 * 5 for a
//    hardcore world, 0 otherwise -- the third int.
//  - public void setTitle(Component) / setSubtitle(Component), fed by the title
//    packets; `subtitle` is the private field the title block reads.
@Mixin(Gui.class)
public class HypixelBedwarsGuiMixin {
	@Shadow
	private Component subtitle;

	@Redirect(method = "renderPlayerHealth",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/gui/Gui;blit(Lcom/mojang/blaze3d/vertex/PoseStack;IIIIII)V"))
	private void originclient$bwHideBars(PoseStack poseStack, int x, int y, int u, int v, int w, int h) {
		try {
			if ((v == 9 && HypixelBedwars.hideArmor()) || (v == 27 && HypixelBedwars.hideFood())) {
				return;
			}
		} catch (Throwable t) {
			// draw it
		}
		GuiComponent.blit(poseStack, x, y, u, v, w, h);
	}

	@ModifyVariable(method = "renderHeart", at = @At("HEAD"), argsOnly = true, ordinal = 2)
	private int originclient$bwHardcoreHearts(int v) {
		try {
			return HypixelBedwars.hardcoreHearts() ? 9 * 5 : v;
		} catch (Throwable t) {
			return v;
		}
	}

	@ModifyVariable(method = "setTitle", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private Component originclient$bwTrapTitle(Component title) {
		try {
			Component out = HypixelBedwars.onTitle(title);
			if (out != title && subtitle != null) {
				// Hypixel sends the subtitle first; recolour the one already waiting.
				subtitle = HypixelBedwars.onSubtitle(subtitle);
			}
			return out;
		} catch (Throwable t) {
			return title;
		}
	}

	@ModifyVariable(method = "setSubtitle", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private Component originclient$bwTrapSubtitle(Component subtitle) {
		try {
			return HypixelBedwars.onSubtitle(subtitle);
		} catch (Throwable t) {
			return subtitle;
		}
	}
}
