package com.vulkairis.test;

import com.vulkairis.iris.ShaderPackProgram;
import com.vulkairis.iris.ShaderPackProgramCatalog;
import com.vulkairis.plan.PassSpec;
import com.vulkairis.plan.ProgramExecutionPlan;
import com.vulkairis.plan.ProgramResolution;
import com.vulkairis.plan.ProgramVariantKey;
import com.vulkairis.plan.TargetSpec;
import com.vulkairis.rendering.backend.vulkanmod.v1211.VulkanModRenderStage;
import com.vulkairis.rendering.exec.PassGates;
import net.irisshaders.iris.shaderpack.loading.ProgramGroup;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * Phase 3: Unit tests for immutable pack execution plan, target schedules, per-dimension resolution,
 * fallback behavior, and truthful gate validation.
 */
public class ProgramExecutionPlanTest {

    private static ShaderPackProgram.Graphics graphics(String name, ProgramGroup group, String fallback) {
        return new ShaderPackProgram.Graphics(
                name, group, 0, 1, null, true, true, false, false, false, fallback
        );
    }

    private static ShaderPackProgram.Graphics graphics(String name, ProgramGroup group) {
        return graphics(name, group, null);
    }

    @Test
    public void testProgramVariantKeyComposition() {
        ProgramVariantKey key = new ProgramVariantKey("gbuffers_terrain", "overworld", "opt1=true", 42L);
        Assertions.assertEquals("gbuffers_terrain", key.programName());
        Assertions.assertEquals("overworld", key.dimension());
        Assertions.assertEquals("opt1=true", key.optionsDigest());
        Assertions.assertEquals(42L, key.generation());
        Assertions.assertTrue(key.compositeKey().contains("gbuffers_terrain@overworld#opt1=true[gen=42]"));
    }

    @Test
    public void testTargetSpecWindowScaleAndFixedResolution() {
        TargetSpec color = TargetSpec.windowScaled("colortex0", 0, "RGBA8", 1.0f, 1.0f, true);
        Assertions.assertEquals(1920, color.effectiveWidth(1920));
        Assertions.assertEquals(1080, color.effectiveHeight(1080));
        Assertions.assertEquals(2560, color.effectiveWidth(2560));
        Assertions.assertEquals(1440, color.effectiveHeight(1440));

        TargetSpec shadow = TargetSpec.fixed("shadowtex0", -1, "DEPTH32F", 2048, 2048, true);
        Assertions.assertEquals(2048, shadow.effectiveWidth(1920));
        Assertions.assertEquals(2048, shadow.effectiveHeight(1080));
    }

    @Test
    public void testBuildPlanWithFullCatalogAndOrdering() {
        ShaderPackProgramCatalog catalog = ShaderPackProgramCatalog.of(List.of(
                graphics("shadow", ProgramGroup.Shadow),
                graphics("gbuffers_terrain", ProgramGroup.Gbuffers),
                graphics("gbuffers_entities", ProgramGroup.Gbuffers),
                graphics("gbuffers_water", ProgramGroup.Gbuffers),
                graphics("gbuffers_hand", ProgramGroup.Gbuffers),
                graphics("composite", ProgramGroup.Composite),
                graphics("final", ProgramGroup.Final)
        ));

        ProgramExecutionPlan plan = ProgramExecutionPlan.build(
                catalog, "BSL", "overworld", 100L, 2560, 1440, 2048, Map.of()
        );

        Assertions.assertEquals("BSL", plan.packName());
        Assertions.assertEquals("overworld", plan.dimension());
        Assertions.assertEquals(100L, plan.generation());
        Assertions.assertEquals(2560, plan.windowWidth());
        Assertions.assertEquals(1440, plan.windowHeight());
        Assertions.assertEquals(2048, plan.shadowResolution());

        // Target verification
        TargetSpec color = plan.getTarget("colortex0");
        Assertions.assertNotNull(color);
        Assertions.assertEquals(2560, color.effectiveWidth(plan.windowWidth()));
        Assertions.assertEquals(1440, color.effectiveHeight(plan.windowHeight()));

        TargetSpec shadow = plan.getTarget("shadowtex0");
        Assertions.assertNotNull(shadow);
        Assertions.assertEquals(2048, shadow.effectiveWidth(plan.windowWidth()));
        Assertions.assertEquals(2048, shadow.effectiveHeight(plan.windowHeight()));

        // Pass ordering verification: passes must be strictly non-decreasing by stage orderIndex
        List<PassSpec> passes = plan.passes();
        Assertions.assertEquals(7, passes.size());
        int lastOrder = -1;
        for (PassSpec pass : passes) {
            int currentOrder = pass.stage().orderIndex();
            Assertions.assertTrue(currentOrder >= lastOrder,
                    "Passes must be ordered by stage orderIndex: " + pass.passId() + " (" + currentOrder + ") < " + lastOrder);
            lastOrder = currentOrder;
        }

        // Required gates must include SHADOW, TERRAIN, OPAQUE_ENTITIES, TRANSLUCENT, DEFERRED_COMPOSITE, FINAL_HUD
        Assertions.assertTrue(plan.requiredGates().contains(PassGates.Gate.SHADOW));
        Assertions.assertTrue(plan.requiredGates().contains(PassGates.Gate.TERRAIN));
        Assertions.assertTrue(plan.requiredGates().contains(PassGates.Gate.OPAQUE_ENTITIES));
        Assertions.assertTrue(plan.requiredGates().contains(PassGates.Gate.TRANSLUCENT));
        Assertions.assertTrue(plan.requiredGates().contains(PassGates.Gate.DEFERRED_COMPOSITE));
        Assertions.assertTrue(plan.requiredGates().contains(PassGates.Gate.FINAL_HUD));

        // When TERRAIN/ENTITIES/SHADOW gates are CLOSED, plan must report unsupported families
        Assertions.assertFalse(plan.unsupportedFamilies().isEmpty());
        Assertions.assertFalse(plan.isExecutable());
    }

