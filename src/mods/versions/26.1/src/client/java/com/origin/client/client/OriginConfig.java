package com.origin.client.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class OriginConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("originclient.json");

	private OriginConfig() {
	}

	public static OriginFeatures load() {
		if (Files.exists(PATH)) {
			try (Reader reader = Files.newBufferedReader(PATH, StandardCharsets.UTF_8)) {
				OriginFeatures loaded = GSON.fromJson(reader, OriginFeatures.class);
				if (loaded != null) {
					return loaded;
				}
			} catch (IOException | RuntimeException e) {
				com.origin.client.OriginClient.LOGGER.warn("Failed to read originclient.json, using defaults", e);
			}
		}
		return new OriginFeatures();
	}

	public static void save(OriginFeatures features) {
		try {
			// Crash-safe write: serialize to a sibling .tmp, then atomically
			// rename over the real file, so a kill mid-write can only leave a
			// stale .tmp instead of a truncated config.
			Files.createDirectories(PATH.getParent());
			Path tmp = PATH.resolveSibling(PATH.getFileName() + ".tmp");
			try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
				GSON.toJson(features, writer);
			}
			try {
				Files.move(tmp, PATH, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(tmp, PATH, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			com.origin.client.OriginClient.LOGGER.warn("Failed to save originclient.json", e);
		}
	}
}
