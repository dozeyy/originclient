package com.vulkairis.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Versioned, validated, atomic configuration persistence (GUI plan Phase 3).
 *
 * <ul>
 *   <li>Schema versioned ({@link VulkairisConfig#CURRENT_SCHEMA_VERSION}) with migration for
 *       older files.</li>
 *   <li>Atomic saves (temp file plus atomic move with plain-move fallback).</li>
 *   <li>Malformed files are backed up ({@code vulkairis.json.bak.<timestamp>}) and replaced
 *       with repaired defaults — never deleted silently.</li>
 *   <li>Every loaded model is {@link VulkairisConfig#repair() repaired} and
 *       {@link VulkairisConfig#validate() validated} before use.</li>
 * </ul>
 *
 * <p>Headless-safe: without a Fabric game directory the manager keeps everything in memory
 * with safe defaults (same approach as the earlier loader fix).</p>
 */
public final class VulkairisConfigManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkairis/Config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "vulkairis.json";

    private static volatile Path overridePath = null;
    private static volatile VulkairisConfig current = new VulkairisConfig();

    private VulkairisConfigManager() {
    }

    /** Overrides the config file location; tests only. Pass {@code null} to restore. */
    public static void setConfigFileForTests(Path path) {
        overridePath = path;
    }

    private static Path configPath() {
        if (overridePath != null) {
            return overridePath;
        }
        try {
            return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
        } catch (Throwable t) {
            return null;
        }
    }

    public static synchronized VulkairisConfig getConfig() {
        return current;
    }

    /**
     * GUI plan Phase 8: safe reset. Replaces options and experimental flags with defaults and
     * persists them. Shader packs, worlds and unrelated mod configs are never touched — only
     * this mod's file is rewritten.
     *
     * @return the fresh defaults now in effect
     */
    public static synchronized VulkairisConfig resetToDefaults() {
        current = repaired(new VulkairisConfig());
        saveConfig();
        LOGGER.info("[Vulkairis] Configuration reset to safe defaults.");
        return current;
    }

    public static synchronized VulkairisConfig loadConfig() {
        Path path = configPath();
        if (path == null) {
            current = repaired(new VulkairisConfig());
            return current;
        }
        try {
            if (!Files.exists(path)) {
                current = repaired(new VulkairisConfig());
                saveConfig();
                return current;
            }
            try (Reader reader = Files.newBufferedReader(path)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                int version = root.has("schemaVersion") ? root.get("schemaVersion").getAsInt() : 1;
                root = migrate(root, version);
                VulkairisConfig loaded = GSON.fromJson(root, VulkairisConfig.class);
                if (loaded == null) {
                    throw new IllegalStateException("parsed config is null");
                }
                current = repaired(loaded);
                LOGGER.info("[Vulkairis] Configuration loaded from {}", path);
                return current;
            }
        } catch (Throwable t) {
            backupCorruptFile(path, t);
            current = repaired(new VulkairisConfig());
            saveConfig();
            return current;
        }
    }

    public static synchronized void saveConfig() {
        Path path = configPath();
        if (path == null) {
            return;
        }        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            current.repair();
            Path temp = Files.createTempFile(parent, FILE_NAME, ".tmp");
            try {
                try (Writer writer = Files.newBufferedWriter(temp)) {
                    GSON.toJson(current, writer);
                }
                try {
                    Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
                }
                LOGGER.info("[Vulkairis] Configuration saved to {}", path);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (Throwable t) {
            LOGGER.error("[Vulkairis] Failed to save configuration to {}: {}", path, t.getMessage());
        }
    }

    /**
     * Migrates a parsed file to the current schema. Version 1 files (no {@code schemaVersion},
     * pre-enum fields) gain defaults through repair; future renames add explicit steps here.
     */
    static JsonObject migrate(JsonObject root, int fromVersion) {
        if (fromVersion < 1) {
            throw new IllegalArgumentException("unknown config schema version " + fromVersion);
        }
        // v1 -> v2: new keys (autoBackend, verbosity, cache flags, experimental flags) default
        // via repair(); nothing is renamed, so the document passes through stamped.
        root.addProperty("schemaVersion", VulkairisConfig.CURRENT_SCHEMA_VERSION);
        return root;
    }

    private static VulkairisConfig repaired(VulkairisConfig config) {
        config.repair();
        java.util.List<String> problems = config.validate();
        if (!problems.isEmpty()) {
            LOGGER.warn("[Vulkairis] Config validation notes: {}", problems);
        }
        return config;
    }

    private static void backupCorruptFile(Path path, Throwable cause) {
        try {
            Path backup = path.resolveSibling(
                    FILE_NAME + ".bak." + System.currentTimeMillis());
            Files.move(path, backup, StandardCopyOption.REPLACE_EXISTING);
            LOGGER.warn("[Vulkairis] Corrupt config backed up to {} ({})",
                    backup, cause.getMessage());
        } catch (Throwable t) {
            LOGGER.warn("[Vulkairis] Could not back up corrupt config: {}", t.getMessage());
        }
    }
}
