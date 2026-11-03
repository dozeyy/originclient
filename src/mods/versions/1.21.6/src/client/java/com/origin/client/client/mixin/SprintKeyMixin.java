package com.origin.client.client.mixin;

import com.origin.client.client.OriginClientMod;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Toggle Sprint and Toggle Sneak, done the way the game already understands:
// while a toggle is on, its vanilla key (Sprint / Sneak) simply reads as held.
// Nothing else is forced. (Class name kept from when it was sprint-only.)
//
// Every Minecraft era from 1.16.5 to 1.21.11 starts a sprint from exactly this
// read -- LocalPlayer.aiStep does `if (options.keySprint.isDown()) setSprinting
// (true)` (1.21.9+ routes the same read through Input.sprint()) -- so the
// sprint begins at the correct point in the tick, before movement is applied
// and before the position/sprint packet is sent.
//
// Sneak is the same read: KeyboardInput.tick builds the tick's Input from
// options.keyShift.isDown() (the Input.shiftKeyDown field up to 1.21.1, the
// Input record's shift() from 1.21.2 on), and LocalPlayer.isShiftKeyDown() --
// what the server is told and what decides safe edges and shift-placing -- reads
// only that Input. Calling player.setShiftKeyDown(true) (what this replaces) set
// the entity flag but never reached that Input, so the server never saw a sneak.
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
//
// If this mixin stops applying, both toggles go dead (the keys read their real
// state); nothing else breaks. A failure inside the check answers "not held".
@Mixin(KeyMapping.class)
public class SprintKeyMixin {

	@Inject(method = "isDown", at = @At("HEAD"), cancellable = true)
	private void originclient$toggleHoldsKey(CallbackInfoReturnable<Boolean> cir) {
		try {
			if (OriginClientMod.keyHeldByToggle((KeyMapping) (Object) this)) {
				cir.setReturnValue(true);
			}
		} catch (Throwable t) {
			// fail-soft: the key reads its real state
		}
	}
}
