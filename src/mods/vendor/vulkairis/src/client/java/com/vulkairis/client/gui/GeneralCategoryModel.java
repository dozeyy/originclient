package com.vulkairis.client.gui;

import com.vulkairis.client.gui.services.RendererSettingsService;
import com.vulkairis.client.gui.services.StartupValidation;
import com.vulkairis.config.VulkairisConfigManager;
import com.vulkairis.core.BackendState;
import com.vulkairis.core.VulkairisController;

import java.util.ArrayList;
import java.util.List;

/**
 * General category: core enablement, startup behavior, folder access, initialization status.
 * Every row is wired to config, services or live controller state.
 */
public class GeneralCategoryModel extends AbstractCategoryModel {

    private volatile StartupValidation.Report validationReport;

    @Override
    public List<SettingRow> rowsFor(GuiCategory category) {
        if (category != GuiCategory.GENERAL) {
            return List.of();
        }
        List<SettingRow> rows = new ArrayList<>();
        rows.add(new SettingRow("Enable Vulkairis",
                "Master switch. OFF disables the bridge cleanly; applies on restart.",
                "Takes effect after a restart.", SettingRow.Kind.TOGGLE, "enabled"));
        rows.add(new SettingRow("Automatic backend selection",
                "Choose the renderer from capability checks. OFF uses the manual choice.",
                "Renderer is chosen at launch.", SettingRow.Kind.TOGGLE, "autoBackend"));
        rows.add(new SettingRow("Show startup status",
                "Display initialization state without flooding chat.",
                "Status appears once per launch.", SettingRow.Kind.TOGGLE, "showStartupStatus"));
        rows.add(new SettingRow("Startup validation",
                "Validate required capabilities before shader execution.",
                "Runs the startup checks on demand.", SettingRow.Kind.TOGGLE, "startupValidation"));
        rows.add(new SettingRow("Shader pack folder",
                "Open the configured Iris shader-pack directory.",
                "Same folder the pack browser scans.", SettingRow.Kind.ACTION, "openPackFolder"));
        rows.add(new SettingRow("Bridge status",
                "Current bridge state. See /vulkairis status for details.",
                "Not initialized, Ready to test, Degraded, or Failed.",
                SettingRow.Kind.STATUS, "bridgeStatus"));
        return rows;
    }

    @Override
    public boolean booleanValue(String key) {
        return switch (key) {
            case "enabled" -> VulkairisConfigManager.getConfig().isEnabled();
            case "autoBackend" -> VulkairisConfigManager.getConfig().isAutoBackend();
            case "showStartupStatus" -> VulkairisConfigManager.getConfig().isShowStartupStatus();
            case "startupValidation" -> VulkairisConfigManager.getConfig().isStartupValidation();
            default -> false;
        };
    }

    @Override
    public boolean setBoolean(String key, boolean requested) {
        RendererSettingsService.ApplyStatus status = switch (key) {
            case "enabled" -> RendererSettingsService.setEnabled(requested);
            case "autoBackend" -> requested
                    ? RendererSettingsService.setBackend("Auto")
                    : RendererSettingsService.setBackend(preferredManualBackend());
            case "showStartupStatus" -> RendererSettingsService.setShowStartupStatus(requested);
            case "startupValidation" -> RendererSettingsService.setStartupValidation(requested);
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
    public void runAction(String key) {
        if ("openPackFolder".equals(key)) {
            openShaderpackFolder();
        }
    }

    @Override
    public StatusBadge.Status statusOf(String key) {
        if (!"bridgeStatus".equals(key)) {
            return StatusBadge.Status.NEUTRAL;
        }
        BackendState state = VulkairisController.getInstance().getState();
        return switch (state) {
            case READY, SHADER_PIPELINE_READY -> StatusBadge.Status.PASS;
            case BRIDGE_ACTIVE, DEVICE_READY, INITIALIZING -> StatusBadge.Status.WARN;
            case FALLBACK, FALLBACK_GL -> StatusBadge.Status.WARN;
            case FAILED, SHUTDOWN -> StatusBadge.Status.FAIL;
            default -> StatusBadge.Status.NEUTRAL;
        };
    }

    @Override
    public String statusText(String key) {
        if (!"bridgeStatus".equals(key)) {
            return "Not tested";
        }
        BackendState state = VulkairisController.getInstance().getState();
        return switch (state) {
            case DISABLED, UNINITIALIZED -> "Not initialized";
            case READY, SHADER_PIPELINE_READY -> "Ready to test";
            case FALLBACK, FALLBACK_GL -> "Degraded: "
                    + com.vulkairis.diagnostics.DiagnosticManager.getLastFallbackReason();
            case FAILED, SHUTDOWN -> "Failed";
            default -> "Starting";
        };
    }

    /** Runs the startup checks on demand (also backing the validation toggle evidence). */
    public StartupValidation.Report runStartupValidation() {
        validationReport = StartupValidation.validate();
        touch();
        return validationReport;
    }

    @Override
    public void resetCategory(GuiCategory category) {
        if (category != GuiCategory.GENERAL) {
            return;
        }
        var defaults = new com.vulkairis.config.VulkairisConfig();
        var config = com.vulkairis.config.VulkairisConfigManager.getConfig();
        config.setEnabled(defaults.isEnabled());
        config.setAutoBackend(defaults.isAutoBackend());
        config.setShowStartupStatus(defaults.isShowStartupStatus());
        config.setStartupValidation(defaults.isStartupValidation());
        com.vulkairis.config.VulkairisConfigManager.saveConfig();
        touch();
    }

    private String preferredManualBackend() {
        List<String> offered = RendererSettingsService.offeredBackends();
        if (offered.contains("Vulkan")) {
            return "Vulkan";
        }
        return offered.contains("OpenGL") ? "OpenGL" : "Auto";
    }

    private void openShaderpackFolder() {
        try {
            java.nio.file.Path directory;
            try {
                directory = (java.nio.file.Path) Class.forName("net.irisshaders.iris.Iris")
                        .getMethod("getShaderpacksDirectory").invoke(null);
            } catch (Throwable t) {
                directory = null;
            }
            if (directory == null) {
                pendingNotice("Shader-pack folder unavailable: Iris is not installed.");
                return;
            }
            java.io.File folder = directory.toFile();
            if (!folder.isDirectory()) {
                pendingNotice("Shader-pack folder missing: " + directory);
                return;
            }
            net.minecraft.Util.getPlatform().openFile(folder);
        } catch (Throwable t) {
            pendingNotice("Could not open the folder: " + t.getMessage());
        }
    }
}
