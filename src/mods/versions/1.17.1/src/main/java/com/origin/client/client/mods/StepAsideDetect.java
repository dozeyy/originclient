package com.origin.client.client.mods;

import com.origin.client.OriginClient;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.util.Map;
import java.util.Optional;

/**
 * Feeds {@link StepAside}'s table from Fabric Loader, once, at the very start of client
 * init (before anything reads Mods.on and before WorldEditCui.init). The mod list cannot
 * change while the game runs, so nothing here is ever looked up again.
 * Hand copy of the shared StepAsideDetect for this .no-shared-sync module.
 */
public final class StepAsideDetect {
	private static final String ORIGIN_ID = "originclient";

	private StepAsideDetect() {
	}

	/** Never throws: on any failure nothing is replaced, i.e. Origin behaves exactly as before. */
	public static void run() {
		try {
			FabricLoader loader = FabricLoader.getInstance();
			boolean dev = loader.isDevelopmentEnvironment();
			Map<String, StepAside.Replaced> found = StepAside.resolve(id -> {
				if (!loader.isModLoaded(id)) {
					return null;
				}
				ModContainer c = loader.getModContainer(id).orElse(null);
				if (c == null) {
					return null;
				}
				if (shippedByOrigin(id)) {
					// Origin bundles this id itself (JEI). Only a copy that is NOT nested in
					// Origin's jar is the player's own. The dev game nests nothing (its JEI
					// comes off the Gradle classpath), so there it never counts.
					if (dev || nestedInOrigin(c)) {
						return null;
					}
				}
				String name = c.getMetadata().getName();
				return name == null || name.isBlank() ? id : name;
			});
			for (Map.Entry<String, StepAside.Replaced> e : found.entrySet()) {
				OriginClient.LOGGER.info("Origin: {} is installed; Origin's {} steps aside (the player can turn it back on)",
						e.getValue().by(), e.getKey());
			}
		} catch (Throwable t) {
			StepAside.resolve(id -> null);
			OriginClient.LOGGER.warn("Origin: could not check for mods that replace Origin's; nothing steps aside", t);
		}
	}

	private static boolean shippedByOrigin(String foreignId) {
		for (StepAside.Row row : StepAside.TABLE) {
			if (row.shippedByOrigin() && row.foreignIds().contains(foreignId)) {
				return true;
			}
		}
		return false;
	}

	private static boolean nestedInOrigin(ModContainer c) {
		for (Optional<ModContainer> p = c.getContainingMod(); p.isPresent(); p = p.get().getContainingMod()) {
			if (ORIGIN_ID.equals(p.get().getMetadata().getId())) {
				return true;
			}
		}
		return false;
	}
}
