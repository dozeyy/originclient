package com.origin.client.client.mixin;

import com.origin.client.client.hud.CooldownsHud;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemCooldowns;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Cooldowns HUD: learns which items went on cooldown, and for how long.
//
// ItemCooldowns keeps its entries in a private map of a private inner class, so
// the HUD can't list them; vanilla only answers "how far through is item X".
// addCooldown(Item, int) is the single entry point (decompiled 1.19.3):
// the server's cooldown packet and the client's own prediction (ender pearl,
// chorus fruit, shield disable) both land here. Read-only -- the call proceeds.
// CooldownsHud ignores every ItemCooldowns that isn't the local player's (the
// singleplayer server's copies pass through here too).
@Mixin(ItemCooldowns.class)
public class ItemCooldownsMixin {
	@Inject(method = "addCooldown", at = @At("HEAD"))
	private void originclient$trackCooldown(Item item, int ticks, CallbackInfo ci) {
		CooldownsHud.onCooldownAdded((ItemCooldowns) (Object) this, item, ticks);
	}
}
