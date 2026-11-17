package com.vulkairis.test;

import com.vulkairis.compatibility.AnalysisResult;
import com.vulkairis.compatibility.CapabilityGate;
import com.vulkairis.compatibility.DistantHorizonsSupport;
import com.vulkairis.compatibility.ShaderAnalyzer;
import com.vulkairis.iris.ShaderPackProgram;
import com.vulkairis.iris.ShaderPackProgramCatalog;
import com.vulkairis.plan.ProgramExecutionPlan;
import com.vulkairis.plan.ProgramResolution;
import com.vulkairis.rendering.exec.PassGates;
import com.vulkairis.translation.ProgramTranslator;
import com.vulkairis.translation.UnifiedTranslationService;
import com.vulkairis.translation.VulkanShaderRegistry;
import net.irisshaders.iris.shaderpack.loading.ProgramGroup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * DH degrade pass: without the Distant Horizons mod, {@code dh_terrain}/{@code dh_water}
 * programs (which reference undeclared {@code dhProjection}/{@code dhProjectionInverse}/
 * {@code dhMaterialId}/{@code dhDepthTex}/{@code dhDepthTex1} identifiers) must degrade
 * gracefully — one labeled skip reason, no shaderc error spam — while non-DH programs are
 * completely unaffected.
 */
public class DistantHorizonsDegradeTest {

    /** Minimal distant-terrain-shaped vertex source carrying the observed DH identifiers. */
    static final String DH_VERT = """
            #version 120
            uniform mat4 dhProjection;
            varying float dhMaterialId;
            void main() {
                dhMaterialId = 1.0;
                gl_Position = dhProjection * gl_Vertex;
            }
            """;

    /** Minimal distant-water-shaped fragment source carrying the observed DH identifiers. */
    static final String DH_FRAG = """
            #version 120
            uniform mat4 dhProjection;
            uniform mat4 dhProjectionInverse;
            uniform sampler2D dhDepthTex;
            uniform sampler2D dhDepthTex1;
            void main() {
                vec4 depth = texture2D(dhDepthTex, vec2(0.5));
                vec4 depth1 = texture2D(dhDepthTex1, vec2(0.5));
                gl_FragData[0] = dhProjection * dhProjectionInverse * (depth + depth1);
            }
            """;

    private static final String PLAIN_VERT = """
            #version 120
            varying vec2 texCoord;
            void main() {
                texCoord = gl_MultiTexCoord0.xy;
                gl_Position = ftransform();
            }
            """;

    private static final String PLAIN_FRAG = """
            #version 120
            uniform sampler2D gtexture;
            varying vec2 texCoord;
            void main() {
                gl_FragData[0] = texture2D(gtexture, texCoord);
            }
            """;

    private static ShaderPackProgram.Graphics graphics(String name, ProgramGroup group) {
        return new ShaderPackProgram.Graphics(
                name, group, 0, 1, null, true, true, false, false, false, null);
    }

    @BeforeEach
    public void setUp() {
        DistantHorizonsSupport.setAvailableForTests(false);
        UnifiedTranslationService.reset();
        ProgramTranslator.clearCacheForTests();
        VulkanShaderRegistry.cleanUp();
    }

    @AfterEach
    public void tearDown() {
        DistantHorizonsSupport.setAvailableForTests(null);
        UnifiedTranslationService.reset();
        ProgramTranslator.clearCacheForTests();
        VulkanShaderRegistry.cleanUp();
    }

