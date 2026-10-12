package com.origin.client.client.mixin;

import com.origin.client.client.mods.ShinyPots;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Shinypots: hooks the one GUI item draw every GUI item funnels into on 1.17.1
// (no GuiGraphics before 1.20): ItemRenderer.renderGuiItem(ItemStack, x, y,
// BakedModel), reached from renderAndDecorateItem / renderGuiItem / the hotbar
// alike (decompiled 1.16.4 and 1.18.1 agree). It positions through the GLOBAL
// model-view stack, which is still the GUI's at HEAD and again at TAIL, so the
// shine draws in the same GUI space. HEAD = the "behind" shine, TAIL = the
// normal one. Fail-soft: a shine error never breaks an item draw.
@Mixin(ItemRenderer.class)
public class ShinyPotsMixin {
	@Inject(method = "renderGuiItem(Lnet/minecraft/world/item/ItemStack;IILnet/minecraft/client/resources/model/BakedModel;)V",
			at = @At("HEAD"))
	private void originclient$shineBehind(ItemStack stack, int x, int y, BakedModel model, CallbackInfo ci) {
		try {
			ShinyPots.draw(stack, x, y, ((ItemRenderer) (Object) this).blitOffset, true);
		} catch (Throwable ignored) {
		}
	}

	@Inject(method = "renderGuiItem(Lnet/minecraft/world/item/ItemStack;IILnet/minecraft/client/resources/model/BakedModel;)V",
			at = @At("TAIL"))
	private void originclient$shineOver(ItemStack stack, int x, int y, BakedModel model, CallbackInfo ci) {
		try {
			ShinyPots.draw(stack, x, y, ((ItemRenderer) (Object) this).blitOffset, false);
		} catch (Throwable ignored) {
		}
	}
}
