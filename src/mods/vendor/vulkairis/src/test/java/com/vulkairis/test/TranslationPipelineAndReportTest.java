package com.vulkairis.test;

import com.vulkairis.compatibility.ShaderPackCompatibilityReport;
import com.vulkairis.iris.ShaderPackProgram;
import com.vulkairis.iris.ShaderPackProgramCatalog;
import com.vulkairis.translation.ShaderTranslationPipeline;
import net.irisshaders.iris.shaderpack.loading.ProgramGroup;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.lwjgl.util.shaderc.Shaderc;

import java.util.List;

/**
 * Phase 13/14/16 verification: the named translation pipeline with per-stage validation and the
 * honest shader-pack compatibility report.
 */
public class TranslationPipelineAndReportTest {

    private static final String VALID_FRAGMENT = """
            #version 150 core
            uniform sampler2D colortex0;
            in vec2 vUv;
            out vec4 fragColor;
            void main() {
                fragColor = texture(colortex0, vUv);
            }
            """;

    @Test
    public void testPipelineSucceedsAndKeepsArtifacts() {
        ShaderTranslationPipeline.TranslationResult result = ShaderTranslationPipeline.translate(
                "copy.frag", VALID_FRAGMENT, false, Shaderc.shaderc_glsl_fragment_shader);

        Assertions.assertTrue(result.success(), result.failure());
        Assertions.assertNotNull(result.spirv());
        Assertions.assertNotNull(result.reflection());
        Assertions.assertTrue(result.reflection().getSamplers().containsKey("colortex0"));
        Assertions.assertEquals(4, result.passes().size());
        Assertions.assertEquals("reflect", result.lastStage());
        Assertions.assertEquals(VALID_FRAGMENT, result.originalSource());
        Assertions.assertNotEquals(VALID_FRAGMENT, result.transformedSource());
        Assertions.assertTrue(result.passes().stream().allMatch(ShaderTranslationPipeline.PassResult::passed));
    }

    @Test
    public void testPipelineReportsFailureAtSpecificStage() {
        String broken = "#version 150 core\nout vec4 fragColor;\nvoid main() { this is not glsl; }";
        ShaderTranslationPipeline.TranslationResult result = ShaderTranslationPipeline.translate(
                "broken.frag", broken, false, Shaderc.shaderc_glsl_fragment_shader);

        Assertions.assertFalse(result.success());
        Assertions.assertNull(result.spirv());
        Assertions.assertNull(result.reflection());
        Assertions.assertEquals("compile-spirv", result.lastStage());
        Assertions.assertTrue(result.failure().contains("compile-spirv"), result.failure());
    }

    @Test
    public void testReportWithoutCatalogIsNotUsed() {
        ShaderPackCompatibilityReport report =
                ShaderPackCompatibilityReport.evaluate("None", null, true);
        Assertions.assertEquals(1, report.getFeatures().size());
        Assertions.assertEquals(0, report.count(ShaderPackCompatibilityReport.Support.SUPPORTED));
        Assertions.assertTrue(report.summarize().contains("no shader pack"));
    }

    @Test
    public void testReportClassifiesDiscoveredFeatures() {
        List<ShaderPackProgram> programs = List.of(
                graphics("shadow", ProgramGroup.Shadow),
                graphics("gbuffers_terrain", ProgramGroup.Gbuffers),
                graphics("final", ProgramGroup.Final),
                compute("setup", ProgramGroup.Setup));
        ShaderPackProgramCatalog catalog = ShaderPackProgramCatalog.of(programs);

        ShaderPackCompatibilityReport report =
                ShaderPackCompatibilityReport.evaluate("TestPack", catalog, true);

        Assertions.assertEquals(ShaderPackCompatibilityReport.Support.SUPPORTED,
                support(report, "shadow pass"));
        Assertions.assertEquals(ShaderPackCompatibilityReport.Support.SUPPORTED,
                support(report, "gbuffers geometry"));
        Assertions.assertEquals(ShaderPackCompatibilityReport.Support.SUPPORTED,
                support(report, "composite/final post-processing"));
        Assertions.assertEquals(ShaderPackCompatibilityReport.Support.SUPPORTED,
                support(report, "compute"));
        Assertions.assertEquals(ShaderPackCompatibilityReport.Support.NOT_USED,
                support(report, "deferred"));
        Assertions.assertEquals(ShaderPackCompatibilityReport.Support.PARTIAL,
                support(report, "multiple render targets"));
        Assertions.assertTrue(report.count(ShaderPackCompatibilityReport.Support.SUPPORTED) >= 4);
    }

    private static ShaderPackCompatibilityReport.Support support(
            ShaderPackCompatibilityReport report, String featureName) {
        return report.getFeatures().stream()
                .filter(feature -> feature.name().equals(featureName))
                .findFirst().orElseThrow().support();
    }

    private static ShaderPackProgram graphics(String name, ProgramGroup group) {
        return new ShaderPackProgram.Graphics(
                name, group, 0, 1, null, true, true, false, false, false, null);
    }

    private static ShaderPackProgram compute(String name, ProgramGroup group) {
        return new ShaderPackProgram.Compute(name, group, 0, 1, null, null);
    }
}
