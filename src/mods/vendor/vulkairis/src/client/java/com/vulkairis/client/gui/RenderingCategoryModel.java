package com.vulkairis.client.gui;

import com.vulkairis.client.gui.services.CompatibilityTestService;
import com.vulkairis.client.gui.services.RendererCapabilityProvider;
import com.vulkairis.client.gui.services.RendererSettingsService;
import com.vulkairis.config.VulkairisConfigManager;
import com.vulkairis.translation.TranslationWorker;
import com.vulkairis.vulkan.pipeline.PipelineManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Rendering category: backend choice, fallback, compatibility testing, compile diagnostics,
 * pipeline cache, and backend status. Every row is wired to services or live state.
 */
public class RenderingCategoryModel extends AbstractCategoryModel {

    private volatile CompatibilityTestService.CompatibilityTestReport lastReport;
    private volatile boolean testRunning;
    private final AtomicBoolean cancelFlag = new AtomicBoolean(false);

    @Override
    public List<SettingRow> rowsFor(GuiCategory category) {
        if (category != GuiCategory.RENDERING) {
            return List.of();
        }
        List<SettingRow> rows = new ArrayList<>();
        rows.add(new SettingRow("Render backend",
                "Preferred renderer. Only genuinely supported paths are offered; the renderer "
                        + "is chosen at launch, so changes need a restart.",
                "Auto is recommended.", SettingRow.Kind.SELECT, "backend"));
        rows.add(new SettingRow("OpenGL fallback",
                "Permit falling back to OpenGL where a valid fallback path exists.",
                "The reason is always recorded.", SettingRow.Kind.TOGGLE, "allowOpenGlFallback"));
        rows.add(new SettingRow("Shader compatibility test",
                "Run the shared fixture validation through the real pipeline.",
                "Stages without an execution path report as not supported.",
                SettingRow.Kind.ACTION, "runCompatTest"));
        rows.add(new SettingRow("Compatibility status",
                "Last validation result and first failure.",
                "Re-run after changing packs or settings.", SettingRow.Kind.STATUS, "compatStatus"));
        rows.add(new SettingRow("Shader compile diagnostics",
                "Record compilation failures with stage, pack and shader identifier.",
                "Status stays truthful either way.", SettingRow.Kind.TOGGLE, "logShaderCompileErrors"));
        rows.add(new SettingRow("Translated source dumps",
                "Write translated GLSL on demand for debugging.",
                "Only writes when enabled.", SettingRow.Kind.TOGGLE, "dumpTranslatedSource"));
        rows.add(new SettingRow("Pipeline cache",
                "Reuse driver pipeline data across launches. Bypass when corrupt.",
                "Validated implementation with corruption recovery.",
                SettingRow.Kind.TOGGLE, "pipelineCacheEnabled"));
        rows.add(new SettingRow("Clear pipeline cache",
                "Destroy cached pipelines and delete the disk blob.",
                "Use after driver or shader changes.", SettingRow.Kind.ACTION, "clearPipelineCache"));
        rows.add(new SettingRow("Backend status",
                "Detected capabilities, requested vs effective backend, restart need.",
                "Effective values, never guesses.", SettingRow.Kind.STATUS, "backendStatus"));
        return rows;
    }

    @Override
    public boolean booleanValue(String key) {
        return switch (key) {
            case "allowOpenGlFallback" ->
                    VulkairisConfigManager.getConfig().isAllowOpenGlFallback();
            case "logShaderCompileErrors" ->
                    VulkairisConfigManager.getConfig().isLogShaderCompileErrors();
            case "dumpTranslatedSource" ->
                    VulkairisConfigManager.getConfig().isDumpTranslatedSource();
            case "pipelineCacheEnabled" ->
                    VulkairisConfigManager.getConfig().isPipelineCacheEnabled();
            default -> false;
        };
    }

    @Override
    public boolean setBoolean(String key, boolean requested) {
        RendererSettingsService.ApplyStatus status = switch (key) {
            case "allowOpenGlFallback" -> RendererSettingsService.setAllowOpenGlFallback(requested);
            case "logShaderCompileErrors" -> {
                VulkairisConfigManager.getConfig().setLogShaderCompileErrors(requested);
                VulkairisConfigManager.saveConfig();
                yield RendererSettingsService.ApplyStatus.applied(false, "applies immediately");
            }
            case "dumpTranslatedSource" -> {
                VulkairisConfigManager.getConfig().setDumpTranslatedSource(requested);
                VulkairisConfigManager.saveConfig();
                yield RendererSettingsService.ApplyStatus.applied(false, "applies immediately");
            }
            case "pipelineCacheEnabled" ->
                    RendererSettingsService.setPipelineCacheEnabled(requested);
            default -> RendererSettingsService.ApplyStatus.refused("unknown setting '" + key + "'");
        };
        if (!status.applied()) {
            return false;
        }
        if (status.restartRequired()) {
            pendingNotice("Restart required: " + status.reason());
        }
        touch();
        return true;
    }

