package com.vulkairis.test;

import com.vulkairis.client.gui.AdvancedCategoryModel;
import com.vulkairis.client.gui.CompatibilityCategoryModel;
import com.vulkairis.client.gui.ExperimentalFlags;
import com.vulkairis.client.gui.GuiCategory;
import com.vulkairis.client.gui.GuiViewModel;
import com.vulkairis.client.gui.SettingRow;
import com.vulkairis.client.gui.ShaderPacksCategoryModel;
import com.vulkairis.client.gui.ToggleState;
import com.vulkairis.client.gui.VulkairisGuiModels;
import com.vulkairis.client.gui.VulkairisModMenu;
import com.vulkairis.client.gui.services.DiagnosticReportService;
import com.vulkairis.client.gui.services.ShaderPackService;
import com.vulkairis.config.VulkairisConfig;
import com.vulkairis.config.VulkairisConfigManager;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Phase F (§7) close-out: Rev 2 toggle contract across every category, scan-state matrix
 * (waiting/scanning/empty/ready/failed), model-level rescan reconciliation, the
 * run/mods refusal, export sanitize/bounds, and the Advanced gates.
 */
public class PhaseFGuiCloseoutTest {

    private static final String VERT = """
            #version 150 core
            in vec3 iris_Position;
            void main() {
                gl_Position = vec4(iris_Position, 1.0);
            }
            """;

    private static final String FRAG = """
            #version 150 core
            out vec4 fragColor;
            void main() {
                fragColor = vec4(1.0);
            }
            """;

