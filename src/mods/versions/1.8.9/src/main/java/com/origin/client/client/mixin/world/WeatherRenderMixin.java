package com.origin.client.client.mixin.world;

import com.origin.client.client.mods.Mods;
import com.origin.client.client.mods.WeatherOverride;
import net.minecraft.client.renderer.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Weather Changer "Clear": skips the precipitation draw (EntityRenderer.renderRainSnow)
// and the rain splash particles + rain sound (addRainParticles) outright -- render
// only; the forced rain strength itself comes from WorldWeatherMixin.
// Weather Changer "Snow": brackets the same two passes with WeatherOverride.precipPass so
// BiomeSnowMixin answers "cold, snowy" for the biome lookups they make: snow in every
// biome, no rain splashes or rain sound in warm ones. Other modes render normally.
// HEAD/RETURN only: EntityRenderer is OptiFine-replaced. If a RETURN is missed (another
// mod cancels the pass), WeatherOverride.tick() clears the flag next tick.
@Mixin(EntityRenderer.class)
public abstract class WeatherRenderMixin {

	private static boolean originclient$clear() {
		return Mods.on("weather") && Mods.mode("weather", "mode").equals("Clear");
	}

	@Inject(method = "renderRainSnow", at = @At("HEAD"), cancellable = true)
	private void originclient$skipWeather(float partialTicks, CallbackInfo ci) {
		if (originclient$clear()) {
			ci.cancel();
			return;
		}
		WeatherOverride.precipPass = WeatherOverride.snow;
	}

	@Inject(method = "renderRainSnow", at = @At("RETURN"))
	private void originclient$endSnowPass(float partialTicks, CallbackInfo ci) {
		WeatherOverride.precipPass = false;
	}

	@Inject(method = "addRainParticles", at = @At("HEAD"), cancellable = true)
	private void originclient$skipRainParticles(CallbackInfo ci) {
		if (originclient$clear()) {
			ci.cancel();
			return;
		}
		WeatherOverride.precipPass = WeatherOverride.snow;
	}

	@Inject(method = "addRainParticles", at = @At("RETURN"))
	private void originclient$endRainParticles(CallbackInfo ci) {
		WeatherOverride.precipPass = false;
	}
}