    @Override
    public List<String> selectOptions(String key) {
        if ("backend".equals(key)) {
            return RendererSettingsService.offeredBackends();
        }
        return List.of();
    }

    @Override
    public String selectValue(String key) {
        if ("backend".equals(key)) {
            return RendererSettingsService.requestedBackend();
        }
        return "";
    }

    @Override
    public boolean setSelect(String key, String value) {
        if (!"backend".equals(key)) {
            return false;
        }
        RendererSettingsService.ApplyStatus status = RendererSettingsService.setBackend(value);
        if (!status.applied()) {
            return false;
        }
        pendingNotice("Restart required: " + status.reason());
        touch();
        return true;
    }

    @Override
    public void runAction(String key) {
        if ("clearPipelineCache".equals(key)) {
            PipelineManager.invalidateCache();
            pendingNotice("Pipeline cache cleared.");
        } else if ("runCompatTest".equals(key)) {
            launchCompatibilityTest();
        }
    }

    @Override
    public StatusBadge.Status statusOf(String key) {
        if ("backendStatus".equals(key)) {
            var capabilities = RendererCapabilityProvider.snapshot();
            if (!capabilities.vulkanModPresent() && !capabilities.irisPresent()) {
                return StatusBadge.Status.WARN;
            }
            return capabilities.deviceLive() ? StatusBadge.Status.PASS : StatusBadge.Status.NEUTRAL;
        }
        if ("compatStatus".equals(key)) {
            if (testRunning) {
                return StatusBadge.Status.WARN;
            }
            if (lastReport == null) {
                return StatusBadge.Status.NEUTRAL;
            }
            return switch (lastReport.summary()) {
                case PASSED -> StatusBadge.Status.PASS;
                case FAILED -> StatusBadge.Status.FAIL;
                default -> StatusBadge.Status.WARN;
            };
        }
        return StatusBadge.Status.NEUTRAL;
    }

    @Override
    public String statusText(String key) {
        if ("backendStatus".equals(key)) {
            var capabilities = RendererCapabilityProvider.snapshot();
            String effective = capabilities.describeEffective();
            String requested = RendererSettingsService.requestedBackend();
            String fallback = com.vulkairis.diagnostics.DiagnosticManager.getLastFallbackReason();
            String text = requested + " requested, " + effective + " active";
            if (!"None (Backend healthy)".equals(fallback)) {
                text += " — " + fallback;
            }
            return text;
        }
        if ("compatStatus".equals(key)) {
            if (testRunning) {
                return "Test running…";
            }
            if (lastReport == null) {
                return "Not tested";
            }
            var failure = lastReport.firstFailure();
            if (failure.isPresent()) {
                return lastReport.summary() + ": " + failure.get().stage();
            }
            return lastReport.summary() + ": " + lastReport.stages().size()
                    + " stages, " + lastReport.durationMs() + "ms";
        }
        return "Not tested";
    }

    /** Launches the validation off-thread; the model refreshes when the report lands. */
    public void launchCompatibilityTest() {
        if (testRunning) {
            return;
        }
        testRunning = true;
        cancelFlag.set(false);
        touch();
        long generation;
        try {
            generation = com.vulkairis.iris.SourceProvenance.currentGeneration();
        } catch (Throwable t) {
            generation = 0L;
        }
        final long taskGeneration = generation;
        TranslationWorker.Task<CompatibilityTestService.CompatibilityTestReport> task =
                CompatibilityTestService.runFixtureValidationAsync(taskGeneration, cancelFlag, null);
        TranslationWorker.submit("compat-test-collect", taskGeneration, () -> {
            Optional<CompatibilityTestService.CompatibilityTestReport> report =
                    TranslationWorker.takeIfCurrent(task, taskGeneration, 120_000L);
            report.ifPresent(landed -> lastReport = landed);
            testRunning = false;
            touch();
            return null;
        });
    }

    /** @return whether a test is currently running (screen shows progress state) */
    public boolean isTestRunning() {
        return testRunning;
    }

    @Override
    public void resetCategory(GuiCategory category) {
        if (category != GuiCategory.RENDERING) {
            return;
        }
        var defaults = new com.vulkairis.config.VulkairisConfig();
        var config = com.vulkairis.config.VulkairisConfigManager.getConfig();
        config.setAutoBackend(defaults.isAutoBackend());
        config.setUseVulkanRenderer(defaults.isUseVulkanRenderer());
        config.setAllowOpenGlFallback(defaults.isAllowOpenGlFallback());
        config.setLogShaderCompileErrors(defaults.isLogShaderCompileErrors());
        config.setDumpTranslatedSource(defaults.isDumpTranslatedSource());
        config.setPipelineCacheEnabled(defaults.isPipelineCacheEnabled());
        com.vulkairis.config.VulkairisConfigManager.saveConfig();
        touch();
    }

    /** @return the last completed report, or empty when none */
    public Optional<CompatibilityTestService.CompatibilityTestReport> lastReport() {
        return Optional.ofNullable(lastReport);
    }
}
