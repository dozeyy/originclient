package com.origin.client.client.mixin;

import com.origin.client.client.OriginClientMod;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Toggle Sprint, done the way the game already understands: while the toggle
// is on, the vanilla sprint key simply reads as held. Nothing else is forced.
//
// Every Minecraft era from 1.16.5 to 1.21.11 starts a sprint from exactly this
// read -- LocalPlayer.aiStep does `if (options.keySprint.isDown()) setSprinting
// (true)` (1.21.9+ routes the same read through Input.sprint()) -- so the
// sprint begins at the correct point in the tick, before movement is applied
// and before the position/sprint packet is sent.
//
// That is what gives the toggle its memory. Opening the inventory, pausing,
// changing settings, hopping servers or bumping a wall all end the sprint the
// way vanilla always did, and vanilla re-starts it by itself on the first tick
// the player is moving again -- so from the player's side it never stops until
// they un-toggle. Forcing setSprinting(true) from the tick loop (what this
// replaces) could not do that: it landed after the tick's movement, and it
// also punched through vanilla's own rules. Those rules are all still in
// force here -- no sprinting inside a screen, at low hunger, or while
// eating/blocking.
@Mixin(KeyMapping.class)
public class SprintKeyMixin {

	@Inject(method = "isDown", at = @At("HEAD"), cancellable = true)
	private void originclient$toggleHoldsSprintKey(CallbackInfoReturnable<Boolean> cir) {
		if (OriginClientMod.sprintKeyHeldByToggle((KeyMapping) (Object) this)) {
			cir.setReturnValue(true);
		}
	}
}
