package com.origin.client.client.mixin.world;

import com.origin.client.client.mods.Mods;
import net.minecraft.client.renderer.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Weather Changer "Clear": skips the precipitation draw (EntityRenderer.renderRainSnow)
// and the rain splash particles + rain sound (addRainParticles) outright -- render
// only; the forced rain strength itself comes from WorldWeatherMixin. Other modes
// render normally (Snow = rain in a cold biome, as vanilla draws it). HEAD cancels:
// EntityRenderer is OptiFine-replaced.
@Mixin(EntityRenderer.class)
public abstract class WeatherRenderMixin {

	private static boolean originclient$clear() {
		return Mods.on("weather") && Mods.mode("weather", "mode").equals("Clear");
	}

	@Inject(method = "renderRainSnow", at = @At("HEAD"), cancellable = true)
	private void originclient$skipWeather(float partialTicks, CallbackInfo ci) {
		if (originclient$clear()) {
			ci.cancel();
		}
	}

	@Inject(method = "addRainParticles", at = @At("HEAD"), cancellable = true)
	private void originclient$skipRainParticles(CallbackInfo ci) {
		if (originclient$clear()) {
			ci.cancel();
		}
	}
}
