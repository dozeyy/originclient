package com.origin.client.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.mods.ShinyPots;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Shinypots: hooks the private tryRenderGuiItem that every GUI slot item draw
// funnels into (decompiled 1.19.4 ItemRenderer: the 8-arg
// tryRenderGuiItem(PoseStack, LivingEntity, Level, ItemStack, x, y, seed,
// guiOffset), reached from every renderAndDecorateItem / renderAndDecorateFakeItem
// -- hotbar, inventories, HUD). HEAD = the "behind" shine, TAIL = the normal one;
// ShinyPots decides which (or neither) applies. The pose is the item's own
// (1.19.4 passes it through), so zBase is 0. Fail-soft: a shine error never
// breaks an item draw.
@Mixin(ItemRenderer.class)
public class ShinyPotsMixin {
	@Inject(method = "tryRenderGuiItem(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;IIII)V", at = @At("HEAD"))
	private void originclient$shineBehind(PoseStack pose, LivingEntity entity, Level level, ItemStack stack, int x, int y,
										  int seed, int guiOffset, CallbackInfo ci) {
		try {
			ShinyPots.draw(pose, 0f, stack, x, y, true);
		} catch (Throwable ignored) {
		}
	}

	@Inject(method = "tryRenderGuiItem(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;IIII)V", at = @At("TAIL"))
	private void originclient$shineOver(PoseStack pose, LivingEntity entity, Level level, ItemStack stack, int x, int y,
										int seed, int guiOffset, CallbackInfo ci) {
		try {
			ShinyPots.draw(pose, 0f, stack, x, y, false);
		} catch (Throwable ignored) {
		}
	}
}
