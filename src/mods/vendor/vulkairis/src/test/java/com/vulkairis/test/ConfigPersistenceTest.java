package com.vulkairis.test;

import com.vulkairis.config.VulkairisConfig;
import com.vulkairis.config.VulkairisConfigManager;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/**
 * GUI plan Phase 3: typed config with defaults, validation, schema migration, atomic saves,
 * and corrupt-file recovery.
 */
public class ConfigPersistenceTest {

    private Path tempDir() throws Exception {
        return Files.createTempDirectory("vulkairis-config-test");
    }

    private void cleanup(Path dir) {
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        } finally {
            VulkairisConfigManager.setConfigFileForTests(null);
        }
    }

    @Test
    public void testDefaultsAndValidation() {
        VulkairisConfig config = new VulkairisConfig();
        Assertions.assertTrue(config.validate().isEmpty(), "defaults must validate clean");
        Assertions.assertTrue(config.isEnabled());
        Assertions.assertTrue(config.isAutoBackend());
        Assertions.assertTrue(config.isAllowOpenGlFallback());
        Assertions.assertTrue(config.isShowStartupStatus());
        Assertions.assertTrue(config.isStartupValidation());
        Assertions.assertEquals(VulkairisConfig.DiagnosticVerbosity.BASIC,
                config.getDiagnosticVerbosity());
        Assertions.assertTrue(config.isLogShaderCompileErrors());
        Assertions.assertFalse(config.isIncludeRendererCapabilities());
        Assertions.assertTrue(config.isPipelineCacheEnabled());
        Assertions.assertTrue(config.getExperimentalFlags().isEmpty());
        Assertions.assertFalse(config.isExperimentalFlag("anything"));
        Assertions.assertEquals(VulkairisConfig.CURRENT_SCHEMA_VERSION, config.getSchemaVersion());
    }

    @Test
    public void testRepairFillsNulls() {
        VulkairisConfig config = new VulkairisConfig();
        config.setCompatibilityMode(null);
        config.setDiagnosticVerbosity(null);
        config.setDebugRenderMode(null);
        config.setExperimentalFlags(null);
        Assertions.assertFalse(config.validate().isEmpty());
        config.repair();
        Assertions.assertTrue(config.validate().isEmpty(), "repair must restore validity");
        Assertions.assertEquals(VulkairisConfig.CompatibilityMode.AUTOMATIC,
                config.getCompatibilityMode());
    }

    @Test
    public void testSaveReloadRoundTrip() throws Exception {
        Path dir = tempDir();
        try {
            Path file = dir.resolve("vulkairis.json");
            VulkairisConfigManager.setConfigFileForTests(file);
            VulkairisConfigManager.loadConfig();
            VulkairisConfig config = VulkairisConfigManager.getConfig();
            config.setEnabled(false);
            config.setDiagnosticVerbosity(VulkairisConfig.DiagnosticVerbosity.DEVELOPER);
            config.setAutoBackend(false);
            config.getExperimentalFlags().put("exp.sample", true);
            VulkairisConfigManager.saveConfig();

            VulkairisConfigManager.loadConfig();
            VulkairisConfig reloaded = VulkairisConfigManager.getConfig();
            Assertions.assertFalse(reloaded.isEnabled());
            Assertions.assertEquals(VulkairisConfig.DiagnosticVerbosity.DEVELOPER,
                    reloaded.getDiagnosticVerbosity());
            Assertions.assertFalse(reloaded.isAutoBackend());
            Assertions.assertTrue(reloaded.isExperimentalFlag("exp.sample"));
            Assertions.assertTrue(reloaded.validate().isEmpty());

            try (var stream = Files.list(dir)) {
                Assertions.assertEquals(1, stream.count(),
                        "atomic saves must leave no temp files behind");
            }
        } finally {
            cleanup(dir);
        }
    }

    @Test
    public void testEnumStoredAsStableIdentifier() throws Exception {
        Path dir = tempDir();
        try {
            Path file = dir.resolve("vulkairis.json");
            VulkairisConfigManager.setConfigFileForTests(file);
            VulkairisConfigManager.loadConfig();
            VulkairisConfigManager.getConfig()
                    .setDiagnosticVerbosity(VulkairisConfig.DiagnosticVerbosity.DETAILED);
            VulkairisConfigManager.saveConfig();
            String raw = Files.readString(file);
            Assertions.assertTrue(raw.contains("\"diagnosticVerbosity\": \"DETAILED\""),
                    "enums must persist as identifiers, not labels: " + raw);
        } finally {
            cleanup(dir);
        }
    }

    @Test
    public void testVersionOneFileMigrates() throws Exception {
        Path dir = tempDir();
        try {
            Path file = dir.resolve("vulkairis.json");
            Files.writeString(file, "{\"enabled\": false, \"useVulkanRenderer\": true}");
            VulkairisConfigManager.setConfigFileForTests(file);
            VulkairisConfig loaded = VulkairisConfigManager.loadConfig();
            Assertions.assertFalse(loaded.isEnabled(), "v1 values must survive migration");
            Assertions.assertTrue(loaded.isAutoBackend(), "new keys default on migrate");
            Assertions.assertEquals(VulkairisConfig.DiagnosticVerbosity.BASIC,
                    loaded.getDiagnosticVerbosity());
            Assertions.assertEquals(VulkairisConfig.CURRENT_SCHEMA_VERSION,
                    loaded.getSchemaVersion());
            Assertions.assertTrue(loaded.validate().isEmpty());
        } finally {
            cleanup(dir);
        }
    }

    @Test
    public void testCorruptFileBackedUpAndReplaced() throws Exception {
        Path dir = tempDir();
        try {
            Path file = dir.resolve("vulkairis.json");
            Files.writeString(file, "{ this is not json");
            VulkairisConfigManager.setConfigFileForTests(file);
            VulkairisConfig loaded = VulkairisConfigManager.loadConfig();
            Assertions.assertTrue(loaded.isEnabled(), "corrupt files fall back to defaults");
            try (var stream = Files.list(dir)) {
                List<String> names = stream.map(p -> p.getFileName().toString()).toList();
                Assertions.assertTrue(names.stream().anyMatch(n -> n.startsWith("vulkairis.json.bak.")),
                        "corrupt file must be backed up, found: " + names);
                Assertions.assertTrue(names.contains("vulkairis.json"),
                        "defaults must be rewritten");
            }
            VulkairisConfig reloaded = VulkairisConfigManager.loadConfig();
            Assertions.assertTrue(reloaded.validate().isEmpty());
        } finally {
            cleanup(dir);
        }
    }

    @Test
    public void testHeadlessKeepsMemoryDefaults() {
        VulkairisConfigManager.setConfigFileForTests(null);
        // No Fabric game directory headless: load/save are safe in-memory no-ops.
        VulkairisConfig config = VulkairisConfigManager.loadConfig();
        Assertions.assertNotNull(config);
        VulkairisConfigManager.saveConfig();
    }
}
