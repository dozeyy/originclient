package com.vulkairis.client.gui.services;

import com.vulkairis.config.VulkairisConfig;
import com.vulkairis.config.VulkairisConfigManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Controlled settings boundary (GUI plan §5): every control writes through here, which
 * validates, persists, and reports whether a restart or reload is required. Nothing writes an
 * unvalidated value into the renderer; unknown keys are refused, never stored.
 */
public final class RendererSettingsService {

    private RendererSettingsService() {
    }

    /** @param applied whether the value took effect @param restartRequired renderer chosen at launch */
    public record ApplyStatus(boolean applied, boolean restartRequired, String reason) {
        public static ApplyStatus applied(boolean restartRequired, String reason) {
            return new ApplyStatus(true, restartRequired, reason);
        }

        public static ApplyStatus refused(String reason) {
            return new ApplyStatus(false, false, reason);
        }
    }

    /** Backend choices genuinely offered by this build. */
    public static List<String> offeredBackends() {
        return RendererCapabilityProvider.offeredBackends(
                VulkairisConfigManager.getConfig().isAllowOpenGlFallback());
    }

    /** @return the requested backend derived from config (Auto/Vulkan/OpenGL) */
    public static String requestedBackend() {
        VulkairisConfig config = VulkairisConfigManager.getConfig();
        if (config.isAutoBackend()) {
            return "Auto";
        }
        return config.isUseVulkanRenderer() ? "Vulkan" : "OpenGL";
    }

    /** @return the effective backend actually rendering right now */
    public static String effectiveBackend() {
        return RendererCapabilityProvider.snapshot().describeEffective();
    }

    public static ApplyStatus setEnabled(boolean enabled) {
        VulkairisConfigManager.getConfig().setEnabled(enabled);
        VulkairisConfigManager.saveConfig();
        return ApplyStatus.applied(true,
                enabled ? "bridge enables on restart" : "bridge disables on restart");
    }

    public static ApplyStatus setBackend(String backend) {
        if (!offeredBackends().contains(backend)) {
            return ApplyStatus.refused("backend '" + backend + "' is not offered by this build");
        }
        VulkairisConfig config = VulkairisConfigManager.getConfig();
        config.setAutoBackend("Auto".equals(backend));
        config.setUseVulkanRenderer(!"OpenGL".equals(backend));
        VulkairisConfigManager.saveConfig();
        return ApplyStatus.applied(true, "renderer is chosen at launch; restart to apply");
    }

    public static ApplyStatus setAllowOpenGlFallback(boolean allowed) {
        VulkairisConfigManager.getConfig().setAllowOpenGlFallback(allowed);
        VulkairisConfigManager.saveConfig();
        return ApplyStatus.applied(false, allowed ? "fallback permitted" : "fallback refused");
    }

    public static ApplyStatus setPipelineCacheEnabled(boolean enabled) {
        VulkairisConfigManager.getConfig().setPipelineCacheEnabled(enabled);
        VulkairisConfigManager.saveConfig();
        return ApplyStatus.applied(false,
                enabled ? "pipeline cache active" : "pipeline cache bypassed");
    }

    public static ApplyStatus setShowStartupStatus(boolean shown) {
        VulkairisConfigManager.getConfig().setShowStartupStatus(shown);
        VulkairisConfigManager.saveConfig();
        return ApplyStatus.applied(false, "applies immediately");
    }

    public static ApplyStatus setStartupValidation(boolean validate) {
        VulkairisConfigManager.getConfig().setStartupValidation(validate);
        VulkairisConfigManager.saveConfig();
        return ApplyStatus.applied(false, "applies immediately");
    }

    public static ApplyStatus setLoadSpreadingEnabled(boolean enabled) {
        VulkairisConfigManager.getConfig().setLoadSpreadingEnabled(enabled);
        try {
            com.vulkairis.translation.TranslationWorker.setLoadSpreadingEnabled(enabled);
        } catch (Throwable ignored) {
        }
        VulkairisConfigManager.saveConfig();
        return ApplyStatus.applied(false,
                enabled ? "load spreading active" : "load spreading disabled");
    }

    /** @return problems in the current persisted model (empty when valid) */
    public static List<String> currentProblems() {
        List<String> problems;
        try {
            problems = new ArrayList<>(VulkairisConfigManager.getConfig().validate());
        } catch (Throwable t) {
            problems = new ArrayList<>();
            problems.add("configuration unreadable: " + t.getMessage());
        }
        return problems;
    }
}
