package com.vulkairis.test;

import com.vulkairis.client.gui.GeneralCategoryModel;
import com.vulkairis.client.gui.GuiCategory;
import com.vulkairis.client.gui.RenderingCategoryModel;
import com.vulkairis.client.gui.SettingRow;
import com.vulkairis.client.gui.VulkairisGuiModels;
import com.vulkairis.client.gui.services.CompatibilityTestService;
import com.vulkairis.client.gui.services.RendererCapabilityProvider;
import com.vulkairis.client.gui.services.RendererSettingsService;
import com.vulkairis.client.gui.services.StartupValidation;
import com.vulkairis.config.VulkairisConfigManager;
import com.vulkairis.diagnostics.DiagnosticManager;
import com.vulkairis.translation.SpirvCompiler;
import com.vulkairis.translation.TranslationWorker;
import com.vulkairis.vulkan.pipeline.PipelineManager;
import net.irisshaders.iris.gl.shader.ShaderType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * GUI plan Phase 4: General/Rendering rows are wired to real services — capability snapshots,
 * startup validation, apply boundaries, compatibility validation, cache controls — with every
 * global mutated for the test restored afterwards.
 */
public class CategoryModelTest {

    private static final class ConfigSnapshot {
        final boolean enabled;
        final boolean autoBackend;
        final boolean useVulkan;
        final boolean fallback;
        final boolean cache;
        final boolean logErrors;
        final boolean dumpTranslated;

        ConfigSnapshot() {
            var config = VulkairisConfigManager.getConfig();
            enabled = config.isEnabled();
            autoBackend = config.isAutoBackend();
            useVulkan = config.isUseVulkanRenderer();
            fallback = config.isAllowOpenGlFallback();
            cache = config.isPipelineCacheEnabled();
            logErrors = config.isLogShaderCompileErrors();
            dumpTranslated = config.isDumpTranslatedSource();
        }

        void restore() {
            var config = VulkairisConfigManager.getConfig();
            config.setEnabled(enabled);
            config.setAutoBackend(autoBackend);
            config.setUseVulkanRenderer(useVulkan);
            config.setAllowOpenGlFallback(fallback);
            config.setPipelineCacheEnabled(cache);
            config.setLogShaderCompileErrors(logErrors);
            config.setDumpTranslatedSource(dumpTranslated);
        }
    }

    @Test
    public void testCapabilitySnapshot() {
        var capabilities = RendererCapabilityProvider.snapshot();
        Assertions.assertTrue(capabilities.vulkanModPresent(), "VulkanMod is a test dependency");
        Assertions.assertTrue(capabilities.irisPresent(), "Iris is a test dependency");
        Assertions.assertFalse(capabilities.deviceLive(), "no device headless");
        Assertions.assertFalse(capabilities.deviceName().isBlank());
        var offered = RendererCapabilityProvider.offeredBackends(true);
        Assertions.assertTrue(offered.contains("Auto"));
        Assertions.assertTrue(offered.contains("Vulkan"));
        Assertions.assertTrue(offered.contains("OpenGL"));
        Assertions.assertFalse(
                RendererCapabilityProvider.offeredBackends(false).contains("OpenGL"));
    }

    @Test
    public void testStartupValidationStructure() {
        StartupValidation.Report report = StartupValidation.validate();
        Assertions.assertEquals(5, report.checks().size());
        Assertions.assertTrue(report.checks().stream()
                .anyMatch(c -> c.name().contains("Java") && c.ok()));
        Assertions.assertTrue(report.checks().stream()
                .anyMatch(c -> c.name().contains("Iris") && c.ok()));
        Assertions.assertTrue(report.checks().stream()
                .anyMatch(c -> c.name().contains("Configuration") && c.ok()));
        if (!report.passed()) {
            Assertions.assertFalse(report.problems().isEmpty(),
                    "a failing report must carry actionable problems");
        }
    }

    @Test
    public void testSettingsApplyBoundary() {
        ConfigSnapshot snapshot = new ConfigSnapshot();
        try {
            var applied = RendererSettingsService.setBackend("Vulkan");
            if (RendererSettingsService.offeredBackends().contains("Vulkan")) {
                Assertions.assertTrue(applied.applied());
                Assertions.assertTrue(applied.restartRequired(),
                        "backend choice applies on restart");
                Assertions.assertEquals("Vulkan", RendererSettingsService.requestedBackend());
            }
            var refused = RendererSettingsService.setBackend("Nope");
            Assertions.assertFalse(refused.applied());
            Assertions.assertTrue(refused.reason().contains("Nope"));

            RendererSettingsService.setPipelineCacheEnabled(false);
            Assertions.assertFalse(VulkairisConfigManager.getConfig().isPipelineCacheEnabled());
            AtomicInteger creations = new AtomicInteger(0);
            var key = new com.vulkairis.vulkan.pipeline.PipelineKey(1L, 2L, "f", 3L, 4,
                    false, false, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0L, "", 0);
            PipelineManager.getOrCreatePipeline(key, creations::incrementAndGet);
            PipelineManager.getOrCreatePipeline(key, creations::incrementAndGet);
            Assertions.assertEquals(2, creations.get(),
                    "disabled caches must bypass instead of storing");

            var disabled = RendererSettingsService.setEnabled(false);
            Assertions.assertTrue(disabled.applied() && disabled.restartRequired());
        } finally {
            snapshot.restore();
        }
    }

