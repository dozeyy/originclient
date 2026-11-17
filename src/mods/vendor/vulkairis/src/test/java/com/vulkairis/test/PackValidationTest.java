package com.vulkairis.test;

import com.vulkairis.client.gui.ShaderPacksCategoryModel;
import com.vulkairis.client.gui.services.CompatibilityTestService;
import com.vulkairis.translation.TranslationWorker;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * GUI plan Phase 6: staged pack validation — scan, per-pair transform/compile/reflection,
 * interface matching, family stages, and the honest summary for packs that compile but
 * cannot reach the screen.
 */
public class PackValidationTest {

    private static final String VERT = """
            #version 150 core
            in vec3 iris_Position;
            in vec2 iris_UV;
            uniform mat4 projection;
            out vec2 vUv;
            void main() {
                vUv = iris_UV;
                gl_Position = projection * vec4(iris_Position, 1.0);
            }
            """;

    private static final String GBUFFER_FRAG = """
            #version 150 core
            uniform sampler2D gtexture;
            in vec2 vUv;
            out vec4 fragColor;
            void main() {
                fragColor = texture(gtexture, vUv);
            }
            """;

    private static final String COMPOSITE_FRAG = """
            #version 150 core
            uniform sampler2D colortex0;
            in vec2 vUv;
            out vec4 fragColor;
            void main() {
                fragColor = texture(colortex0, vUv);
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
        return Files.createTempDirectory("vulkairis-packvalidation-test");
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

    private Path writePack(Path dir, String fileName, String[][] pairs) throws Exception {
        Path archive = dir.resolve(fileName);
        try (var out = new ZipOutputStream(Files.newOutputStream(archive))) {
            for (String[] pair : pairs) {
                out.putNextEntry(new ZipEntry("shaders/" + pair[0] + ".vsh"));
                out.write(pair[1].getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
                out.putNextEntry(new ZipEntry("shaders/" + pair[0] + ".fsh"));
                out.write(pair[2].getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return archive;
    }

    private static String[][] pair(String name, String frag) {
        return new String[][]{{name, VERT, frag}};
    }

    @Test
    public void testFullPackPassesAllStages() throws Exception {
        Path dir = tempDir();
        try {
            Path archive = writePack(dir, "Full.zip", new String[][]{
                    {"gbuffers_terrain", VERT, GBUFFER_FRAG},
                    {"composite", VERT, COMPOSITE_FRAG},
            });
            var report = CompatibilityTestService.runPackValidation(
                    archive, new AtomicBoolean(false), null);
            Assertions.assertEquals(CompatibilityTestService.StageStatus.PASSED, report.summary(),
                    report.stages().toString());
            Assertions.assertTrue(report.firstFailure().isEmpty());
            for (String name : CompatibilityTestService.planStageNames()) {
                Assertions.assertTrue(report.stages().stream()
                                .anyMatch(s -> s.stage().equals(name)),
                        "every plan stage must appear: " + name + " in " + report.stages());
            }
        } finally {
            cleanup(dir);
        }
    }

    @Test
    public void testBrokenPairFailsCompilationWithCause() throws Exception {
        Path dir = tempDir();
        try {
            Path archive = writePack(dir, "Broken.zip", pair("gbuffers_terrain", BROKEN_FRAG));
            var report = CompatibilityTestService.runPackValidation(
                    archive, new AtomicBoolean(false), null);
            Assertions.assertEquals(CompatibilityTestService.StageStatus.FAILED, report.summary());
            var failure = report.firstFailure().orElseThrow();
            Assertions.assertTrue(failure.stage().contains("Shader compilation"),
                    failure.toString());
            Assertions.assertFalse(failure.detail().isBlank(), "exact cause must be preserved");
        } finally {
            cleanup(dir);
        }
    }

    @Test
    public void testMismatchedInterfaceFailsResources() throws Exception {
        Path dir = tempDir();
        try {
            String vec3out = VERT.replace("out vec2 vUv;", "out vec3 vColor;")
                    .replace("vUv = iris_UV;", "vColor = vec3(1.0);")
                    .replace("gl_Position = projection", "gl_Position = projection");
            String vec2in = GBUFFER_FRAG.replace("in vec2 vUv;", "in vec2 vColor;");
            Path archive = writePack(dir, "Mismatch.zip", new String[][]{
                    {"gbuffers_terrain", vec3out, vec2in}});
            var report = CompatibilityTestService.runPackValidation(
                    archive, new AtomicBoolean(false), null);
            Assertions.assertEquals(CompatibilityTestService.StageStatus.FAILED, report.summary());
            Assertions.assertTrue(report.stages().stream()
                            .anyMatch(s -> s.stage().startsWith("Resource and descriptor")
                                    && s.status() == CompatibilityTestService.StageStatus.FAILED),
                    report.stages().toString());
        } finally {
            cleanup(dir);
        }
    }

    @Test
    public void testCompilingWithoutPostPathIsPartial() throws Exception {
        Path dir = tempDir();
        try {
            // Compiles cleanly but declares no composite/final path: the world renders
            // without shader effects. The report must say so, not claim success.
            Path archive = writePack(dir, "NoPost.zip", pair("gbuffers_terrain", GBUFFER_FRAG));
            var report = CompatibilityTestService.runPackValidation(
                    archive, new AtomicBoolean(false), null);
            Assertions.assertEquals(CompatibilityTestService.StageStatus.PARTIAL, report.summary(),
                    report.stages().toString());
            var summary = report.stages().stream()
                    .filter(s -> s.stage().equals("Result summary")).findFirst().orElseThrow();
            Assertions.assertTrue(summary.detail().contains("no composite/final post path"),
                    summary.toString());
        } finally {
            cleanup(dir);
        }
    }

    @Test
    public void testUnknownCompositeSamplerIsPartial() throws Exception {
        Path dir = tempDir();
        try {
            String weird = COMPOSITE_FRAG.replace("uniform sampler2D colortex0;",
                    "uniform sampler2D customTarget;");
            Path archive = writePack(dir, "Weird.zip", new String[][]{
                    {"gbuffers_terrain", VERT, GBUFFER_FRAG},
                    {"composite", VERT, weird.replace("colortex0", "customTarget")}});
            var report = CompatibilityTestService.runPackValidation(
                    archive, new AtomicBoolean(false), null);
            Assertions.assertTrue(report.stages().stream()
                            .anyMatch(s -> s.stage().equals("Composite/post-processing")
                                    && s.status() == CompatibilityTestService.StageStatus.PARTIAL),
                    report.stages().toString());
        } finally {
            cleanup(dir);
        }
    }

    @Test
    public void testMissingPairAndNullArchiveFailScan() throws Exception {
        Path dir = tempDir();
        try {
            Path lonely;
            try (var out = new ZipOutputStream(Files.newOutputStream(dir.resolve("Lonely.zip")))) {
                out.putNextEntry(new ZipEntry("shaders/gbuffers_terrain.vsh"));
                out.write(VERT.getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
            lonely = dir.resolve("Lonely.zip");
            var report = CompatibilityTestService.runPackValidation(
                    lonely, new AtomicBoolean(false), null);
            Assertions.assertEquals(CompatibilityTestService.StageStatus.FAILED, report.summary());
            Assertions.assertTrue(report.firstFailure().orElseThrow().stage().equals("Pack scan"));

            var nullReport = CompatibilityTestService.runPackValidation(
                    null, new AtomicBoolean(false), null);
            Assertions.assertEquals(CompatibilityTestService.StageStatus.FAILED,
                    nullReport.summary());
        } finally {
            cleanup(dir);
        }
    }

    @Test
    public void testCancelAndAsync() throws Exception {
        Path dir = tempDir();
        try {
            Path archive = writePack(dir, "Full.zip", new String[][]{
                    {"gbuffers_terrain", VERT, GBUFFER_FRAG},
                    {"composite", VERT, COMPOSITE_FRAG},
            });
            AtomicBoolean cancel = new AtomicBoolean(true);
            var cancelled = CompatibilityTestService.runPackValidation(archive, cancel, null);
            Assertions.assertEquals(CompatibilityTestService.StageStatus.PARTIAL,
                    cancelled.summary());

            var progress = new java.util.ArrayList<String>();
            var task = CompatibilityTestService.runPackValidationAsync(
                    archive, 8801L, new AtomicBoolean(false),
                    (name, done, total) -> progress.add(name + ":" + done + "/" + total));
            Optional<CompatibilityTestService.CompatibilityTestReport> report =
                    TranslationWorker.takeIfCurrent(task, 8801L, 30_000L);
            Assertions.assertTrue(report.isPresent());
            Assertions.assertFalse(progress.isEmpty(), "progress must advance per pair");
            Assertions.assertEquals(CompatibilityTestService.StageStatus.PASSED,
                    report.get().summary());

            var stale = CompatibilityTestService.runPackValidationAsync(
                    archive, 8802L, new AtomicBoolean(false), null);
            Assertions.assertTrue(TranslationWorker.takeIfCurrent(stale, 8803L, 30_000L).isEmpty());
        } finally {
            cleanup(dir);
        }
    }

    @Test
    public void testModelRunsFullValidation() throws Exception {
        Path dir = tempDir();
        try {
            writePack(dir, "Model.zip", new String[][]{
                    {"gbuffers_terrain", VERT, GBUFFER_FRAG},
                    {"composite", VERT, COMPOSITE_FRAG},
            });
            ShaderPacksCategoryModel model = new ShaderPacksCategoryModel();
            model.rescan(dir);
            Assertions.assertTrue(model.select("Model.zip"));
            var report = model.testSelectedFull(dir);
            Assertions.assertTrue(report.isPresent());
            Assertions.assertEquals(CompatibilityTestService.StageStatus.PASSED,
                    report.get().summary());
            Assertions.assertTrue(model.testStatusText().startsWith("Passed"),
                    model.testStatusText());
        } finally {
            cleanup(dir);
        }
    }
}