    /** Program identity: Dh group or dh_ name detects; everything else does not. */
    @Test
    public void testDistantProgramDetection() {
        Assertions.assertTrue(DistantHorizonsSupport.isDistantProgram(
                graphics("dh_terrain", ProgramGroup.Dh)));
        Assertions.assertTrue(DistantHorizonsSupport.isDistantProgram(
                graphics("dh_water", ProgramGroup.Dh)));
        Assertions.assertTrue(DistantHorizonsSupport.isDistantProgramName("dh_terrain"));
        Assertions.assertTrue(DistantHorizonsSupport.isDistantProgramName("DH_Water"));
        Assertions.assertFalse(DistantHorizonsSupport.isDistantProgram(
                graphics("gbuffers_terrain", ProgramGroup.Gbuffers)));
        Assertions.assertFalse(DistantHorizonsSupport.isDistantProgram(
                graphics("gbuffers_water", ProgramGroup.Gbuffers)));
        Assertions.assertFalse(DistantHorizonsSupport.isDistantProgram(
                graphics("composite", ProgramGroup.Composite)));
        Assertions.assertFalse(DistantHorizonsSupport.isDistantProgram(null));
        Assertions.assertFalse(DistantHorizonsSupport.isDistantProgramName(null));
        Assertions.assertFalse(DistantHorizonsSupport.isDistantProgramName(""));
    }

    /** Source detection: observed DH identifiers match; normal sources and lookalikes do not. */
    @Test
    public void testSourceRequiresDistantHorizons() {
        Assertions.assertTrue(DistantHorizonsSupport.requiresDistantHorizons(DH_VERT));
        Assertions.assertTrue(DistantHorizonsSupport.requiresDistantHorizons(DH_FRAG));
        Assertions.assertTrue(DistantHorizonsSupport.requiresDistantHorizons(
                "uniform mat4 dhProjectionInverse;"));
        Assertions.assertTrue(DistantHorizonsSupport.requiresDistantHorizons(
                "float x = dhMaterialId;"));
        Assertions.assertTrue(DistantHorizonsSupport.requiresDistantHorizons(
                "texture2D(dhDepthTex, uv); texture2D(dhDepthTex1, uv);"));
        Assertions.assertFalse(DistantHorizonsSupport.requiresDistantHorizons(PLAIN_VERT));
        Assertions.assertFalse(DistantHorizonsSupport.requiresDistantHorizons(PLAIN_FRAG));
        Assertions.assertFalse(DistantHorizonsSupport.requiresDistantHorizons(null));
        Assertions.assertFalse(DistantHorizonsSupport.requiresDistantHorizons(""));
        // Token-boundary discipline: identifiers merely containing a DH name never match.
        Assertions.assertFalse(DistantHorizonsSupport.requiresDistantHorizons(
                "float mydhProjection = 1.0;"));
        Assertions.assertFalse(DistantHorizonsSupport.requiresDistantHorizons(
                "float dhProjection2 = 1.0;"));
    }

    /** Availability override mechanics for tests; ambient test env reads as absent. */
    @Test
    public void testAvailabilityOverride() {
        DistantHorizonsSupport.setAvailableForTests(null);
        Assertions.assertFalse(DistantHorizonsSupport.isAvailable(),
                "DH mod is not a test dependency, so ambient detection must read absent");
        DistantHorizonsSupport.setAvailableForTests(true);
        Assertions.assertTrue(DistantHorizonsSupport.isAvailable());
        DistantHorizonsSupport.setAvailableForTests(false);
        Assertions.assertFalse(DistantHorizonsSupport.isAvailable());
    }

