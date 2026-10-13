package com.origin.client.client.mixin;

import com.origin.client.client.mods.Mods;
import net.minecraft.client.ClientClockManager;
import net.minecraft.core.Holder;
import net.minecraft.world.clock.WorldClock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Time Changer (client-side only). 26.x replaced Level.getDayTime with world
// clocks: Level.getOverworldClockTime()/getDefaultClockTime() read the CLIENT's
// ClientClockManager.getTotalTicks(clock), which is what the sky, sun, moon and
// stars are driven from. Overriding it here keeps the day COUNT (moon phases)
// and swaps the time of day for OriginClientMod.timeOverride (slider, Use Real
// Time, the +/- keys and Time Passage, resolved once per tick). The server's
// clock -- and gameplay -- are never touched: this class only exists client-side.
@Mixin(ClientClockManager.class)
public class LevelTimeMixin {

	@Inject(method = "getTotalTicks", at = @At("RETURN"), cancellable = true)
	private void originclient$lockClientTime(Holder<WorldClock> clock, CallbackInfoReturnable<Long> cir) {
		try {
			if (!Mods.on("timechanger") || com.origin.client.client.OriginClientMod.readingRealTime) {
				return;
			}
			long total = cir.getReturnValueJ();
			long day = Math.floorDiv(total, 24000L);
			cir.setReturnValue(day * 24000L + (long) com.origin.client.client.OriginClientMod.timeOverride);
		} catch (Throwable t) {
			// keep the real time
		}
	}
}
