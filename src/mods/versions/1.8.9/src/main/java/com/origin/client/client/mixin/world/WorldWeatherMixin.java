package com.origin.client.client.mixin.world;

import com.origin.client.client.mods.WeatherOverride;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Weather Changer, as a read-time override of the weather getters.
 *
 * 1.8.9: World.getRainStrength(float) / getThunderStrength(float); WorldClient
 * does NOT override them, so World is the only place to hook. WeatherOverride
 * restricts the effect to the client's own world, so the integrated server's
 * WorldServer -- same JVM in singleplayer, also a World -- is never affected and
 * real weather is untouched.
 *
 * Every consumer reads through these getters (rain/snow rendering, sky
 * darkening, rain ambience and splashes), so visuals AND sound follow the
 * setting with no state written anywhere.
 */
@Mixin(World.class)
public abstract class WorldWeatherMixin {

	@Inject(method = "getRainStrength", at = @At("HEAD"), cancellable = true)
	private void originclient$rainLevel(float partialTick, CallbackInfoReturnable<Float> cir) {
		Float forced = WeatherOverride.rain((World) (Object) this);
		if (forced != null) {
			cir.setReturnValue(forced);
		}
	}

	@Inject(method = "getThunderStrength", at = @At("HEAD"), cancellable = true)
	private void originclient$thunderLevel(float partialTick, CallbackInfoReturnable<Float> cir) {
		Float forced = WeatherOverride.thunder((World) (Object) this);
		if (forced != null) {
			cir.setReturnValue(forced);
		}
	}
}