    @Test
    public void testPerDimensionPlanSeparation() {
        ShaderPackProgramCatalog overworldCatalog = ShaderPackProgramCatalog.of(List.of(
                graphics("gbuffers_terrain", ProgramGroup.Gbuffers)
        ));
        ShaderPackProgramCatalog netherCatalog = ShaderPackProgramCatalog.of(List.of(
                graphics("gbuffers_terrain", ProgramGroup.Gbuffers),
                graphics("composite", ProgramGroup.Composite)
        ));

        ProgramExecutionPlan overworldPlan = ProgramExecutionPlan.build(
                overworldCatalog, "TestPack", "overworld", 101L, 1920, 1080, 1024, Map.of()
        );
        ProgramExecutionPlan netherPlan = ProgramExecutionPlan.build(
                netherCatalog, "TestPack", "the_nether", 101L, 1920, 1080, 1024, Map.of()
        );

        Assertions.assertEquals("overworld", overworldPlan.dimension());
        Assertions.assertEquals(1, overworldPlan.passes().size());

        Assertions.assertEquals("the_nether", netherPlan.dimension());
        Assertions.assertEquals(2, netherPlan.passes().size());
    }

    @Test
    public void testDisabledProgramOptionExcludesFromPasses() {
        ShaderPackProgramCatalog catalog = ShaderPackProgramCatalog.of(List.of(
                graphics("gbuffers_terrain", ProgramGroup.Gbuffers),
                graphics("composite", ProgramGroup.Composite)
        ));

        // Disable composite via option
        Map<String, String> options = Map.of("COMPOSITE_ENABLED", "false");
        ProgramExecutionPlan plan = ProgramExecutionPlan.build(
                catalog, "TestPack", "overworld", 102L, 1920, 1080, 1024, options
        );

        ProgramResolution compRes = plan.getResolution("composite");
        Assertions.assertNotNull(compRes);
        Assertions.assertEquals(ProgramResolution.ResolutionStatus.DISABLED, compRes.status());
        Assertions.assertFalse(compRes.isExecutable());

        // Pass list must only contain gbuffers_terrain
        Assertions.assertEquals(1, plan.passes().size());
        Assertions.assertEquals("gbuffers_terrain", plan.passes().get(0).sourceProgram());
    }

    @Test
    public void testFallbackAppliedResolution() {
        ShaderPackProgramCatalog catalog = ShaderPackProgramCatalog.of(List.of(
                graphics("gbuffers_textured", ProgramGroup.Gbuffers)
        ));

        // Program with a fallback that is present in catalog
        ShaderPackProgram.Graphics withFallback = graphics("gbuffers_terrain", ProgramGroup.Gbuffers, "gbuffers_textured");
        ShaderPackProgramCatalog catalogWithFallback = ShaderPackProgramCatalog.of(List.of(
                withFallback,
                graphics("gbuffers_textured", ProgramGroup.Gbuffers)
        ));

        ProgramExecutionPlan plan = ProgramExecutionPlan.build(
                catalogWithFallback, "TestPack", "overworld", 103L, 1920, 1080, 1024, Map.of()
        );

        ProgramResolution res = plan.getResolution("gbuffers_terrain");
        Assertions.assertNotNull(res);
        Assertions.assertTrue(res.isExecutable());
    }

    @Test
    public void testResizePreservesPlanStructure() {
        ShaderPackProgramCatalog catalog = ShaderPackProgramCatalog.of(List.of(
                graphics("final", ProgramGroup.Final)
        ));

        ProgramExecutionPlan p1 = ProgramExecutionPlan.build(
                catalog, "Mini", "overworld", 200L, 1280, 720, 512, Map.of()
        );
        Assertions.assertEquals(1280, p1.windowWidth());
        Assertions.assertEquals(720, p1.windowHeight());
        Assertions.assertEquals(1280, p1.getTarget("colortex0").effectiveWidth(p1.windowWidth()));
        Assertions.assertEquals(720, p1.getTarget("colortex0").effectiveHeight(p1.windowHeight()));

        ProgramExecutionPlan p2 = ProgramExecutionPlan.build(
                catalog, "Mini", "overworld", 201L, 3840, 2160, 512, Map.of()
        );
        Assertions.assertEquals(3840, p2.windowWidth());
        Assertions.assertEquals(2160, p2.windowHeight());
        Assertions.assertEquals(3840, p2.getTarget("colortex0").effectiveWidth(p2.windowWidth()));
        Assertions.assertEquals(2160, p2.getTarget("colortex0").effectiveHeight(p2.windowHeight()));
    }
}
