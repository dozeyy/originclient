package com.origin.client.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.mods.HypixelBedwars;
import net.minecraft.client.gui.Gui;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.LevelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Hypixel Bed Wars: the vanilla-HUD half of the mod. Every hook asks
// HypixelBedwars first, which says no unless the mod is on and we're in a Bed
// Wars game on Hypixel -- everywhere else these are no-ops.
//
// 1.16.5 has no renderArmor / renderFood / renderHearts (those splits are 1.17+
// and 1.20+): armour, hearts, food and air are all drawn inline in
// Gui.renderPlayerHealth(PoseStack) (decompiled 1.16.4), so each is reached
// through that method's own calls:
//  - Hide Armor Bar: the armour row is drawn only while player.getArmorValue()
//    > 0 -- that one read answers 0 while hiding.
//  - Hide Food Bar: the shanks are the blits between the profiler's "food" and
//    "air" sections; a flag raised at popPush("food") and dropped at
//    popPush("air") skips exactly those blits (armour, air untouched). The
//    Saturation overlay (GuiFoodMixin, at the TAIL) checks hideFood() too.
//  - Hardcore Hearts: each heart picks the hardcore row from
//    level.getLevelData().isHardcore(), read inline in renderPlayerHealth --
//    that read answers true.
//  - Titles: 1.16.5 has ONE setTitles(title, subtitle, fadeIn, stay, fadeOut)
//    (the title packet sets either the title or the subtitle per call; the
//    split setTitle / setSubtitle are 1.17+). Both arguments are passed through
//    HypixelBedwars; `subtitle` is the private field the title block reads.
@Mixin(Gui.class)
public class HypixelBedwarsGuiMixin {
	@Shadow
	private Component subtitle;

	@Unique
	private boolean originclient$bwSkipFood = false;

	@Redirect(method = "renderPlayerHealth",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;getArmorValue()I"))
	private int originclient$bwHideArmor(Player player) {
		try {
			if (HypixelBedwars.hideArmor()) {
				return 0;
			}
		} catch (Throwable t) {
			// leave the armour bar to vanilla
		}
		return player.getArmorValue();
	}

	@Inject(method = "renderPlayerHealth",
			at = @At(value = "INVOKE_STRING", target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V",
					args = "ldc=food"))
	private void originclient$bwFoodStart(PoseStack poseStack, CallbackInfo ci) {
		try {
			originclient$bwSkipFood = HypixelBedwars.hideFood();
		} catch (Throwable t) {
			originclient$bwSkipFood = false;
		}
	}

	@Inject(method = "renderPlayerHealth",
			at = @At(value = "INVOKE_STRING", target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V",
					args = "ldc=air"))
	private void originclient$bwFoodEnd(PoseStack poseStack, CallbackInfo ci) {
		originclient$bwSkipFood = false;
	}

	@Redirect(method = "renderPlayerHealth",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Gui;blit(Lcom/mojang/blaze3d/vertex/PoseStack;IIIIII)V"))
	private void originclient$bwFoodBlit(Gui self, PoseStack poseStack, int x, int y, int u, int v, int w, int h) {
		if (!originclient$bwSkipFood) {
			self.blit(poseStack, x, y, u, v, w, h);
		}
	}

	@Redirect(method = "renderPlayerHealth",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/storage/LevelData;isHardcore()Z"))
	private boolean originclient$bwHardcoreHearts(LevelData data) {
		boolean hardcore = data.isHardcore();
		try {
			return hardcore || HypixelBedwars.hardcoreHearts();
		} catch (Throwable t) {
			return hardcore;
		}
	}

	@ModifyVariable(method = "setTitles", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private Component originclient$bwTrapTitle(Component title) {
		if (title == null) {
			return null;
		}
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

	@ModifyVariable(method = "setTitles", at = @At("HEAD"), argsOnly = true, ordinal = 1)
	private Component originclient$bwTrapSubtitle(Component subtitle) {
		if (subtitle == null) {
			return null;
		}
		try {
			return HypixelBedwars.onSubtitle(subtitle);
		} catch (Throwable t) {
			return subtitle;
		}
	}
}
