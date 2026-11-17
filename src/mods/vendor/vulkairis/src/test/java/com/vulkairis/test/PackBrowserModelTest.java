package com.vulkairis.test;

import com.vulkairis.client.gui.ShaderPacksCategoryModel;
import com.vulkairis.client.gui.services.PackSmokeTest;
import com.vulkairis.client.gui.services.PackTestHistory;
import com.vulkairis.client.gui.services.ShaderPackService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * GUI plan Phase 5: pack smoke tests run real archives through the production pipeline, and
 * the browser model keeps selection distinct from test results with staleness tracking.
 */
public class PackBrowserModelTest {

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

    private static final String BROKEN_FRAG = """
            #version 150 core
            out vec4 fragColor;
            void main() {
                this is not valid glsl;
            }
            """;

    private Path tempDir() throws Exception {
        return Files.createTempDirectory("vulkairis-packbrowser-test");
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

    private void writePack(Path dir, String fileName, String frag) throws Exception {
        try (var out = new ZipOutputStream(Files.newOutputStream(dir.resolve(fileName)))) {
            out.putNextEntry(new ZipEntry("shaders/gbuffers_terrain.vsh"));
            out.write(VERT.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new ZipEntry("shaders/gbuffers_terrain.fsh"));
            out.write(frag.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
    }

    @Test
    public void testSmokeTestPassesAndFails() throws Exception {
        Path dir = tempDir();
        try {
            writePack(dir, "Good.zip", FRAG);
            writePack(dir, "Bad.zip", BROKEN_FRAG);
            var good = PackSmokeTest.test(dir.resolve("Good.zip"));
            Assertions.assertTrue(good.passed(), good.detail());
            Assertions.assertEquals(2, good.stages().size());
            var bad = PackSmokeTest.test(dir.resolve("Bad.zip"));
            Assertions.assertFalse(bad.passed());
            Assertions.assertTrue(bad.detail().contains("compilation"),
                    "failures name the stage: " + bad.detail());
            Assertions.assertFalse(PackSmokeTest.test(null).passed());
            Assertions.assertFalse(PackSmokeTest.test(dir.resolve("missing.zip")).passed());
        } finally {
            cleanup(dir);
        }
    }

    @Test
    public void testModelSelectTestAndStale() throws Exception {
        Path dir = tempDir();
        try {
            writePack(dir, "Pick.zip", FRAG);
            ShaderPacksCategoryModel model = new ShaderPacksCategoryModel();
            model.rescan(dir);
            Assertions.assertTrue(model.select("Pick.zip"));
            Assertions.assertEquals("Pick.zip", model.selectedFile().orElseThrow());
            Assertions.assertTrue(model.selectedPack().isPresent());
            Assertions.assertFalse(model.select("Ghost.zip"), "unknown files are refused");
            Assertions.assertEquals("Pick.zip", model.selectedFile().orElseThrow());

            var result = model.testSelected(dir);
            Assertions.assertTrue(result.isPresent() && result.get().passed());
            Assertions.assertTrue(model.testStatusText().startsWith("Passed"),
                    model.testStatusText());

            // Replace the file: the mark goes stale and a retest is required.
            try (var out = new ZipOutputStream(Files.newOutputStream(dir.resolve("Pick.zip")))) {
                out.putNextEntry(new ZipEntry("shaders/gbuffers_terrain.vsh"));
                byte[] bigger = (VERT + "\n// extra\n").getBytes(StandardCharsets.UTF_8);
                out.write(bigger);
                out.closeEntry();
                out.putNextEntry(new ZipEntry("shaders/gbuffers_terrain.fsh"));
                out.write(FRAG.getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
            model.rescan(dir);
            Assertions.assertEquals("Pick.zip", model.selectedFile().orElseThrow(),
                    "replaced files keep their selection");
            Assertions.assertTrue(model.testStatusText().startsWith("Stale"),
                    model.testStatusText());
            var retest = model.testSelected(dir);
            Assertions.assertTrue(retest.isPresent() && retest.get().passed());
            Assertions.assertTrue(model.testStatusText().startsWith("Passed"));
        } finally {
            cleanup(dir);
        }
    }

    @Test
    public void testSelectionClearedWhenFileVanishes() throws Exception {
        Path dir = tempDir();
        try {
            writePack(dir, "Gone.zip", FRAG);
            ShaderPacksCategoryModel model = new ShaderPacksCategoryModel();
            model.rescan(dir);
            Assertions.assertTrue(model.select("Gone.zip"));
            Files.delete(dir.resolve("Gone.zip"));
            model.rescan(dir);
            Assertions.assertTrue(model.selectedFile().isEmpty(),
                    "vanished files cannot stay selected");
            Assertions.assertTrue(model.selectedPack().isEmpty());
            Assertions.assertEquals("No pack selected", model.testStatusText());
        } finally {
            cleanup(dir);
        }
    }

    @Test
    public void testHistoryRetainsOnlyDiscovered() {
        PackTestHistory history = new PackTestHistory();
        history.record("A.zip", "1:2",
                new PackSmokeTest.SmokeResult(true, java.util.List.of(), "ok"));
        history.record("B.zip", "3:4",
                new PackSmokeTest.SmokeResult(false, java.util.List.of(), "bad"));
        Assertions.assertFalse(history.isStale("A.zip", "1:2"));
        Assertions.assertTrue(history.isStale("A.zip", "9:9"));
        Assertions.assertTrue(history.isStale("C.zip", "1:1"), "never tested counts as stale");
        history.retainOnly(java.util.Set.of("A.zip"));
        Assertions.assertTrue(history.markFor("B.zip").isEmpty());
        Assertions.assertEquals(1, history.snapshot().size());
    }

    @Test
    public void testCategoryRowsPresent() {
        ShaderPacksCategoryModel model = new ShaderPacksCategoryModel();
        var keys = model.rowsFor(com.vulkairis.client.gui.GuiCategory.SHADER_PACKS).stream()
                .map(com.vulkairis.client.gui.SettingRow::key).toList();
        for (String key : new String[]{"openBrowser", "rescan", "selectedPack", "lastTest"}) {
            Assertions.assertTrue(keys.contains(key), "missing browser row: " + key);
        }
        Assertions.assertTrue(model.rowsFor(com.vulkairis.client.gui.GuiCategory.GENERAL).isEmpty());
    }
}
