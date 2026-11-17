package com.vulkairis.client.gui;

import com.vulkairis.client.gui.services.DiagnosticReportService;
import com.vulkairis.config.VulkairisConfig;
import com.vulkairis.config.VulkairisConfigManager;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Diagnostics category: report collection, sanitized export, clipboard details, and the
 * verbosity/capture toggles. Copy Details always remains as a fallback when export fails.
 */
public class DiagnosticsCategoryModel extends AbstractCategoryModel {

    private volatile DiagnosticReportService.DiagnosticReport lastReport;
    private volatile String lastExportResult = "No export yet";
    private volatile String lastCopyResult = "";

    @Override
    public List<SettingRow> rowsFor(GuiCategory category) {
        if (category != GuiCategory.DIAGNOSTICS) {
            return List.of();
        }
        List<SettingRow> rows = new ArrayList<>();
        rows.add(new SettingRow("Collect report",
                "Gather versions, backend, pack, stages and a sanitized log excerpt.",
                "Nothing leaves the machine on collect.", SettingRow.Kind.ACTION, "collectReport"));
        rows.add(new SettingRow("Export text report",
                "Write a bounded text report for sharing.",
                "Personal paths are redacted.", SettingRow.Kind.ACTION, "exportText"));
        rows.add(new SettingRow("Export JSON report",
                "Write a bounded JSON report for tooling.",
                "Same content as text, structured.", SettingRow.Kind.ACTION, "exportJson"));
        rows.add(new SettingRow("Copy details",
                "Copy the concise summary to the clipboard.",
                "Fallback when export fails.", SettingRow.Kind.ACTION, "copyDetails"));
        rows.add(new SettingRow("Diagnostic verbosity",
                "Basic, Detailed or Developer log capture for reports.",
                "Developer mode is verbose.", SettingRow.Kind.SELECT, "diagnosticVerbosity"));
        rows.add(new SettingRow("Log compile errors",
                "Record shader compile failures with identifiers.",
                "Status stays truthful either way.", SettingRow.Kind.TOGGLE, "logShaderCompileErrors"));
        rows.add(new SettingRow("Include renderer capabilities",
                "Add expanded capability data to reports.",
                "Opt-in; reports can be large.", SettingRow.Kind.TOGGLE, "includeRendererCapabilities"));
        rows.add(new SettingRow("Report status",
                "What the last collection captured.",
                "Re-collect after changes.", SettingRow.Kind.STATUS, "reportStatus"));
        rows.add(new SettingRow("Last export",
                "Where the last export landed, or why it failed.",
                "Copy Details covers export failures.", SettingRow.Kind.STATUS, "lastExport"));
        return rows;
    }

    /** @return the last collected report, if any */
    public Optional<DiagnosticReportService.DiagnosticReport> lastReport() {
        return Optional.ofNullable(lastReport);
    }

    /**
     * Collects a report, optionally seeded with the latest staged validation.
     */
    public DiagnosticReportService.DiagnosticReport collect(
            com.vulkairis.client.gui.services.CompatibilityTestService.CompatibilityTestReport compat) {
        lastReport = DiagnosticReportService.collect(compat);
        touch();
        return lastReport;
    }

    /**
     * Exports the last report (collecting first when absent).
     *
     * @return human-readable outcome for the status row
     */
    public String exportTo(Path directory, boolean asJson) {
        if (lastReport == null) {
            collect(null);
        }
        try {
            Path written = DiagnosticReportService.export(lastReport, directory, asJson);
            lastExportResult = "Exported " + written.getFileName();
        } catch (DiagnosticReportService.ExportException e) {
            lastExportResult = "Export failed: " + e.getMessage();
        }
        touch();
        return lastExportResult;
    }

    /**
     * Copies the concise summary to the clipboard when a client is present.
     *
     * @return whether the clipboard accepted it (the text stays available either way)
     */
    public boolean copyDetails() {
        if (lastReport == null) {
            collect(null);
        }
        String summary = DiagnosticReportService.conciseSummary(lastReport);
        try {
            net.minecraft.client.Minecraft.getInstance().keyboardHandler.setClipboard(summary);
            lastCopyResult = "Copied " + summary.length() + " chars";
            touch();
            return true;
        } catch (Throwable t) {
            lastCopyResult = "Clipboard unavailable (" + t.getMessage()
                    + "); summary is " + summary.length() + " chars";
            touch();
            return false;
        }
    }

    /** @return the concise summary text (manual-copy fallback) */
    public String conciseSummary() {
        if (lastReport == null) {
            collect(null);
        }
        return DiagnosticReportService.conciseSummary(lastReport);
    }

