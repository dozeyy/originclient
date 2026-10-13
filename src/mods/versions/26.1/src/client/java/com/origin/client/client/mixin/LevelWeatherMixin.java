package com.origin.client.client.mixin;

import com.origin.client.client.mods.WeatherOverride;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Weather Changer, as a read-time override of the weather getters.
 *
 * ClientLevel does NOT override these, so Level is the only place to hook them.
 * WeatherOverride restricts the effect to the client's own level, so the
 * integrated server's ServerLevel -- same JVM in singleplayer, same class -- is
 * never affected and real weather is untouched.
 *
 * Every consumer reads through these getters (precipitation rendering, sky
 * darkening, rain/thunder ambience), so visuals AND sound follow the setting
 * with no state written anywhere. See WeatherOverride for why the old
 * setRainLevel() approach couldn't be instant or purely visual.
 */
@Mixin(Level.class)
public class LevelWeatherMixin {

	@Inject(method = "getRainLevel", at = @At("HEAD"), cancellable = true)
	private void originclient$rainLevel(float partialTick, CallbackInfoReturnable<Float> cir) {
		Float forced = WeatherOverride.rain((Level) (Object) this);
		if (forced != null) {
			cir.setReturnValue(forced);
		}
	}

	@Inject(method = "getThunderLevel", at = @At("HEAD"), cancellable = true)
	private void originclient$thunderLevel(float partialTick, CallbackInfoReturnable<Float> cir) {
		Float forced = WeatherOverride.thunder((Level) (Object) this);
		if (forced != null) {
			cir.setReturnValue(forced);
		}
	}
}
