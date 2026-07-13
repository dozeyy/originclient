package com.origin.client.client.mixin;

import com.origin.client.client.mods.Mods;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Weather Changer. Forcing the client rain/thunder level from the tick loop lost
// a fight with the server's per-tick weather sync, so the override now lives on
// the read side: getRainLevel/getThunderLevel are what the precipitation render,
// sky darkening, and sound all consult, and we override them CLIENT-SIDE only.
// The server's real weather/gameplay is untouched (nothing is sent). Snow renders
// as snowfall in cold biomes (rain level 1 is how MC draws snow); Clear zeroes
// both so the sky stays bright.
@Mixin(Level.class)
public class LevelWeatherMixin {

	@Inject(method = "getRainLevel", at = @At("RETURN"), cancellable = true)
	private void originclient$rain(float partialTick, CallbackInfoReturnable<Float> cir) {
		if (!((Level) (Object) this).isClientSide() || !Mods.on("weather")) {
			return;
		}
		String mode = Mods.mode("weather", "mode");
		cir.setReturnValue(mode.equals("Clear") ? 0f : 1f);
	}

	@Inject(method = "getThunderLevel", at = @At("RETURN"), cancellable = true)
	private void originclient$thunder(float partialTick, CallbackInfoReturnable<Float> cir) {
		if (!((Level) (Object) this).isClientSide() || !Mods.on("weather")) {
			return;
		}
		String mode = Mods.mode("weather", "mode");
		boolean thunder = !mode.equals("Clear") && (mode.equals("Thunder") || Mods.bool("weather", "thunder"));
		cir.setReturnValue(thunder ? 1f : 0f);
	}
}