    @Override
    public void resetCategory(GuiCategory category) {
        if (category != GuiCategory.DIAGNOSTICS) {
            return;
        }
        var defaults = new com.vulkairis.config.VulkairisConfig();
        var config = com.vulkairis.config.VulkairisConfigManager.getConfig();
        config.setDiagnosticVerbosity(defaults.getDiagnosticVerbosity());
        config.setLogShaderCompileErrors(defaults.isLogShaderCompileErrors());
        config.setIncludeRendererCapabilities(defaults.isIncludeRendererCapabilities());
        com.vulkairis.config.VulkairisConfigManager.saveConfig();
        touch();
    }

    /** @return default export directory, or empty outside a game */
    public Optional<Path> defaultExportDir() {
        try {
            return Optional.of(net.fabricmc.loader.api.FabricLoader.getInstance()
                    .getGameDir().resolve("vulkairis-reports"));
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    @Override
    public boolean booleanValue(String key) {
        return switch (key) {
            case "logShaderCompileErrors" ->
                    VulkairisConfigManager.getConfig().isLogShaderCompileErrors();
            case "includeRendererCapabilities" ->
                    VulkairisConfigManager.getConfig().isIncludeRendererCapabilities();
            default -> false;
        };
    }

    @Override
    public boolean setBoolean(String key, boolean requested) {
        switch (key) {
            case "logShaderCompileErrors":
                VulkairisConfigManager.getConfig().setLogShaderCompileErrors(requested);
                break;
            case "includeRendererCapabilities":
                VulkairisConfigManager.getConfig().setIncludeRendererCapabilities(requested);
                break;
            default:
                return false;
        }
        VulkairisConfigManager.saveConfig();
        touch();
        return true;
    }

    @Override
    public List<String> selectOptions(String key) {
        if ("diagnosticVerbosity".equals(key)) {
            return List.of("Basic", "Detailed", "Developer");
        }
        return List.of();
    }

    @Override
    public String selectValue(String key) {
        if ("diagnosticVerbosity".equals(key)) {
            return switch (VulkairisConfigManager.getConfig().getDiagnosticVerbosity()) {
                case BASIC -> "Basic";
                case DETAILED -> "Detailed";
                case DEVELOPER -> "Developer";
            };
        }
        return "";
    }

    @Override
    public boolean setSelect(String key, String value) {
        if (!"diagnosticVerbosity".equals(key)) {
            return false;
        }
        VulkairisConfig.DiagnosticVerbosity verbosity = switch (value) {
            case "Detailed" -> VulkairisConfig.DiagnosticVerbosity.DETAILED;
            case "Developer" -> VulkairisConfig.DiagnosticVerbosity.DEVELOPER;
            case "Basic" -> VulkairisConfig.DiagnosticVerbosity.BASIC;
            default -> null;
        };
        if (verbosity == null) {
            return false;
        }
        VulkairisConfigManager.getConfig().setDiagnosticVerbosity(verbosity);
        VulkairisConfigManager.saveConfig();
        touch();
        return true;
    }

    @Override
    public void runAction(String key) {
        switch (key) {
            case "collectReport" -> collect(null);
            case "exportText" -> defaultExportDir().ifPresentOrElse(
                    dir -> exportTo(dir, false),
                    () -> {
                        lastExportResult = "Export failed: no game directory";
                        touch();
                    });
            case "exportJson" -> defaultExportDir().ifPresentOrElse(
                    dir -> exportTo(dir, true),
                    () -> {
                        lastExportResult = "Export failed: no game directory";
                        touch();
                    });
            case "copyDetails" -> copyDetails();
            default -> {
            }
        }
    }

    @Override
    public StatusBadge.Status statusOf(String key) {
        if ("reportStatus".equals(key)) {
            return lastReport != null ? StatusBadge.Status.PASS : StatusBadge.Status.NEUTRAL;
        }
        if ("lastExport".equals(key)) {
            if (lastExportResult.startsWith("Exported")) {
                return StatusBadge.Status.PASS;
            }
            return lastExportResult.startsWith("No export")
                    ? StatusBadge.Status.NEUTRAL : StatusBadge.Status.FAIL;
        }
        return StatusBadge.Status.NEUTRAL;
    }

    @Override
    public String statusText(String key) {
        if ("reportStatus".equals(key)) {
            if (lastReport == null) {
                return "Not collected";
            }
            return "Collected " + lastReport.createdAt() + ", "
                    + lastReport.sections().size() + " sections";
        }
        if ("lastExport".equals(key)) {
            return lastExportResult;
        }
        if ("copyDetails".equals(key)) {
            return lastCopyResult.isEmpty() ? "Not copied" : lastCopyResult;
        }
        return "Not tested";
    }
}
