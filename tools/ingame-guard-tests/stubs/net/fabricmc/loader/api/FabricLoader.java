package net.fabricmc.loader.api;

import java.nio.file.Path;
import java.nio.file.Paths;

// Test stub: the only FabricLoader call ModsConfig makes. The config dir comes from
// -Dtest.configDir so every scenario runs in its own temp folder.
public final class FabricLoader {
	private static final FabricLoader INSTANCE = new FabricLoader();

	public static FabricLoader getInstance() {
		return INSTANCE;
	}

	public Path getConfigDir() {
		return Paths.get(System.getProperty("test.configDir"));
	}
}
