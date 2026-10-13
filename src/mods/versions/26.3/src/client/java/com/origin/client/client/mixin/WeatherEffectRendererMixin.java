package com.origin.client.client.mixin;

import com.origin.client.client.mods.Mods;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.state.level.WeatherRenderState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Weather "Clear" (render-only). 26.3 dropped LevelRenderer.addWeatherPass: rain
// and snow are extracted into a WeatherRenderState (column lists + intensity)
// and drawn from it inside the main/OIT passes. Emptying that state right after
// extraction draws no precipitation at all, whichever pass would have drawn it.
// Other modes force rain levels tick-side (OriginClientMod.applyWeather).
@Mixin(WeatherEffectRenderer.class)
public class WeatherEffectRendererMixin {

	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void originclient$clearWeather(ClientLevel level, float partialTick, Vec3 camera, WeatherRenderState state, CallbackInfo ci) {
		try {
			if (Mods.on("weather") && Mods.mode("weather", "mode").equals("Clear")) {
				state.rainColumns.clear();
				state.snowColumns.clear();
				state.intensity = 0.0F;
			}
		} catch (Throwable ignored) {
			// vanilla weather
		}
	}
}
