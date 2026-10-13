package com.origin.client.client.mixin.world;

import com.origin.client.client.mods.ShinyPots;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Shinypots: hooks the one GUI item draw every GUI item funnels into on 1.8.9:
// RenderItem.renderItemIntoGUI(ItemStack, x, y), reached from
// renderItemAndEffectIntoGUI (containers, the hotbar, creative tabs) alike. It
// positions through the GLOBAL model-view stack, which is still the GUI's at
// HEAD and again at RETURN, so the shine draws in the same GUI space. HEAD = the
// "behind" shine, RETURN = the normal one. RenderItem is OptiFine-replaced, hence
// HEAD/RETURN only. Fail-soft: a shine error never breaks an item draw.
@Mixin(RenderItem.class)
public abstract class ShinyPotsMixin {
	@Inject(method = "renderItemIntoGUI", at = @At("HEAD"))
	private void originclient$shineBehind(ItemStack stack, int x, int y, CallbackInfo ci) {
		try {
			ShinyPots.draw(stack, x, y, ((RenderItem) (Object) this).zLevel, true);
		} catch (Throwable ignored) {
		}
	}

	@Inject(method = "renderItemIntoGUI", at = @At("RETURN"))
	private void originclient$shineOver(ItemStack stack, int x, int y, CallbackInfo ci) {
		try {
			ShinyPots.draw(stack, x, y, ((RenderItem) (Object) this).zLevel, false);
		} catch (Throwable ignored) {
		}
	}
}
