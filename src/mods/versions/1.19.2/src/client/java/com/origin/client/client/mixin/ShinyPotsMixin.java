package com.origin.client.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.mods.ShinyPots;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Shinypots: hooks the private tryRenderGuiItem that every GUI slot item draw
// funnels into (decompiled 1.19.1 (the 1.19.2 code base) ItemRenderer: the 6-arg
// tryRenderGuiItem(LivingEntity, ItemStack, x, y, seed, guiOffset), reached from
// every renderAndDecorateItem / renderAndDecorateFakeItem -- hotbar,
// inventories, HUD). HEAD = the "behind" shine, TAIL = the normal one;
// ShinyPots decides which (or neither) applies. Before 1.19.4 GUI items have no
// PoseStack: they position through RenderSystem's model-view stack and sit at
// z = blitOffset (+50 +100 inside), so the shine gets an identity pose and
// blitOffset as its z base -- the shader applies the model-view either way.
// Fail-soft: a shine error never breaks an item draw.
@Mixin(ItemRenderer.class)
public class ShinyPotsMixin {
	@Shadow
	public float blitOffset;

	@Inject(method = "tryRenderGuiItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;IIII)V",
			at = @At("HEAD"))
	private void originclient$shineBehind(LivingEntity entity, ItemStack stack, int x, int y, int seed, int guiOffset,
										  CallbackInfo ci) {
		try {
			ShinyPots.draw(new PoseStack(), blitOffset, stack, x, y, true);
		} catch (Throwable ignored) {
		}
	}

	@Inject(method = "tryRenderGuiItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;IIII)V",
			at = @At("TAIL"))
	private void originclient$shineOver(LivingEntity entity, ItemStack stack, int x, int y, int seed, int guiOffset,
										CallbackInfo ci) {
		try {
			ShinyPots.draw(new PoseStack(), blitOffset, stack, x, y, false);
		} catch (Throwable ignored) {
		}
	}
}