    /**
     * Analyzer fast path: DH stages return UNSUPPORTED with the labeled reason without ever
     * compiling (no shaderc error dump); non-DH sources flow through unchanged.
     */
    @Test
    public void testAnalyzerSkipsDistantWithoutCompiling() {
        AnalysisResult vert = ShaderAnalyzer.analyzeAndTest("dh_terrain_vert", DH_VERT, 35633);
        Assertions.assertEquals(AnalysisResult.Status.UNSUPPORTED, vert.status());
        Assertions.assertTrue(vert.reason().contains("Distant Horizons"),
                "skip must be labeled: " + vert.reason());
        Assertions.assertTrue(vert.details().contains("dh_terrain_vert"), vert.details());

        AnalysisResult frag = ShaderAnalyzer.analyzeAndTest("dh_water_frag", DH_FRAG, 35632);
        Assertions.assertEquals(AnalysisResult.Status.UNSUPPORTED, frag.status());
        Assertions.assertTrue(frag.reason().contains("Distant Horizons"));

        // Non-DH sources are unaffected: a plain pair still passes dry-run analysis.
        Assertions.assertTrue(
                ShaderAnalyzer.analyzeAndTest("gbuffers_clouds_vert", PLAIN_VERT, 35633).isSuccess(),
                "non-DH vertex must still pass");
        Assertions.assertTrue(
                ShaderAnalyzer.analyzeAndTest("gbuffers_clouds_frag", PLAIN_FRAG, 35632).isSuccess(),
                "non-DH fragment must still pass");

        // A broken non-DH source keeps its existing (non-DH) failure, not the DH reason.
        AnalysisResult broken = ShaderAnalyzer.analyzeAndTest("gbuffers_broken_frag",
                "#version 450 core\nvoid main() { syntax error here !! }", 35632);
        Assertions.assertFalse(broken.isSuccess());
        Assertions.assertFalse(broken.reason().contains("Distant Horizons"),
                "non-DH failure must not be mislabeled: " + broken.reason());
    }

    /** Catalog queries expose the distant set without changing discovery itself. */
    @Test
    public void testCatalogDistantQueries() {
        ShaderPackProgramCatalog catalog = ShaderPackProgramCatalog.of(List.of(
                graphics("gbuffers_terrain", ProgramGroup.Gbuffers),
                graphics("dh_terrain", ProgramGroup.Dh),
                graphics("dh_water", ProgramGroup.Dh),
                graphics("final", ProgramGroup.Final)));
        Assertions.assertTrue(catalog.hasDistantPrograms());
        Assertions.assertEquals(2, catalog.getDistantPrograms().size());
        Assertions.assertEquals(4, catalog.size(), "discovery itself is unchanged");

        ShaderPackProgramCatalog clean = ShaderPackProgramCatalog.of(List.of(
                graphics("gbuffers_terrain", ProgramGroup.Gbuffers)));
        Assertions.assertFalse(clean.hasDistantPrograms());
        Assertions.assertTrue(clean.getDistantPrograms().isEmpty());
    }

    /** Capability gate: DH presence degrades to ACCEPT_PARTIAL with one labeled note. */
    @Test
    public void testCapabilityGatePartialForDistant() {
        var verdict = CapabilityGate.evaluatePack(ShaderPackProgramCatalog.of(List.of(
                graphics("gbuffers_terrain", ProgramGroup.Gbuffers),
                graphics("composite", ProgramGroup.Composite),
                graphics("final", ProgramGroup.Final),
                graphics("dh_terrain", ProgramGroup.Dh),
                graphics("dh_water", ProgramGroup.Dh))));
        Assertions.assertEquals(CapabilityGate.PackVerdict.ACCEPT_PARTIAL, verdict.verdict());
        Assertions.assertTrue(verdict.notes().stream()
                        .anyMatch(n -> n.contains("distant-horizons") && n.contains("dh_terrain")
                                && n.contains("dh_water")),
                "gate note must label the skipped programs: " + verdict.notes());

        // With the mod present the distant set flows through the normal path (no DH note).
        DistantHorizonsSupport.setAvailableForTests(true);
        var present = CapabilityGate.evaluatePack(ShaderPackProgramCatalog.of(List.of(
                graphics("gbuffers_terrain", ProgramGroup.Gbuffers),
                graphics("composite", ProgramGroup.Composite),
                graphics("final", ProgramGroup.Final),
                graphics("dh_terrain", ProgramGroup.Dh))));
        Assertions.assertEquals(CapabilityGate.PackVerdict.ACCEPT, present.verdict());
        Assertions.assertTrue(present.notes().isEmpty());
    }

