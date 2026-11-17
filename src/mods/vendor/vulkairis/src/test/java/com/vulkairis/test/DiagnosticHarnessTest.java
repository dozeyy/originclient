package com.vulkairis.test;

import com.vulkairis.client.render.BridgeTestRenderer;
import com.vulkairis.config.VulkairisConfig;
import com.vulkairis.core.DebugRenderMode;
import com.vulkairis.diagnostics.DiagnosticDumps;
import com.vulkairis.diagnostics.RegressionCaptures;
import com.vulkairis.testpack.ShaderPackTestHarness;
import com.vulkairis.testpack.ShaderTestPack;
import com.vulkairis.testpack.ShaderTestPacks;
import com.vulkairis.translation.SpirvCompiler;
import com.vulkairis.translation.VulkairisShader;
import com.vulkairis.vulkan.pipeline.PipelineCompatibilityValidator;
import net.irisshaders.iris.gl.shader.ShaderType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/**
 * Phase 1: the diagnostic harness is observable and safe by default.
 *
 * <ul>
 *   <li>All dump/trace flags default off, so normal runs produce no dump I/O.</li>
 *   <li>The legacy test-draw path stays debug-only and unreachable from normal rendering.</li>
 *   <li>New fixtures (alpha blend, depth I/O) reach REFLECTED; the mismatched-interface
 *   fixture is rejected at link validation, never bound.</li>
 *   <li>Both named visual regressions carry fixed capture parameters for future runs.</li>
 * </ul>
 */
public class DiagnosticHarnessTest {

    @Test
    public void testDumpAndTraceFlagsDefaultOff() {
        VulkairisConfig config = new VulkairisConfig();
        Assertions.assertFalse(config.isDumpSpirv(), "SPIR-V dump must be opt-in");
        Assertions.assertFalse(config.isDumpTranslatedSource(), "translated-source dump must be opt-in");
        Assertions.assertFalse(config.isDumpReflection(), "reflection dump must be opt-in");
        Assertions.assertFalse(config.isPerPassCapture(), "per-pass capture must be opt-in");
        Assertions.assertFalse(config.isTraceLogging(), "trace logging must be opt-in");
    }

    @Test
    public void testDebugDrawDefaultsOff() {
        Assertions.assertEquals(DebugRenderMode.OFF, new VulkairisConfig().getDebugRenderMode(),
                "a fresh config must never draw the test quad over menus or worlds");
    }

    @Test
    public void testConfigManagerIsHeadlessSafe() {
        Assertions.assertDoesNotThrow(
                () -> com.vulkairis.config.ConfigManager.getConfig(),
                "reading config must not require a live Fabric game (unit-test JVM)");
        Assertions.assertNotNull(com.vulkairis.config.ConfigManager.getConfig());
    }

    @Test
    public void testLegacyTestRendererStaysDebugOnly() {
        Assertions.assertNotNull(
                BridgeTestRenderer.class.getAnnotation(Deprecated.class),
                "BridgeTestRenderer must stay marked as a dead debug-only path");
    }

    @Test
    public void testDumpsAreNoOpsByDefault() throws Exception {
        Path probe = Path.of("run", "vulkairis-dumps");
        boolean existed = Files.exists(probe);
        long before = existed ? Files.list(probe).count() : 0;

        Assertions.assertNull(DiagnosticDumps.dumpTranslatedSource("probe", "void main() {}"));
        Assertions.assertNull(DiagnosticDumps.dumpReflection("probe", "summary"));
        Assertions.assertNull(DiagnosticDumps.dumpSpirv("probe", null));

        if (!existed) {
            Assertions.assertFalse(Files.exists(probe),
                    "disabled dumps must not create the dump directory");
        } else {
            Assertions.assertEquals(before, Files.list(probe).count(),
                    "disabled dumps must not write files");
        }
    }

    @Test
    public void testDumpsWriteWhenEnabled() throws Exception {
        Path temp = Files.createTempDirectory("vulkairis-dumps-test");
        String previousDir = System.getProperty("vulkairis.dumpDir");
        boolean previousFlag = com.vulkairis.config.ConfigManager.getConfig().isDumpTranslatedSource();
        System.setProperty("vulkairis.dumpDir", temp.toString());
        com.vulkairis.config.ConfigManager.getConfig().setDumpTranslatedSource(true);
        try {
            Path written = DiagnosticDumps.dumpTranslatedSource("probe_shader", "void main() {}");
            Assertions.assertNotNull(written, "enabled dump must write a file");
            Assertions.assertEquals("void main() {}",
                    Files.readString(written), "dump must preserve exact content");
        } finally {
            com.vulkairis.config.ConfigManager.getConfig().setDumpTranslatedSource(previousFlag);
            if (previousDir != null) {
                System.setProperty("vulkairis.dumpDir", previousDir);
            } else {
                System.clearProperty("vulkairis.dumpDir");
            }
            try (var stream = Files.walk(temp)) {
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
    public void testAlphaBlendFixtureReachesReflected() {
        ShaderPackTestHarness.PackReport report =
                ShaderPackTestHarness.run(ShaderTestPacks.alphaBlend());
        Assertions.assertTrue(report.passed(), "alpha-blend fixture failed:\n" + report.summary());
    }

    @Test
    public void testDepthFixtureReachesReflected() {
        ShaderPackTestHarness.PackReport report =
                ShaderPackTestHarness.run(ShaderTestPacks.depthTest());
        Assertions.assertTrue(report.passed(), "depth fixture failed:\n" + report.summary());
    }

    @Test
    public void testMismatchedInterfaceIsRejectedBeforePipeline() {
        ShaderTestPack pack = ShaderTestPacks.interfaceMismatch();
        Assertions.assertEquals(2, pack.stages().size());

        VulkairisShader vert = SpirvCompiler.compileShader(
                pack.stages().get(0).name(), pack.stages().get(0).glsl(), ShaderType.VERTEX);
        VulkairisShader frag = SpirvCompiler.compileShader(
                pack.stages().get(1).name(), pack.stages().get(1).glsl(), ShaderType.FRAGMENT);

        List<String> problems = PipelineCompatibilityValidator.validate(
                vert.getReflection(), frag.getReflection(), 1);
        Assertions.assertFalse(problems.isEmpty(),
                "vec3 output vs vec2 input must be rejected before pipeline creation");
        Assertions.assertTrue(problems.stream().anyMatch(p -> p.contains("mismatch")),
                "the rejection must name the interface mismatch, got: " + problems);
    }

    @Test
    public void testRegressionsCarryFixedCaptureParameters() {
        for (RegressionCaptures.CaptureSpec spec : RegressionCaptures.all()) {
            Assertions.assertNotNull(spec.id());
            Assertions.assertFalse(spec.fixedParameters().isEmpty(),
                    spec.id() + " must pin its capture parameters");
            Assertions.assertTrue(spec.fixedParameters().containsKey("resolution"),
                    spec.id() + " must pin resolution");
            Assertions.assertTrue(spec.fixedParameters().containsKey("reference"),
                    spec.id() + " must define its Iris+OpenGL reference capture");
            Assertions.assertEquals(RegressionCaptures.EvidenceStatus.PENDING, spec.evidence(),
                    spec.id() + " has no game-run evidence yet");
        }
        Assertions.assertEquals(2, RegressionCaptures.all().size(),
                "both reported regressions (menu square, bright outlines) must be tracked");
    }
}
