package com.origin.client.client.mixin.core;

import com.origin.client.client.OriginClientMod;
import net.minecraft.client.settings.KeyBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Toggle Sprint / Toggle Sneak: while a toggle is engaged, the vanilla sprint
 * (or sneak) key READS as held, so the game itself starts sprinting / sneaking
 * by its own rules at the right point in the tick (1.16.5's SprintKeyMixin,
 * extended to sneak -- see OriginClientMod.sneakKeyHeldByToggle for why).
 * HEAD inject on a plain getter; KeyBinding is not an OptiFine-replaced class.
 */
@Mixin(KeyBinding.class)
public abstract class KeyBindingMixin {
	@Inject(method = "isKeyDown", at = @At("HEAD"), cancellable = true)
	private void originclient$heldByToggle(CallbackInfoReturnable<Boolean> cir) {
		try {
			if (OriginClientMod.keyHeldByToggle((KeyBinding) (Object) this)) {
				cir.setReturnValue(true);
			}
		} catch (Throwable t) {
			// vanilla key state
		}
	}
}
