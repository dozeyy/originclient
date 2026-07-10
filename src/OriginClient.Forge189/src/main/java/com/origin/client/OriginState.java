package com.origin.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

/**
 * Loads/saves {@link OriginFeatures} as JSON in the Forge config dir
 * (originclient.json). Same schema shape as the Fabric build's config, so a
 * player's toggles feel identical across versions. Fully fail-soft: any I/O or
 * parse failure leaves defaults in place rather than crashing.
 */
public final class OriginState {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static File configFile;
    private static OriginFeatures features = new OriginFeatures();

    private OriginState() {
    }

    public static OriginFeatures features() {
        return features;
    }

    public static void init(File configDir) {
        try {
            if (!configDir.exists()) {
                configDir.mkdirs();
            }
            configFile = new File(configDir, "originclient.json");
            load();
        } catch (Throwable ignored) {
        }
    }

    public static void load() {
        if (configFile == null || !configFile.exists()) {
            return;
        }
        try (FileReader r = new FileReader(configFile)) {
            OriginFeatures loaded = GSON.fromJson(r, OriginFeatures.class);
            if (loaded != null) {
                features = loaded;
            }
        } catch (Throwable ignored) {
        }
    }

    public static void save() {
        if (configFile == null) {
            return;
        }
        try (FileWriter w = new FileWriter(configFile)) {
            GSON.toJson(features, w);
        } catch (Throwable ignored) {
        }
    }
}