    private Path tempDir(String prefix) throws Exception {
        return Files.createTempDirectory(prefix);
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
        }
    }

    private void writePack(Path dir, String fileName, String extra) throws Exception {
        try (var out = new ZipOutputStream(Files.newOutputStream(dir.resolve(fileName)))) {
            out.putNextEntry(new ZipEntry("shaders/gbuffers_terrain.vsh"));
            out.write((VERT + (extra == null ? "" : extra)).getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new ZipEntry("shaders/gbuffers_terrain.fsh"));
            out.write(FRAG.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
    }

    private static final class ConfigSnapshot {
        final boolean enabled;
        final boolean autoBackend;
        final boolean useVulkan;
        final boolean fallback;
        final boolean showStartup;
        final boolean startupValidation;
        final boolean cache;
        final boolean logErrors;
        final boolean dumpTranslated;
        final boolean resourceValidation;
        final boolean debugOverlay;
        final boolean includeCaps;
        final VulkairisConfig.DiagnosticVerbosity verbosity;
        final VulkairisConfig.CompatibilityMode compatMode;
        final Map<String, Boolean> flags;

        ConfigSnapshot() {
            var config = VulkairisConfigManager.getConfig();
            enabled = config.isEnabled();
            autoBackend = config.isAutoBackend();
            useVulkan = config.isUseVulkanRenderer();
            fallback = config.isAllowOpenGlFallback();
            showStartup = config.isShowStartupStatus();
            startupValidation = config.isStartupValidation();
            cache = config.isPipelineCacheEnabled();
            logErrors = config.isLogShaderCompileErrors();
            dumpTranslated = config.isDumpTranslatedSource();
            resourceValidation = config.isResourceValidation();
            debugOverlay = config.isDebugOverlay();
            includeCaps = config.isIncludeRendererCapabilities();
            verbosity = config.getDiagnosticVerbosity();
            compatMode = config.getCompatibilityMode();
            flags = new LinkedHashMap<>(config.getExperimentalFlags());
        }

        void restore() {
            var config = VulkairisConfigManager.getConfig();
            config.setEnabled(enabled);
            config.setAutoBackend(autoBackend);
            config.setUseVulkanRenderer(useVulkan);
            config.setAllowOpenGlFallback(fallback);
            config.setShowStartupStatus(showStartup);
            config.setStartupValidation(startupValidation);
            config.setPipelineCacheEnabled(cache);
            config.setLogShaderCompileErrors(logErrors);
            config.setDumpTranslatedSource(dumpTranslated);
            config.setResourceValidation(resourceValidation);
            config.setDebugOverlay(debugOverlay);
            config.setIncludeRendererCapabilities(includeCaps);
            config.setDiagnosticVerbosity(verbosity);
            config.setCompatibilityMode(compatMode);
            config.setExperimentalFlags(flags);
        }
    }

    @Test
    public void testToggleColorAndLabelChangeTogether() {
        for (boolean value : new boolean[]{true, false}) {
            String label = ToggleState.labelFor(value);
            int fill = ToggleState.fillFor(value);
            Assertions.assertFalse(label.isBlank(), "every toggle state needs a visible label");
            if (value) {
                Assertions.assertEquals("ON", label);
                Assertions.assertEquals(ToggleState.ON_FILL, fill);
            } else {
                Assertions.assertEquals("OFF", label);
                Assertions.assertEquals(ToggleState.OFF_FILL, fill);
            }
        }
        Assertions.assertNotEquals(ToggleState.fillFor(true), ToggleState.fillFor(false),
                "ON and OFF must never share a face color");
        // Failed applies roll back instead of displaying a lie.
        Assertions.assertFalse(ToggleState.resolveAfterClick(true, false, false));
        Assertions.assertTrue(ToggleState.resolveAfterClick(false, false, true));
        Assertions.assertTrue(ToggleState.resolveAfterClick(true, true, false));
        Assertions.assertFalse(ToggleState.resolveAfterClick(false, true, true));
    }

    @Test
    public void testEveryToggleRowHasTooltipAndRoundTrips() {
        ConfigSnapshot snapshot = new ConfigSnapshot();
        try {
            for (GuiCategory category : GuiCategory.values()) {
                GuiViewModel model = VulkairisGuiModels.forCategory(category);
                for (SettingRow row : model.rowsFor(category)) {
                    Assertions.assertFalse(row.title().isBlank(), category + " row title blank");
                    Assertions.assertFalse(row.tooltip().isBlank(),
                            category + "/" + row.key() + " must carry a tooltip");
                    if (row.kind() == SettingRow.Kind.TOGGLE) {
                        boolean before = model.booleanValue(row.key());
                        Assertions.assertTrue(model.setBoolean(row.key(), !before),
                                category + "/" + row.key() + " must accept a flip");
                        Assertions.assertEquals(!before, model.booleanValue(row.key()),
                                category + "/" + row.key() + " must persist the flip");
                        Assertions.assertTrue(model.setBoolean(row.key(), before),
                                category + "/" + row.key() + " must restore");
                        Assertions.assertEquals(before, model.booleanValue(row.key()));
                    }
                }
                Assertions.assertFalse(model.setBoolean("no-such-setting", true),
                        category + " must refuse unknown toggle keys");
            }
        } finally {
            snapshot.restore();
        }
    }

    @Test
    public void testScanStateMatrix() throws Exception {
        ShaderPacksCategoryModel fresh = new ShaderPacksCategoryModel();
        Assertions.assertEquals("waiting", fresh.scanState(), "no scan yet must read as waiting");

        Path dir = tempDir("vulkairis-phasef-states");
        try {
            ShaderPacksCategoryModel model = new ShaderPacksCategoryModel();
            model.rescan(dir);
            Assertions.assertEquals("empty", model.scanState(), "clean folder must read as empty");

            writePack(dir, "Solo.zip", null);
            model.rescan(dir);
            Assertions.assertEquals("ready", model.scanState());
            Assertions.assertEquals(1, ShaderPackService.packsOrEmpty(model.lastScan().orElseThrow()).size());

            var missing = ShaderPackService.scan(dir.resolve("nope"));
            Assertions.assertFalse(missing.scanned());
            ShaderPacksCategoryModel failed = new ShaderPacksCategoryModel();
            failed.rescan(dir.resolve("nope"));
            Assertions.assertEquals("failed", failed.scanState());
        } finally {
            cleanup(dir);
        }
    }

    @Test
    public void testAsyncScanInstallsOnModel() throws Exception {
        Path dir = tempDir("vulkairis-phasef-async");
        try {
            writePack(dir, "Async.zip", null);
            ShaderPacksCategoryModel model = new ShaderPacksCategoryModel();
            model.rescanAsync(dir);
            boolean installed = false;
            long deadline = System.currentTimeMillis() + 15_000L;
            while (System.currentTimeMillis() < deadline) {
                if (model.collectScan()) {
                    installed = true;
                    break;
                }
                Thread.sleep(25L);
            }
            Assertions.assertTrue(installed, "async scan on open must install");
            Assertions.assertEquals("ready", model.scanState());
            Assertions.assertEquals(1, ShaderPackService.packsOrEmpty(
                    model.lastScan().orElseThrow()).size());
        } finally {
            cleanup(dir);
        }
    }

    @Test
    public void testRescanReconcilesAddRemoveReplaceDuplicateCorrupt() throws Exception {
        Path dir = tempDir("vulkairis-phasef-reconcile");
        try {
            writePack(dir, "Alpha.zip", null);
            ShaderPacksCategoryModel model = new ShaderPacksCategoryModel();
            model.rescan(dir);
            Assertions.assertTrue(model.select("Alpha.zip"));
            model.history().record("Alpha.zip",
                    model.selectedPack().orElseThrow().contentStamp(),
                    new com.vulkairis.client.gui.services.PackSmokeTest.SmokeResult(
                            true, List.of(), "ok"));

            // Add + duplicate + corrupt reconcile on rescan.
            writePack(dir, "Beta.zip", "\n// beta\n");
            Files.copy(dir.resolve("Alpha.zip"), dir.resolve("AlphaCopy.zip"));
            Files.write(dir.resolve("Corrupt.zip"), new byte[]{0x50, 0x4B, 0x03, 0x04, 0x00});
            model.rescan(dir);
            List<String> names = ShaderPackService.packsOrEmpty(model.lastScan().orElseThrow())
                    .stream().map(ShaderPackService.DiscoveredPack::fileName).toList();
            Assertions.assertTrue(names.contains("Alpha.zip") && names.contains("Beta.zip"),
                    "added packs appear: " + names);
            Assertions.assertTrue(model.lastScan().orElseThrow().warnings().stream()
                            .anyMatch(w -> w.fileName().equals("AlphaCopy.zip")
                                    && w.reason().contains("duplicate")),
                    "content duplicates warn: " + model.lastScan().orElseThrow().warnings());
            Assertions.assertTrue(model.lastScan().orElseThrow().warnings().stream()
                            .anyMatch(w -> w.fileName().equals("Corrupt.zip")),
                    "corrupt archives warn: " + model.lastScan().orElseThrow().warnings());
            Assertions.assertEquals("Alpha.zip", model.selectedFile().orElseThrow(),
                    "surviving selections stick across rescan");

            // Remove reconciles: vanished files cannot stay selected, history is pruned.
            Files.delete(dir.resolve("Alpha.zip"));
            Files.delete(dir.resolve("AlphaCopy.zip"));
            Files.delete(dir.resolve("Corrupt.zip"));
            model.rescan(dir);
            Assertions.assertTrue(model.selectedFile().isEmpty(),
                    "vanished files cannot stay selected");
            Assertions.assertTrue(model.history().markFor("Alpha.zip").isEmpty(),
                    "history is pruned to discovered packs");

            // Replace reconciles: same name, new stamp -> stale until retested.
            Assertions.assertTrue(model.select("Beta.zip"));
            String stamp = model.selectedPack().orElseThrow().contentStamp();
            model.history().record("Beta.zip", stamp,
                    new com.vulkairis.client.gui.services.PackSmokeTest.SmokeResult(
                            true, List.of(), "ok"));
            Assertions.assertFalse(model.testStatusText().startsWith("Stale"));
            writePack(dir, "Beta.zip", "\n// replaced\n");
            model.rescan(dir);
            Assertions.assertEquals("Beta.zip", model.selectedFile().orElseThrow(),
                    "replaced files keep their selection");
            Assertions.assertTrue(model.testStatusText().startsWith("Stale"),
                    "changed files require a retest: " + model.testStatusText());
            var retest = model.testSelected(dir);
            Assertions.assertTrue(retest.isPresent() && retest.get().passed());
            Assertions.assertTrue(model.testStatusText().startsWith("Passed"),
                    model.testStatusText());
        } finally {
            cleanup(dir);
        }
    }

    @Test
    public void testModsFolderAndJarsNeverBecomePacks() throws Exception {
        Path parent = tempDir("vulkairis-phasef-mods");
        try {
            Path mods = parent.resolve("mods");
            Files.createDirectories(mods);
            writePack(mods, "SodiumPlus.zip", null);

            var refused = ShaderPackService.scan(mods);
            Assertions.assertFalse(refused.scanned(), "run/mods must never scan as packs");
            Assertions.assertTrue(refused.error().contains("mods"), refused.error());
            Assertions.assertTrue(ShaderPackService.packsOrEmpty(refused).isEmpty());

            ShaderPacksCategoryModel model = new ShaderPacksCategoryModel();
            model.rescan(mods);
            Assertions.assertEquals("failed", model.scanState());
            Assertions.assertTrue(model.selectedFile().isEmpty());

            // Fabric mod jars sitting beside zips are skipped, never cards.
            Path packs = parent.resolve("shaderpacks");
            Files.createDirectories(packs);
            writePack(packs, "Real.zip", null);
            try (var out = new ZipOutputStream(Files.newOutputStream(packs.resolve("sodium.jar")))) {
                out.putNextEntry(new ZipEntry("fabric.mod.json"));
                out.write("{}".getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
            var result = ShaderPackService.scan(packs);
            Assertions.assertTrue(result.scanned());
            Assertions.assertEquals(1, result.packs().size(), "jars must never become cards");
            Assertions.assertEquals("Real.zip", result.packs().get(0).fileName());
        } finally {
            cleanup(parent);
        }
    }

    @Test
    public void testSanitizeRedactsUsernamesAndProfiles() {
        String home = System.getProperty("user.home");
        String user = System.getProperty("user.name");
        Assertions.assertFalse(
                DiagnosticReportService.sanitize("see " + home + "/shaders").contains(home));

        String otherWin = "failed at C:\\Users\\SomeOtherUser\\shaderpacks\\A.zip";
        String redactedWin = DiagnosticReportService.sanitize(otherWin);
        Assertions.assertFalse(redactedWin.contains("SomeOtherUser"), redactedWin);
        Assertions.assertTrue(redactedWin.contains("<user>"), redactedWin);

        String otherUnix = "failed at /home/otheruser/.minecraft/shaderpacks";
        String redactedUnix = DiagnosticReportService.sanitize(otherUnix);
        Assertions.assertFalse(redactedUnix.contains("otheruser"), redactedUnix);
        Assertions.assertTrue(redactedUnix.contains("<user>"), redactedUnix);

        if (user != null && user.length() >= 2) {
            String segment = "profile/" + user + "/packs";
            String redacted = DiagnosticReportService.sanitize(segment);
            Assertions.assertFalse(redacted.contains(user), redacted);
        }
        // Pack display names and bare words survive: only path segments are redacted.
        Assertions.assertEquals("BSL_v10.1.3", DiagnosticReportService.sanitize("BSL_v10.1.3"));
        Assertions.assertEquals("", DiagnosticReportService.sanitize(null));
    }

    @Test
    public void testReportBoundsAndExportRoundTrip() throws Exception {
        List<DiagnosticReportService.ReportSection> sections = new ArrayList<>();
        String bigLine = "x".repeat(2000);
        for (int section = 0; section < 60; section++) {
            List<String> lines = new ArrayList<>();
            for (int line = 0; line < 60; line++) {
                lines.add("line " + line + " " + bigLine);
            }
            sections.add(new DiagnosticReportService.ReportSection("Section-" + section, lines));
        }
        var huge = new DiagnosticReportService.DiagnosticReport(
                java.time.Instant.now(), List.copyOf(sections));
        Assertions.assertTrue(
                DiagnosticReportService.renderText(huge).length()
                        <= DiagnosticReportService.MAX_REPORT_CHARS + 50);
        Assertions.assertTrue(
                DiagnosticReportService.renderJson(huge).length()
                        <= DiagnosticReportService.MAX_REPORT_CHARS + 50);
        Assertions.assertTrue(
                DiagnosticReportService.conciseSummary(huge).length()
                        <= DiagnosticReportService.MAX_REPORT_CHARS + 50);

        Path reports = tempDir("vulkairis-phasef-export").resolve("vulkairis-reports");
        try {
            var report = DiagnosticReportService.collect(null);
            Path text = DiagnosticReportService.export(report, reports, false);
            Assertions.assertTrue(Files.exists(text), "text export must land");
            Assertions.assertTrue(
                    Files.readString(text).contains("Vulkairis diagnostic report"));
            Path json = DiagnosticReportService.export(report, reports, true);
            Assertions.assertTrue(Files.exists(json), "JSON export must land");
            Assertions.assertTrue(Files.readString(json).contains("\"sections\""));
            // Exported content is sanitized + bounded too.
            String home = System.getProperty("user.home");
            if (home != null && !home.isBlank()) {
                Assertions.assertFalse(Files.readString(text).contains(home));
            }
        } finally {
            cleanup(reports.getParent());
        }

        var report = DiagnosticReportService.collect(null);
        var noDir = Assertions.assertThrows(
                DiagnosticReportService.ExportException.class,
                () -> DiagnosticReportService.export(report, null, false));
        Assertions.assertTrue(noDir.getMessage().contains("no export directory"));
        var noReport = Assertions.assertThrows(
                DiagnosticReportService.ExportException.class,
                () -> DiagnosticReportService.export(null, Path.of("."), false));
        Assertions.assertTrue(noReport.getMessage().contains("nothing to export"));
    }

    @Test
    public void testAdvancedCloseoutGates() throws Exception {
        ConfigSnapshot snapshot = new ConfigSnapshot();
        try {
            // Only implemented flags are ever exposed; none exist, all default OFF.
            Assertions.assertTrue(ExperimentalFlags.implemented().isEmpty());
            Assertions.assertFalse(
                    VulkairisConfigManager.getConfig().isExperimentalFlag("anything"));

            AdvancedCategoryModel model = new AdvancedCategoryModel();
            Assertions.assertFalse(model.offersCacheInvalidation(),
                    "no cache-invalidation control until a Phase C disk-cache API exists");
            Assertions.assertEquals("No experimental flags implemented",
                    model.statusText("experimentalFlags"));
            Assertions.assertFalse(model.statusText("pipelineCacheState").isBlank());
            Assertions.assertFalse(model.setBoolean("nope", true));
            Assertions.assertFalse(model.booleanValue("nope"));

            // Safe reset touches only Vulkairis options: packs and worlds on disk survive,
            // and unrelated config keys are untouched by the category reset.
            Path scratch = tempDir("vulkairis-phasef-reset");
            try {
                Path pack = scratch.resolve("KeepMe.zip");
                writePack(scratch, "KeepMe.zip", null);
                Path world = scratch.resolve("world");
                Files.createDirectories(world);
                Files.writeString(world.resolve("level.dat"), "world-data");
                boolean enabledBefore = VulkairisConfigManager.getConfig().isEnabled();

                model.resetCategory(GuiCategory.ADVANCED);

                Assertions.assertTrue(Files.exists(pack), "packs survive a safe reset");
                Assertions.assertTrue(Files.exists(world.resolve("level.dat")),
                        "worlds survive a safe reset");
                Assertions.assertEquals(enabledBefore,
                        VulkairisConfigManager.getConfig().isEnabled(),
                        "category reset must not touch other categories' keys");
                Assertions.assertTrue(
                        VulkairisConfigManager.getConfig().getExperimentalFlags().isEmpty());
            } finally {
                cleanup(scratch);
            }
        } finally {
            snapshot.restore();
        }
    }

    @Test
    public void testModMenuAndCompatibilityWiringIntact() {
        Assertions.assertNotNull(new VulkairisModMenu().getModConfigScreenFactory(),
                "ModMenu entry must stay wired");
        for (GuiCategory category : GuiCategory.values()) {
            Assertions.assertTrue(VulkairisGuiModels.isReal(category), category + " must be real");
            Assertions.assertFalse(VulkairisGuiModels.forCategory(category)
                    .rowsFor(category).isEmpty(), category + " must have rows");
        }
        CompatibilityCategoryModel compat = new CompatibilityCategoryModel();
        List<String> keys = compat.rowsFor(GuiCategory.COMPATIBILITY).stream()
                .map(SettingRow::key).toList();
        for (String key : new String[]{"runTest", "strictFailures", "copyDetails",
                "testStatus", "firstFailure"}) {
            Assertions.assertTrue(keys.contains(key), "missing Compatibility row: " + key);
        }
        Assertions.assertFalse(compat.isTestRunning());
    }
}
