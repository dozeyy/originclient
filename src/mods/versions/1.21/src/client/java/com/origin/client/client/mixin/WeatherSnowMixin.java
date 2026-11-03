package com.origin.client.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.origin.client.client.mods.WeatherOverride;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Weather Changer "Snow" snows in every biome.
 *
 * Snow forces the rain LEVEL (LevelWeatherMixin), but the biome temperature
 * still picks rain or snow per column, so warm biomes showed rain.
 * Target: LevelRenderer.renderSnowAndRain (the
 * biome's hasPrecipitation gate + its rain/snow pick) and tickRain (splashes
 * and rain sounds).
 * Client only: LevelRenderer only runs on the render thread,
 * so the integrated server's real weather (snow layers, cauldrons) never
 * sees this.
 *
 * If it stops applying (defaultRequire 0) Snow falls back to vanilla's per-biome
 * rain/snow; nothing else breaks.
 */
@Mixin(LevelRenderer.class)
public class WeatherSnowMixin {

	// renderSnowAndRain skips any column whose biome has no precipitation at all
	// (deserts, badlands): let Snow fall there too.
	@ModifyExpressionValue(method = "renderSnowAndRain",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/biome/Biome;hasPrecipitation()Z"))
	private boolean originclient$snowHasPrecipitation(boolean original) {
		return original || WeatherOverride.snowEverywhere();
	}

	// The rain/snow pick: drawn columns (renderSnowAndRain) and rain splashes +
	// rain sounds (tickRain), which stay silent under snow.
	@ModifyExpressionValue(method = {"renderSnowAndRain", "tickRain"},
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/biome/Biome;getPrecipitationAt(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/biome/Biome$Precipitation;"))
	private Biome.Precipitation originclient$snowPrecipitation(Biome.Precipitation original) {
		return WeatherOverride.snowEverywhere() ? Biome.Precipitation.SNOW : original;
	}
}
