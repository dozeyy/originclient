package com.origin.client.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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
			Files.createDirectories(PATH.getParent());
			try (Writer writer = Files.newBufferedWriter(PATH, StandardCharsets.UTF_8)) {
				GSON.toJson(features, writer);
			}
		} catch (IOException e) {
			com.origin.client.OriginClient.LOGGER.warn("Failed to save originclient.json", e);
		}
	}
}
