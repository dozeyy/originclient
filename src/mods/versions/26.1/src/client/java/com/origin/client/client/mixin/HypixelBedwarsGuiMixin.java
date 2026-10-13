package com.origin.client.client.mixin;

import com.origin.client.client.mods.HypixelBedwars;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Hypixel Bed Wars: the vanilla-HUD half of the mod. Every hook asks
// HypixelBedwars first, which says no unless the mod is on and we're in a Bed
// Wars game on Hypixel -- everywhere else these are no-ops.
//
// Gui shapes (verified against decompiled 1.21.11 client source -- identical
// to 1.21.1 for every hook here):
//  - private static void renderArmor(GuiGraphicsExtractor, Player, int y, int heartRows, int rowHeight, int left)
//  - private void renderFood(GuiGraphicsExtractor, Player, int y, int right)
//  - private void renderHeart(GuiGraphicsExtractor, Gui.HeartType, int x, int y,
//        boolean hardcore, boolean blinking, boolean half)  -- every heart sprite
//    renderHearts draws goes through it, `hardcore` being the first boolean.
//  - public void setTitle(Component) / setSubtitle(Component), fed by the
//    title packets; `subtitle` is the private field the title layer reads.
// Cancelling renderFood at HEAD also skips the Saturation overlay that
// GuiFoodMixin draws at its TAIL, which is right: no food bar, nothing over it.
@Mixin(Gui.class)
public class HypixelBedwarsGuiMixin {
	@Shadow
	private Component subtitle;

	@Inject(method = "extractArmor", at = @At("HEAD"), cancellable = true)
	private static void originclient$bwHideArmor(GuiGraphicsExtractor guiGraphics, Player player, int y, int heartRows,
			int rowHeight, int left, CallbackInfo ci) {
		try {
			if (HypixelBedwars.hideArmor()) {
				ci.cancel();
			}
		} catch (Throwable t) {
			// leave the armour bar to vanilla
		}
	}

	@Inject(method = "extractFood", at = @At("HEAD"), cancellable = true)
	private void originclient$bwHideFood(GuiGraphicsExtractor guiGraphics, Player player, int y, int right, CallbackInfo ci) {
		try {
			if (HypixelBedwars.hideFood()) {
				ci.cancel();
			}
		} catch (Throwable t) {
			// leave the food bar to vanilla
		}
	}

	@ModifyVariable(method = "extractHeart", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private boolean originclient$bwHardcoreHearts(boolean hardcore) {
		try {
			return hardcore || HypixelBedwars.hardcoreHearts();
		} catch (Throwable t) {
			return hardcore;
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
