package com.origin.client.client.mods;

import net.minecraft.client.Minecraft;
import net.minecraft.world.World;

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
 *
 * 1.8.9: the getters are World.getRainStrength(float) / getThunderStrength(float)
 * (WorldWeatherMixin); everything else is unchanged.
 */
public final class WeatherOverride {

	private WeatherOverride() {
	}

	/** True only for the world the player is actually looking at. */
	private static boolean applies(World level) {
		// In singleplayer the integrated server's WorldServer lives in this same
		// JVM and is also a World, so the mixin would hit it too. Restricting to
		// the client's own world is what keeps this client-side and cosmetic --
		// the server's real weather is never touched.
		return Mods.on("weather") && level == Minecraft.getMinecraft().theWorld;
	}

	/** Forced rain level, or null to let vanilla answer. */
	public static Float rain(World level) {
		if (!applies(level)) {
			return null;
		}
		// Snow isn't its own weather in Minecraft -- it's rain falling in a cold
		// biome, so it wants a full rain level too. Only Clear means none.
		return Mods.mode("weather", "mode").equals("Clear") ? 0f : 1f;
	}

	/** Forced thunder level, or null to let vanilla answer. */
	public static Float thunder(World level) {
		if (!applies(level)) {
			return null;
		}
		String mode = Mods.mode("weather", "mode");
		boolean on = mode.equals("Thunder") || Mods.bool("weather", "thunder");
		return on ? 1f : 0f;
	}
}
