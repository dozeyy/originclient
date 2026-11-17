package com.vulkairis.test;

import com.vulkairis.client.gui.DiagnosticsCategoryModel;
import com.vulkairis.client.gui.GuiCategory;
import com.vulkairis.client.gui.SettingRow;
import com.vulkairis.client.gui.StatusBadge;
import com.vulkairis.client.gui.VulkairisGuiModels;
import com.vulkairis.client.gui.services.DiagnosticReportService;
import com.vulkairis.config.VulkairisConfig;
import com.vulkairis.config.VulkairisConfigManager;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/**
 * GUI plan Phase 7: sanitized, bounded diagnostic reports with export and clipboard
 * fallback, plus the wired Diagnostics category.
 */
public class DiagnosticReportTest {

    @Test
    public void testSanitizeRedactsPersonalPaths() {
        String home = System.getProperty("user.home");
        Assertions.assertEquals("see ~/shaders for details",
                DiagnosticReportService.sanitize("see " + home + "/shaders for details"),
                "user home must not leave the machine");
        Assertions.assertEquals("", DiagnosticReportService.sanitize(null));
        Assertions.assertEquals("", DiagnosticReportService.sanitize(""));
        Assertions.assertEquals("plain text",
                DiagnosticReportService.sanitize("plain text"));
    }

    @Test
    public void testCollectHasAllSections() {
        var report = DiagnosticReportService.collect(null);
        List<String> names = report.sections().stream()
                .map(DiagnosticReportService.ReportSection::name).toList();
        for (String section : new String[]{"Environment", "Backend", "Shader pack",
                "Stages", "Log excerpt"}) {
            Assertions.assertTrue(names.contains(section), "missing section: " + section);
        }
        Assertions.assertNotNull(report.createdAt());
    }

    @Test
    public void testLogExcerptBounded() {
        var report = DiagnosticReportService.collect(null);
        var excerpt = report.sections().stream()
                .filter(s -> s.name().equals("Log excerpt")).findFirst().orElseThrow();
        Assertions.assertTrue(excerpt.lines().size() <= DiagnosticReportService.MAX_LOG_LINES);
        int chars = excerpt.lines().stream().mapToInt(String::length).sum();
        Assertions.assertTrue(chars <= DiagnosticReportService.MAX_LOG_CHARS + 200);
    }

    @Test
    public void testConciseSummaryBounded() {
        var report = DiagnosticReportService.collect(null);
        String summary = DiagnosticReportService.conciseSummary(report);
        Assertions.assertTrue(summary.length() <= DiagnosticReportService.MAX_REPORT_CHARS + 50);
        Assertions.assertTrue(summary.contains("Environment"));
    }

    @Test
    public void testExportRoundTrip() throws Exception {
        Path dir = Files.createTempDirectory("vulkairis-report-test");
        try {
            var report = DiagnosticReportService.collect(null);
            Path text = DiagnosticReportService.export(report, dir, false);
            Assertions.assertTrue(Files.exists(text));
            Assertions.assertTrue(Files.readString(text).contains("Vulkairis diagnostic report"));
            Path json = DiagnosticReportService.export(report, dir, true);
            Assertions.assertTrue(Files.exists(json));
            String raw = Files.readString(json);
            Assertions.assertTrue(raw.contains("\"sections\""), "JSON must carry sections");
            Assertions.assertTrue(raw.trim().startsWith("{"));
        } finally {
            try (var stream = Files.walk(dir)) {
                stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                    }
                });
            }
        }
    }

    @Test
    public void testExportFailureActionable() {
        var report = DiagnosticReportService.collect(null);
        var missing = Assertions.assertThrows(
                DiagnosticReportService.ExportException.class,
                () -> DiagnosticReportService.export(report, null, false));
        Assertions.assertTrue(missing.getMessage().contains("no export directory"),
                missing.getMessage());
        var empty = Assertions.assertThrows(DiagnosticReportService.ExportException.class,
                () -> DiagnosticReportService.export(null, Path.of("."), false));
        Assertions.assertTrue(empty.getMessage().contains("nothing to export"));
    }

    @Test
    public void testDiagnosticsModelWired() {
        VulkairisConfigManager.getConfig().setDiagnosticVerbosity(
                VulkairisConfig.DiagnosticVerbosity.BASIC);
        VulkairisConfigManager.getConfig().setLogShaderCompileErrors(true);
        VulkairisConfigManager.getConfig().setIncludeRendererCapabilities(false);
        try {
            DiagnosticsCategoryModel model = new DiagnosticsCategoryModel();
            List<String> keys = model.rowsFor(GuiCategory.DIAGNOSTICS).stream()
                    .map(SettingRow::key).toList();
            for (String key : new String[]{"collectReport", "exportText", "exportJson",
                    "copyDetails", "diagnosticVerbosity", "logShaderCompileErrors",
                    "includeRendererCapabilities", "reportStatus", "lastExport"}) {
                Assertions.assertTrue(keys.contains(key), "missing Diagnostics row: " + key);
            }
            Assertions.assertEquals(List.of("Basic", "Detailed", "Developer"),
                    model.selectOptions("diagnosticVerbosity"));
            Assertions.assertEquals("Basic", model.selectValue("diagnosticVerbosity"));
            Assertions.assertTrue(model.setSelect("diagnosticVerbosity", "Developer"));
            Assertions.assertEquals(VulkairisConfig.DiagnosticVerbosity.DEVELOPER,
                    VulkairisConfigManager.getConfig().getDiagnosticVerbosity());
            Assertions.assertFalse(model.setSelect("diagnosticVerbosity", "Verbose"));
            Assertions.assertFalse(model.setBoolean("nope", true));
            Assertions.assertTrue(model.setBoolean("includeRendererCapabilities", true));
            Assertions.assertTrue(
                    VulkairisConfigManager.getConfig().isIncludeRendererCapabilities());

            var collected = model.collect(null);
            Assertions.assertEquals(5, collected.sections().size());
            Assertions.assertTrue(model.statusText("reportStatus").startsWith("Collected"));
            Assertions.assertFalse(model.conciseSummary().isBlank());

            // Clipboard needs a client; headless must fail soft with text retained.
            model.copyDetails();
            Assertions.assertFalse(model.conciseSummary().isBlank());

            // Export needs a game directory; headless must report, not crash or write.
            model.runAction("exportText");
            Assertions.assertTrue(model.statusText("lastExport").startsWith("Export failed"),
                    model.statusText("lastExport"));
            Assertions.assertEquals(StatusBadge.Status.FAIL, model.statusOf("lastExport"));
        } finally {
            VulkairisConfigManager.getConfig().setDiagnosticVerbosity(
                    VulkairisConfig.DiagnosticVerbosity.BASIC);
            VulkairisConfigManager.getConfig().setLogShaderCompileErrors(true);
            VulkairisConfigManager.getConfig().setIncludeRendererCapabilities(false);
        }
    }

    @Test
    public void testDiagnosticsModelReal() {
        Assertions.assertTrue(VulkairisGuiModels.isReal(GuiCategory.DIAGNOSTICS));
        Assertions.assertTrue(VulkairisGuiModels.forCategory(GuiCategory.DIAGNOSTICS)
                instanceof DiagnosticsCategoryModel);
    }
}
