package com.origin.client.client.mods;

import com.origin.client.OriginClient;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;

import java.util.Map;

/**
 * Feeds {@link StepAside}'s table from Forge's Loader (1.8.9 has no Fabric Loader), once,
 * at the start of Origin's client init (FMLInitializationEvent, OriginClientMod.init):
 * every mod is constructed by then, and the mod list cannot change while the game runs.
 * Forge-side copy of the shared Fabric StepAsideDetect; no 1.8.9 row is shippedByOrigin
 * (no bundled JEI here), so there is no nested-jar check.
 */
public final class StepAsideDetect {

	private StepAsideDetect() {
	}

	/** Never throws: on any failure nothing is replaced, i.e. Origin behaves exactly as before. */
	public static void run() {
		try {
			final Map<String, ModContainer> mods = Loader.instance().getIndexedModList();
			Map<String, StepAside.Replaced> found = StepAside.resolve(id -> {
				if (!Loader.isModLoaded(id)) {
					return null;
				}
				ModContainer c = mods.get(id);
				String name = c == null ? null : c.getName();
				return name == null || name.trim().isEmpty() ? id : name;
			});
			for (Map.Entry<String, StepAside.Replaced> e : found.entrySet()) {
				OriginClient.LOGGER.info("Origin: " + e.getValue().by() + " is installed; Origin's " + e.getKey()
						+ " steps aside (the player can turn it back on)");
			}
		} catch (Throwable t) {
			StepAside.resolve(id -> null);
			OriginClient.LOGGER.warn("Origin: could not check for mods that replace Origin's; nothing steps aside", t);
		}
		// Mods.on caches its answers; anything asked before this ran (startup mixins) was
		// answered without the table, so drop it.
		Mods.clearOnCache();
	}
}
