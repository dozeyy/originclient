package com.origin.client.client.mixin;

import com.origin.client.client.hud.CombatTracker;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// The one place the client knows "I just swung at that player": Minecraft.startAttack
// ray-traces the crosshair and routes a landed melee hit through here. Recording the
// target is all the Tab Editor's combat highlight needs — no packets, no server help.
// The same swing also feeds the Combo Counter / Reach Display (CombatStats).
// attack(Player, Entity) is unchanged across every 1.21.x family Origin ships.
@Mixin(MultiPlayerGameMode.class)
public class MultiPlayerGameModeMixin {

	@Inject(method = "attack", at = @At("HEAD"))
	private void originclient$trackCombat(Player player, Entity target, CallbackInfo ci) {
		if (target instanceof Player hit) {
			CombatTracker.hit(hit.getUUID());
		}
		// Combo Counter / Reach Display: remember the swing; CombatStats.tick
		// confirms it only once the target visibly takes the hit.
		try {
			com.origin.client.client.mods.CombatStats.onAttack(target);
		} catch (Throwable ignored) {
			// a stats readout must never interfere with attacking
		}
	}
}