    @Test
    public void testLogDiagnosticsToggle() {
        ConfigSnapshot snapshot = new ConfigSnapshot();
        try {
            DiagnosticManager.clearHistory();
            VulkairisConfigManager.getConfig().setLogShaderCompileErrors(false);
            SpirvCompiler.compileShader("toggle_probe", "this is not glsl", ShaderType.FRAGMENT);
            boolean hasRecord = DiagnosticManager.getRecentRecords(50).stream()
                    .anyMatch(r -> r.message().contains("SPIR-V compilation failed"));
            Assertions.assertFalse(hasRecord, "disabled diagnostics must stay quiet");
            Assertions.assertFalse(VulkairisControllerHolder.lastError().isBlank(),
                    "status truth survives the toggle");

            VulkairisConfigManager.getConfig().setLogShaderCompileErrors(true);
            SpirvCompiler.compileShader("toggle_probe2", "this is not glsl", ShaderType.FRAGMENT);
            Assertions.assertTrue(DiagnosticManager.getRecentRecords(50).stream()
                    .anyMatch(r -> r.message().contains("SPIR-V compilation failed")));
        } finally {
            snapshot.restore();
        }
    }

    @Test
    public void testCompatServiceRunsRealChecks() {
        var report = CompatibilityTestService.runFixtureValidation(
                new AtomicBoolean(false), null);
        Assertions.assertEquals(CompatibilityTestService.StageStatus.PASSED, report.summary());
        Assertions.assertTrue(report.firstFailure().isEmpty());
        Assertions.assertTrue(report.durationMs() >= 0);
        List<String> names = CompatibilityTestService.planStageNames();
        Assertions.assertEquals(9, names.size());
        for (String name : names) {
            Assertions.assertTrue(report.stages().stream().anyMatch(s -> s.stage().startsWith(name)
                            || s.stage().equals(name)
                            || s.stage().contains(name.split(" ")[0])),
                    "every plan stage must appear: " + name);
        }
    }

    @Test
    public void testCompatServiceAsyncAndCancel() {
        var task = CompatibilityTestService.runFixtureValidationAsync(
                9001L, new AtomicBoolean(false), null);
        Optional<CompatibilityTestService.CompatibilityTestReport> report =
                TranslationWorker.takeIfCurrent(task, 9001L, 30_000L);
        Assertions.assertTrue(report.isPresent());
        Assertions.assertEquals(CompatibilityTestService.StageStatus.PASSED, report.get().summary());

        AtomicBoolean cancel = new AtomicBoolean(true);
        var cancelled = CompatibilityTestService.runFixtureValidation(cancel, null);
        Assertions.assertEquals(CompatibilityTestService.StageStatus.PARTIAL, cancelled.summary());

        var stale = CompatibilityTestService.runFixtureValidationAsync(
                9002L, new AtomicBoolean(false), null);
        Assertions.assertTrue(TranslationWorker.takeIfCurrent(stale, 9003L, 30_000L).isEmpty(),
                "stale generations must never install reports");
    }

    @Test
    public void testGeneralModelRows() {
        GeneralCategoryModel model = new GeneralCategoryModel();
        List<String> keys = model.rowsFor(GuiCategory.GENERAL).stream()
                .map(SettingRow::key).toList();
        for (String key : new String[]{"enabled", "autoBackend", "showStartupStatus",
                "startupValidation", "openPackFolder", "bridgeStatus"}) {
            Assertions.assertTrue(keys.contains(key), "missing General row: " + key);
        }
        Assertions.assertTrue(model.rowsFor(GuiCategory.RENDERING).isEmpty());
        Assertions.assertFalse(model.statusText("bridgeStatus").isBlank());
        Assertions.assertFalse(model.setBoolean("nope", true), "unknown keys are refused");
        var validation = model.runStartupValidation();
        Assertions.assertFalse(validation.checks().isEmpty());
    }

    @Test
    public void testRenderingModelRows() {
        ConfigSnapshot snapshot = new ConfigSnapshot();
        try {
            RenderingCategoryModel model = new RenderingCategoryModel();
            List<String> keys = model.rowsFor(GuiCategory.RENDERING).stream()
                    .map(SettingRow::key).toList();
            for (String key : new String[]{"backend", "allowOpenGlFallback", "runCompatTest",
                    "compatStatus", "logShaderCompileErrors", "dumpTranslatedSource",
                    "pipelineCacheEnabled", "clearPipelineCache", "backendStatus"}) {
                Assertions.assertTrue(keys.contains(key), "missing Rendering row: " + key);
            }
            Assertions.assertTrue(model.selectOptions("backend")
                    .contains(model.selectValue("backend")));
            Assertions.assertTrue(model.setSelect("backend", model.selectValue("backend")));
            Assertions.assertFalse(model.setSelect("nope", "x"));
            Assertions.assertFalse(model.setBoolean("nope", true));
            Assertions.assertEquals("Not tested", model.statusText("compatStatus"));
            Assertions.assertFalse(model.statusText("backendStatus").isBlank());
            Assertions.assertFalse(model.footerNotice() == null);
            model.runAction("nope");
            model.runAction("clearPipelineCache");
        } finally {
            snapshot.restore();
        }
    }

    @Test
    public void testModelComposition() {
        Assertions.assertTrue(VulkairisGuiModels.isReal(GuiCategory.GENERAL));
        Assertions.assertTrue(VulkairisGuiModels.isReal(GuiCategory.RENDERING));
        Assertions.assertTrue(VulkairisGuiModels.isReal(GuiCategory.SHADER_PACKS));
        Assertions.assertTrue(VulkairisGuiModels.isReal(GuiCategory.COMPATIBILITY));
        Assertions.assertTrue(VulkairisGuiModels.isReal(GuiCategory.DIAGNOSTICS));
        Assertions.assertTrue(VulkairisGuiModels.isReal(GuiCategory.ADVANCED));
    }

    /** Reads the controller error without importing controller statics into assertions. */
    static final class VulkairisControllerHolder {
        static String lastError() {
            return com.vulkairis.core.VulkairisController.getInstance().getLastCompileError();
        }
    }
}
