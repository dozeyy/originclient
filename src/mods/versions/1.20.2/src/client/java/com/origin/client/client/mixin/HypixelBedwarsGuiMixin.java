package com.origin.client.client.mixin;

import com.origin.client.client.mods.HypixelBedwars;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Slice;

// Hypixel Bed Wars: the vanilla-HUD half of the mod. Every hook asks
// HypixelBedwars first, which says no unless the mod is on and we're in a Bed
// Wars game on Hypixel -- everywhere else these are no-ops.
//
// Pre-1.20.5 Gui shapes (verified against decompiled 1.20.2 client source):
//  - there is no renderArmor / renderFood yet: private renderPlayerHealth(GuiGraphics)
//    draws the armour row and the food row inline, each as a run of
//    GuiGraphics.blitSprite(ResourceLocation, int, int, int, int) calls inside
//    its own profiler section -- push("armor") ... popPush("health") and
//    popPush("food") ... popPush("air"). Redirecting just the blitSprite calls
//    in those two slices skips drawing that row and nothing else: the hearts,
//    the air bubbles and every row's position stay exactly where vanilla puts
//    them -- the same result as 1.21.1 cancelling renderArmor / renderFood.
//    (GuiFoodMixin's Saturation overlay asks HypixelBedwars.hideFood too.)
//  - private void renderHeart(GuiGraphics, Gui.HeartType, int x, int y,
//        boolean hardcore, boolean blinking, boolean half)  -- every heart sprite
//    renderHearts draws goes through it, `hardcore` being the first boolean.
//  - public void setTitle(Component) / setSubtitle(Component), fed by the
//    title packets; `subtitle` is the private field the title layer reads.
@Mixin(Gui.class)
public class HypixelBedwarsGuiMixin {
	@Shadow
	private Component subtitle;

	@Redirect(method = "renderPlayerHealth(Lnet/minecraft/client/gui/GuiGraphics;)V",
			slice = @Slice(from = @At(value = "CONSTANT", args = "stringValue=armor"),
					to = @At(value = "CONSTANT", args = "stringValue=health")),
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/gui/GuiGraphics;blitSprite(Lnet/minecraft/resources/ResourceLocation;IIII)V"),
			require = 0)
	private void originclient$bwHideArmor(GuiGraphics guiGraphics, ResourceLocation sprite, int x, int y, int w, int h) {
		if (!originclient$hide(true)) {
			guiGraphics.blitSprite(sprite, x, y, w, h);
		}
	}

	@Redirect(method = "renderPlayerHealth(Lnet/minecraft/client/gui/GuiGraphics;)V",
			slice = @Slice(from = @At(value = "CONSTANT", args = "stringValue=food"),
					to = @At(value = "CONSTANT", args = "stringValue=air")),
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/gui/GuiGraphics;blitSprite(Lnet/minecraft/resources/ResourceLocation;IIII)V"),
			require = 0)
	private void originclient$bwHideFood(GuiGraphics guiGraphics, ResourceLocation sprite, int x, int y, int w, int h) {
		if (!originclient$hide(false)) {
			guiGraphics.blitSprite(sprite, x, y, w, h);
		}
	}

	@Unique
	private static boolean originclient$hide(boolean armor) {
		try {
			return armor ? HypixelBedwars.hideArmor() : HypixelBedwars.hideFood();
		} catch (Throwable t) {
			return false;   // leave the row to vanilla
		}
	}

	@ModifyVariable(method = "renderHeart", at = @At("HEAD"), argsOnly = true, ordinal = 0)
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
