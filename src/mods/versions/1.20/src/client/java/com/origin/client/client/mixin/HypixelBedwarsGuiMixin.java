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
// 1.20 / 1.20.1 Gui shapes (verified against decompiled 1.20 client source):
//  - there is no renderArmor / renderFood yet: private renderPlayerHealth(GuiGraphics)
//    draws the armour row and the food row inline, each as a run of
//    GuiGraphics.blit(ResourceLocation, int x, int y, int u, int v, int w, int h)
//    calls on textures/gui/icons.png (no GUI sprites before 1.20.2) inside
//    its own profiler section -- push("armor") ... popPush("health") and
//    popPush("food") ... popPush("air"). Redirecting just the blit calls
//    in those two slices skips drawing that row and nothing else: the hearts,
//    the air bubbles and every row's position stay exactly where vanilla puts
//    them -- the same result as 1.21.1 cancelling renderArmor / renderFood.
//    (GuiFoodMixin's Saturation overlay asks HypixelBedwars.hideFood too.)
//  - private void renderHeart(GuiGraphics, Gui.HeartType, int x, int y, int v,
//        boolean blinking, boolean half) -- every heart renderHearts draws goes
//    through it. Before the 1.20.2 sprites, hardcore is not a flag but the icons.png
//    row: renderHearts passes v = 9 * (hardcore ? 5 : 0), i.e. 0 or 45, and v is
//    the third int argument.
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
					target = "Lnet/minecraft/client/gui/GuiGraphics;blit(Lnet/minecraft/resources/ResourceLocation;IIIIII)V"),
			require = 0)
	private void originclient$bwHideArmor(GuiGraphics guiGraphics, ResourceLocation texture, int x, int y, int u, int v,
										int w, int h) {
		if (!originclient$hide(true)) {
			guiGraphics.blit(texture, x, y, u, v, w, h);
		}
	}

	@Redirect(method = "renderPlayerHealth(Lnet/minecraft/client/gui/GuiGraphics;)V",
			slice = @Slice(from = @At(value = "CONSTANT", args = "stringValue=food"),
					to = @At(value = "CONSTANT", args = "stringValue=air")),
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/gui/GuiGraphics;blit(Lnet/minecraft/resources/ResourceLocation;IIIIII)V"),
			require = 0)
	private void originclient$bwHideFood(GuiGraphics guiGraphics, ResourceLocation texture, int x, int y, int u, int v,
										int w, int h) {
		if (!originclient$hide(false)) {
			guiGraphics.blit(texture, x, y, u, v, w, h);
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
