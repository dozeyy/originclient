package com.origin.client.client.mixin;

import com.origin.client.client.mods.ShinyPots;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Shinypots: hooks the one private renderItem that every GUI item draw funnels
// into -- hotbar, inventories, tooltips, HUD. PER-VERSION (1.21.6-1.21.8,
// verified against the decompiled 1.21.6 and 1.21.8 GuiGraphics): it is
// renderItem(LivingEntity, Level, ItemStack, x, y, seed) -- the guiOffset
// argument 1.21.1 had is gone -- and it SUBMITS the item to the deferred GUI
// (GuiItemRenderState) rather than drawing it, so HEAD/TAIL decide layering by
// submission order instead of z.
// HEAD = the "behind" shine, TAIL = the normal one; ShinyPots decides which
// (or neither) applies. Fail-soft: a shine error never breaks an item draw.
@Mixin(GuiGraphics.class)
public class ShinyPotsMixin {
	@Inject(method = "renderItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;III)V",
			at = @At("HEAD"))
	private void originclient$shineBehind(LivingEntity entity, Level level, ItemStack stack, int x, int y, int seed,
										  CallbackInfo ci) {
		try {
			ShinyPots.draw((GuiGraphics) (Object) this, stack, x, y, true);
		} catch (Throwable ignored) {
		}
	}

	@Inject(method = "renderItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;III)V",
			at = @At("TAIL"))
	private void originclient$shineOver(LivingEntity entity, Level level, ItemStack stack, int x, int y, int seed,
										CallbackInfo ci) {
		try {
			ShinyPots.draw((GuiGraphics) (Object) this, stack, x, y, false);
		} catch (Throwable ignored) {
		}
	}
}