    /** Execution plan: DH resolutions carry the skip reason with no pass; others unaffected. */
    @Test
    public void testExecutionPlanSkipsDistant() {
        ShaderPackProgramCatalog catalog = ShaderPackProgramCatalog.of(List.of(
                graphics("gbuffers_terrain", ProgramGroup.Gbuffers),
                graphics("dh_terrain", ProgramGroup.Dh),
                graphics("dh_water", ProgramGroup.Dh),
                graphics("composite", ProgramGroup.Composite)));

        ProgramExecutionPlan plan = ProgramExecutionPlan.build(
                catalog, "Complementary", "overworld", 7L, 1920, 1080, 1024, Map.of());

        for (String dh : List.of("dh_terrain", "dh_water")) {
            ProgramResolution res = plan.getResolution(dh);
            Assertions.assertNotNull(res, "skipped DH program must still resolve: " + dh);
            Assertions.assertEquals(ProgramResolution.ResolutionStatus.UNSUPPORTED, res.status());
            Assertions.assertFalse(res.isExecutable());
            Assertions.assertTrue(res.reason().contains("Distant Horizons"), res.reason());
            Assertions.assertTrue(plan.passes().stream()
                    .noneMatch(p -> p.sourceProgram().equals(dh)), "no pass for " + dh);
        }
        // Non-DH programs keep their passes.
        Assertions.assertTrue(plan.passes().stream()
                .anyMatch(p -> p.sourceProgram().equals("gbuffers_terrain")));
        Assertions.assertTrue(plan.passes().stream()
                .anyMatch(p -> p.sourceProgram().equals("composite")));
        Assertions.assertEquals(ProgramResolution.ResolutionStatus.RESOLVED,
                plan.getResolution("gbuffers_terrain").status());

        // Skipped distant programs must not hold gates: a DH-only catalog requires nothing.
        ShaderPackProgramCatalog dhOnly = ShaderPackProgramCatalog.of(List.of(
                graphics("dh_terrain", ProgramGroup.Dh)));
        Assertions.assertTrue(PassGates.requiredFor(dhOnly).isEmpty());
    }

    /**
     * Translation service: a fully DH-driven program is clean-rejected once with the labeled
     * reason (no per-stage shaderc spam); non-DH programs still translate and recompile.
     */
    @Test
    public void testTranslationServiceCleanRejectsDistantProgram() {
        int vertId = VulkanShaderRegistry.registerShader("dh_terrain.vsh", 35633, null, DH_VERT);
        int fragId = VulkanShaderRegistry.registerShader("dh_terrain.fsh", 35632, null, DH_FRAG);
        UnifiedTranslationService.bufferRawSource(vertId, "dh_terrain.vsh", DH_VERT, "VERTEX");
        UnifiedTranslationService.bufferRawSource(fragId, "dh_terrain.fsh", DH_FRAG, "FRAGMENT");

        UnifiedTranslationService.TranslationOutcome outcome =
                UnifiedTranslationService.translateProgram("dh_terrain", List.of(vertId, fragId));

        Assertions.assertFalse(outcome.succeeded());
        Assertions.assertFalse(outcome.errors().isEmpty());
        Assertions.assertTrue(outcome.errors().stream()
                        .anyMatch(e -> e.contains("Distant Horizons")),
                "rejection must be labeled: " + outcome.errors());
    }

    /** Non-DH translation is unaffected by the DH gate. */
    @Test
    public void testTranslationServiceUnaffectedForPlainProgram() {
        int vertId = VulkanShaderRegistry.registerShader("gbuffers_clouds.vsh", 35633, null, PLAIN_VERT);
        int fragId = VulkanShaderRegistry.registerShader("gbuffers_clouds.fsh", 35632, null, PLAIN_FRAG);
        UnifiedTranslationService.bufferRawSource(vertId, "gbuffers_clouds.vsh", PLAIN_VERT, "VERTEX");
        UnifiedTranslationService.bufferRawSource(fragId, "gbuffers_clouds.fsh", PLAIN_FRAG, "FRAGMENT");

        UnifiedTranslationService.TranslationOutcome outcome =
                UnifiedTranslationService.translateProgram("gbuffers_clouds", List.of(vertId, fragId));

        Assertions.assertTrue(outcome.succeeded(),
                "plain program must still translate: " + outcome.errors());
    }
}
