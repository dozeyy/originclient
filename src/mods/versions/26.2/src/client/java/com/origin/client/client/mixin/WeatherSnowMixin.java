package com.origin.client.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.origin.client.client.mods.WeatherOverride;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Weather Changer "Snow" snows in every biome.
 *
 * Snow forces the rain LEVEL (LevelWeatherMixin), but the biome temperature
 * still picks rain or snow per column, so warm biomes showed rain.
 * Target: ClientLevel.getPrecipitationAt (26.2+:
 * the weather renderer and rain particles/sounds both ask the client level).
 * Client only: ClientLevel is the client's own level,
 * so the integrated server's real weather (snow layers, cauldrons) never
 * sees this.
 *
 * If it stops applying (defaultRequire 0) Snow falls back to vanilla's per-biome
 * rain/snow; nothing else breaks.
 */
@Mixin(ClientLevel.class)
public class WeatherSnowMixin {

	// The biome's rain/snow pick inside ClientLevel.getPrecipitationAt. Only reached
	// for loaded chunks (the unloaded-chunk NONE return stays vanilla's).
	@ModifyExpressionValue(method = "getPrecipitationAt",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/biome/Biome;getPrecipitationAt(Lnet/minecraft/core/BlockPos;I)Lnet/minecraft/world/level/biome/Biome$Precipitation;"))
	private Biome.Precipitation originclient$snowPrecipitation(Biome.Precipitation original) {
		return WeatherOverride.snowEverywhere() ? Biome.Precipitation.SNOW : original;
	}
}
