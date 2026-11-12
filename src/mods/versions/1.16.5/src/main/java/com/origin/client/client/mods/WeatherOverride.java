package com.origin.client.client.mods;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;

/**
 * Weather Changer's forced values, as a pure READ-TIME override.
 *
 * This used to work by calling level.setRainLevel()/setThunderLevel() every
 * client tick, which was wrong three ways and produced exactly the symptoms Will
 * hit:
 *  - it MUTATED real level state, so it wasn't "purely visual";
 *  - turning the mod off just stopped writing, leaving whatever was forced
 *    stuck there with nothing to restore it;
 *  - vanilla keeps updating the same fields from server packets, so the two
 *    fought and the change lagged instead of snapping.
 *
 * Overriding the getters instead means nothing is ever written: the value is
 * computed on read, so switching modes is instant, turning the mod off returns
 * to real weather on the very next frame, and no state can be left behind.
 * Rain/thunder AMBIENCE follows for free -- vanilla's sound code reads the same
 * getters as the renderer.
 */
public final class WeatherOverride {

	private WeatherOverride() {
	}

	/** True only for the level the player is actually looking at. */
	private static boolean applies(Level level) {
		// In singleplayer the integrated server's ServerLevel lives in this same
		// JVM and is also a Level, so this mixin would hit it too. Restricting to
		// the client's own level is what keeps this client-side and cosmetic --
		// the server's real weather is never touched.
		// Level check first: Mods.on reads the config map, which must never happen on the integrated server thread.
		return level == Minecraft.getInstance().level && Mods.on("weather");
	}

	/** Forced rain level, or null to let vanilla answer. */
	public static Float rain(Level level) {
		if (!applies(level)) {
			return null;
		}
		// Snow isn't its own weather in Minecraft -- it's rain falling in a cold
		// biome, so it wants a full rain level too (LevelRendererMixin then draws it
		// as snow in every biome). Only Clear means none.
		return Mods.mode("weather", "mode").equals("Clear") ? 0f : 1f;
	}

	/** Forced thunder level, or null to let vanilla answer. */
	public static Float thunder(Level level) {
		if (!applies(level)) {
			return null;
		}
		return thunderOn(Mods.mode("weather", "mode")) ? 1f : 0f;
	}

	/**
	 * Thunder mode, or the Thunder toggle on a weather that has precipitation. Clear + Thunder
	 * used to darken a clear sky and roll thunder every 15 s; a storm needs rain or snow.
	 * The one rule for both the forced level and the thunder-sound cadence (OriginClientMod).
	 */
	public static boolean thunderOn(String mode) {
		return mode.equals("Thunder") || (!mode.equals("Clear") && Mods.bool("weather", "thunder"));
	}

	/**
	 * Snow mode is on. Read per rain column by LevelRendererMixin's precipitation hooks, so it is
	 * a plain field refreshed once a client tick (tick()) rather than config lookups per column.
	 */
	public static boolean snow = false;

	/** Called every client tick from OriginClientMod.applyWeather, before its early return. */
	public static void tick() {
		snow = Mods.on("weather") && Mods.mode("weather", "mode").equals("Snow");
	}
}
