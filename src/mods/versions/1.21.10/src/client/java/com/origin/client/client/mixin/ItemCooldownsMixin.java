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
// ItemCooldowns keeps its entries in a private map of a package-private record,
// so the HUD can't list them; vanilla only answers "how far through is stack X".
// 1.21.10 (source-verified against decompiled ItemCooldowns): every cooldown
// lands in addCooldown(ResourceLocation group, int) -- the server's cooldown packet
// calls it directly, and the client's own predictions go through
// addCooldown(ItemStack, int), which resolves the stack's group and forwards.
// The stack hook only remembers the icon; the group hook records the cooldown.
// Read-only -- both calls proceed. CooldownsHud ignores every ItemCooldowns
// that isn't the local player's (the singleplayer server's copies pass here too).
@Mixin(ItemCooldowns.class)
public class ItemCooldownsMixin {
	@Inject(method = "addCooldown(Lnet/minecraft/world/item/ItemStack;I)V", at = @At("HEAD"))
	private void originclient$cooldownIcon(ItemStack stack, int ticks, CallbackInfo ci) {
		try {
			CooldownsHud.onStackCooldown((ItemCooldowns) (Object) this, stack);
		} catch (Throwable ignored) {
			// the HUD just shows the group's own item
		}
	}

	@Inject(method = "addCooldown(Lnet/minecraft/resources/ResourceLocation;I)V", at = @At("HEAD"))
	private void originclient$trackCooldown(ResourceLocation group, int ticks, CallbackInfo ci) {
		try {
			CooldownsHud.onCooldownAdded((ItemCooldowns) (Object) this, group, ticks);
		} catch (Throwable ignored) {
			// a HUD readout must never stop the cooldown itself
		}
	}
}
