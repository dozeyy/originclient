package com.origin.client.client.mixin;

import com.origin.client.client.mods.Mods;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Time Changer: locks the CLIENT's perceived day time to the configured
// value. Client-side only by construction — the override applies only when
// this Level is the client copy; the server's real time, mob spawning, and
// other players are untouched (anticheat-safe: nothing is sent anywhere).
@Mixin(Level.class)
public class LevelTimeMixin {

	@Inject(method = "getDayTime", at = @At("RETURN"), cancellable = true)
	private void originclient$lockClientTime(CallbackInfoReturnable<Long> cir) {
		if (((Level) (Object) this).isClientSide && Mods.on("timechanger")) {
			// timeOverride folds in the slider, Use Real Time, the +/- keys,
			// and Time Passage — resolved once per tick in OriginClientMod.
			cir.setReturnValue((long) com.origin.client.client.OriginClientMod.timeOverride);
		}
	}
}
