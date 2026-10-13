package com.origin.client.client.mixin;

import com.origin.client.client.mods.Mods;
import net.minecraft.client.ClientClockManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Time Changer (client-side only). 26.x drives the sky, sun, moon and stars from
// world clocks the CLIENT keeps in ClientClockManager. 26.2 overrode
// ClientClockManager.getTotalTicks(clock); 26.3 removed that method -- every reader
// now asks the clock's ClientClockInstance.totalTicks() (javap -c, 26.3: the
// manager itself only ever touches the totalTicks FIELD, so overriding the getter
// never feeds back into the real clock). Overriding it keeps the day COUNT (moon
// phases) and swaps the time of day for OriginClientMod.timeOverride (slider, Use
// Real Time, the +/- keys and Time Passage, resolved once per tick). The server's
// clock -- and gameplay -- are never touched.
@Mixin(ClientClockManager.ClientClockInstance.class)
public class LevelTimeMixin {

	@Inject(method = "totalTicks", at = @At("RETURN"), cancellable = true)
	private void originclient$lockClientTime(CallbackInfoReturnable<Long> cir) {
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
