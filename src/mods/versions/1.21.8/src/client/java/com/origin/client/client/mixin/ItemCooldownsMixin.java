package com.origin.client.client.mixin;

import com.origin.client.client.hud.CooldownsHud;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemCooldowns;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Cooldowns HUD: learns which items went on cooldown, and for how long.
//
// ItemCooldowns keeps its entries in a private map, so the HUD can't list them;
// vanilla only answers "how far through is this stack". PER-VERSION (1.21.2+,
// source-verified on 1.21.4 and 1.21.8): cooldowns are keyed by a cooldown GROUP.
//   addCooldown(ItemStack, int)          -- the client's own prediction (pearl,
//                                           chorus fruit, shield disable); it
//                                           resolves the group and calls...
//   addCooldown(ResourceLocation, int)   -- ...the single entry point every
//                                           cooldown lands in (the server's
//                                           cooldown packet calls it directly).
// The first hook only remembers which stack to draw for the group; the second
// starts the dial. Read-only -- both calls proceed. CooldownsHud ignores every
// ItemCooldowns that isn't the local player's (the singleplayer server's copies
// pass through here too).
@Mixin(ItemCooldowns.class)
public class ItemCooldownsMixin {
	@Inject(method = "addCooldown(Lnet/minecraft/world/item/ItemStack;I)V", at = @At("HEAD"))
	private void originclient$trackStack(ItemStack stack, int ticks, CallbackInfo ci) {
		try {
			CooldownsHud.onStackCooldown((ItemCooldowns) (Object) this, stack);
		} catch (Throwable ignored) {
			// a HUD dial must never interfere with a cooldown
		}
	}

	@Inject(method = "addCooldown(Lnet/minecraft/resources/ResourceLocation;I)V", at = @At("HEAD"))
	private void originclient$trackCooldown(ResourceLocation group, int ticks, CallbackInfo ci) {
		try {
			CooldownsHud.onCooldownAdded((ItemCooldowns) (Object) this, group, ticks);
		} catch (Throwable ignored) {
			// a HUD dial must never interfere with a cooldown
		}
	}
}
