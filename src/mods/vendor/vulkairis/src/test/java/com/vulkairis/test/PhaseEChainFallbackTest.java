package com.vulkairis.test;

import com.vulkairis.diagnostics.DiagnosticManager;
import com.vulkairis.diagnostics.FallbackRecord;
import com.vulkairis.diagnostics.VulkairisCommands;
import com.vulkairis.core.VulkairisController;
import com.vulkairis.iris.ShaderPackProgram;
import com.vulkairis.iris.ShaderPackProgramCatalog;
import com.vulkairis.rendergraph.PassNode;
import com.vulkairis.rendering.exec.PackChainPlan;
import com.vulkairis.rendering.exec.PackRenderPipeline;
import com.vulkairis.rendering.exec.VulkairisFrameCoordinator;
import com.vulkairis.resource.RenderTargetRegistry;
import net.irisshaders.iris.shaderpack.loading.ProgramGroup;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Phase E: the chain plan is solved once per pack-dimension profile and replayed without
 * re-solving, a missing {@code final} becomes an explicitly labeled present-equivalent
 * substitute (never a claimed real final), and every fallback engagement propagates its
 * requested-vs-effective record verbatim to the status commands.
 */
public class PhaseEChainFallbackTest {

    private static ShaderPackProgram graphics(String name, ProgramGroup group) {
        return new ShaderPackProgram.Graphics(name, group, 0, 1, null, true, true,
                false, false, false, null);
    }

    private static ShaderPackProgramCatalog catalogOf(ShaderPackProgram... programs) {
        return ShaderPackProgramCatalog.of(List.of(programs));
    }

    @Test
    public void testPlanReplayDeterministic() {
        PackChainPlan.clearForTests();
        ShaderPackProgramCatalog catalog = catalogOf(
                graphics("composite1", ProgramGroup.Composite),
                graphics("composite2", ProgramGroup.Composite),
                graphics("final", ProgramGroup.Final));
        int solvesBefore = PackChainPlan.solveCount();

        PackChainPlan first = PackChainPlan.planFor(
                catalog, "pack", "overworld", 1920, 1080, 1024, 7L);
        PackChainPlan replay = PackChainPlan.planFor(
                catalog, "pack", "overworld", 1920, 1080, 1024, 7L);
        Assertions.assertSame(first, replay,
                "the same profile must replay the cached plan without re-solving");
        Assertions.assertEquals(solvesBefore + 1, PackChainPlan.solveCount(),
                "one profile must solve exactly once");

        PackChainPlan resolved = PackChainPlan.resolve(
                catalog, "pack", "overworld", 1920, 1080, 1024, 7L);
        Assertions.assertEquals(first, resolved, "the same inputs must solve identically");
        Assertions.assertEquals(first.digest(), resolved.digest());
        Assertions.assertEquals(first.profileKey(), resolved.profileKey());

        PackChainPlan otherDimension = PackChainPlan.planFor(
                catalog, "pack", "the_nether", 1920, 1080, 1024, 7L);
        Assertions.assertNotEquals(first, otherDimension,
                "a dimension change is a new profile and must re-solve");
        Assertions.assertEquals(solvesBefore + 3, PackChainPlan.solveCount());

        Assertions.assertEquals(
                List.of("composite1", "composite2", "final"),
                first.postPrograms());
        List<PackChainPlan.DrawStep> steps = first.drawSteps();
        Assertions.assertEquals(3, steps.size());
        Assertions.assertTrue(steps.get(2).finalStep());
        Assertions.assertFalse(steps.get(2).substituteFinal(),
                "a pack final must not be labeled substitute");
        Assertions.assertTrue(first.hasRealFinal());
        Assertions.assertFalse(first.usesSubstituteFinal());
        Assertions.assertEquals(PackChainPlan.STAGE_COMPOSITES_FINAL,
                first.stages().get(first.stages().size() - 1));

        Assertions.assertEquals(7, first.targets().size(), "standard target set");
        Assertions.assertEquals(7, first.samplers().size(), "one sampler policy per target");
        long fixed = first.targets().stream()
                .filter(PackChainPlan.TargetSlot::fixedResolution).count();
        Assertions.assertEquals(2, fixed, "shadow depth+color stay fixed");
        Assertions.assertTrue(first.samplers().containsKey(RenderTargetRegistry.FINAL));

        PackChainPlan.clearForTests();
    }

    @Test
    public void testSubstituteFinalLabeling() {
        PackChainPlan.clearForTests();

        ShaderPackProgramCatalog noFinal = catalogOf(
                graphics("composite1", ProgramGroup.Composite),
                graphics("composite2", ProgramGroup.Composite));
        PackChainPlan substitute = PackChainPlan.resolve(
                noFinal, "pack", "overworld", 1280, 720, 512, 3L);
        Assertions.assertTrue(substitute.usesSubstituteFinal());
        Assertions.assertFalse(substitute.hasRealFinal());
        List<PackChainPlan.DrawStep> steps = substitute.drawSteps();
        Assertions.assertFalse(steps.isEmpty());
        Assertions.assertTrue(steps.get(steps.size() - 1).substituteFinal());
        Assertions.assertTrue(substitute.finalLabel().contains("SUBSTITUTE"),
                substitute.finalLabel());
        Assertions.assertTrue(substitute.finalLabel().contains("not a real final"),
                substitute.finalLabel());
        Assertions.assertTrue(substitute.finalLabel().contains(PackChainPlan.mainTarget()),
                "the substitute writes the game main target");
        Assertions.assertTrue(substitute.statusLines().stream()
                        .anyMatch(line -> line.contains("[SUBSTITUTE]")),
                substitute.statusLines().toString());
        Assertions.assertEquals(PackChainPlan.STAGE_PRESENT_SUBSTITUTE,
                substitute.stages().get(substitute.stages().size() - 1));

        ShaderPackProgramCatalog empty =
                ShaderPackProgramCatalog.of(List.of());
        PackChainPlan presentOnly = PackChainPlan.resolve(
                empty, "pack", "overworld", 1280, 720, 512, 3L);
        Assertions.assertTrue(presentOnly.usesSubstituteFinal());
        Assertions.assertEquals(1, presentOnly.drawSteps().size());
        PackChainPlan.DrawStep only = presentOnly.drawSteps().get(0);
        Assertions.assertEquals(PackChainPlan.PRESENT_SUBSTITUTE_PROGRAM, only.programName());
        Assertions.assertEquals(RenderTargetRegistry.SCENE_COLOR, only.inputTarget());
        Assertions.assertEquals(RenderTargetRegistry.FINAL, only.outputTarget());
        Assertions.assertTrue(only.substituteFinal());
        Assertions.assertTrue(presentOnly.finalLabel().contains("not a real final"));

        ShaderPackProgramCatalog realFinal = catalogOf(
                graphics("composite1", ProgramGroup.Composite),
                graphics("final", ProgramGroup.Final));
        PackChainPlan real = PackChainPlan.resolve(
                realFinal, "pack", "overworld", 1280, 720, 512, 3L);
        Assertions.assertFalse(real.usesSubstituteFinal());
        Assertions.assertTrue(real.finalLabel().contains("real pack final"),
                real.finalLabel());
        Assertions.assertFalse(real.finalLabel().contains("SUBSTITUTE"));

        // Substitute-only plans stay non-executable (legacy no-post behavior) but labeled.
        PackRenderPipeline substitutePipeline =
                PackRenderPipeline.buildFromPlan(presentOnly, null);
        Assertions.assertFalse(substitutePipeline.hasGraph());
        Assertions.assertSame(presentOnly, substitutePipeline.getChainPlan());

        PackRenderPipeline realPipeline = PackRenderPipeline.buildFromPlan(
                real, (name, w, h, targets) -> null);
        Assertions.assertTrue(realPipeline.hasGraph());
        Assertions.assertSame(real, realPipeline.getChainPlan());

        // The coordinator mirrors the installed plan's labeling in its status lines.
        VulkairisFrameCoordinator.setActivePipeline(
                PackRenderPipeline.buildFromPlan(substitute, (name, w, h, targets) -> null));
        try {
            Assertions.assertTrue(VulkairisFrameCoordinator.describeChainPlan().stream()
                            .anyMatch(line -> line.contains("SUBSTITUTE")),
                    VulkairisFrameCoordinator.describeChainPlan().toString());
        } finally {
            VulkairisFrameCoordinator.setActivePipeline(null);
        }
        Assertions.assertTrue(VulkairisFrameCoordinator.describeChainPlan().stream()
                        .anyMatch(line -> line.contains("none")),
                "without a plan the status must say so, never imply shaders work");

        PackChainPlan.clearForTests();
    }

    @Test
    public void testFallbackReasonPropagationToStatus() {
        DiagnosticManager.clearHistory();
        String home = System.getProperty("user.home");

        FallbackRecord.Entry engaged = FallbackRecord.engage(
                "Vulkan", "vanilla-vulkanmod", "composite",
                "Complementary", "composite0",
                "pack pipeline rejected",
                "compile failed at " + home + "/shaders/composite0.fsh: boom");
        Assertions.assertFalse(engaged.logExcerpt().contains(home),
                "user home must not leave the machine: " + engaged.logExcerpt());
        Assertions.assertTrue(engaged.logExcerpt().contains("~"),
                engaged.logExcerpt());
        Assertions.assertEquals("Vulkan", engaged.requestedBackend());
        Assertions.assertEquals("vanilla-vulkanmod", engaged.effectiveBackend());
        Assertions.assertEquals("composite", engaged.stage());
        Assertions.assertEquals("Complementary", engaged.packId());
        Assertions.assertEquals("composite0", engaged.shaderId());

        StringBuilder longExcerpt = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            longExcerpt.append("log line ").append(i)
                    .append(" with padding 0123456789abcdef\n");
        }
        FallbackRecord.Entry bounded = FallbackRecord.engage(
                "Vulkan", "vanilla-vulkanmod", "final",
                "BSL", "final", "overflow", longExcerpt.toString());
        Assertions.assertTrue(bounded.logExcerpt().length()
                        <= FallbackRecord.MAX_EXCERPT_CHARS + 100,
                "excerpts stay bounded: " + bounded.logExcerpt().length());

        VulkairisController controller = VulkairisController.getInstance();
        controller.triggerFallbackDetailed("Vulkan", "vanilla-vulkanmod", "final",
                "BSL", "final1", "final link failed", "linker said no");
        FallbackRecord.Entry last = controller.getLastFallbackRecord();
        Assertions.assertNotNull(last);
        Assertions.assertEquals("final link failed", last.reason());

        List<String> verbatim = FallbackRecord.describe(last);
        Assertions.assertEquals(verbatim, controller.getFallbackStatusLines(),
                "controller status lines mirror the record verbatim");

        List<String> statusLines = VulkairisCommands.statusExtraLines();
        Assertions.assertTrue(statusLines.containsAll(verbatim),
                "status commands carry the fallback record verbatim: " + statusLines);

        List<String> shaderLines = VulkairisCommands.shaderExtraLines();
        Assertions.assertTrue(shaderLines.containsAll(verbatim),
                "shader commands carry the fallback record verbatim: " + shaderLines);

        Assertions.assertTrue(controller.isFallbackTriggered());
        DiagnosticManager.clearHistory();
    }

    /**
     * The Complementary r5.9.3 post chain ({@code composite, composite1, composite3,
     * composite4, composite5, composite6, final}) must build an executable pipeline through
     * {@code PackRenderPipeline.buildFromPlan} instead of failing the render graph with
     * {@code composite1 -> composite3 -> composite1}. Every post program stays in the plan
     * in order; none is silently dropped to break the false cycle.
     */
    @Test
    public void testComplementaryShapedPostChainBuildsPipeline() {
        PackChainPlan.clearForTests();
        ShaderPackProgramCatalog catalog = catalogOf(
                graphics("composite", ProgramGroup.Composite),
                graphics("composite1", ProgramGroup.Composite),
                graphics("composite3", ProgramGroup.Composite),
                graphics("composite4", ProgramGroup.Composite),
                graphics("composite5", ProgramGroup.Composite),
                graphics("composite6", ProgramGroup.Composite),
                graphics("final", ProgramGroup.Final));
        PackChainPlan plan = PackChainPlan.resolve(
                catalog, "ComplementaryReimagined_r5.9.3", "overworld", 1366, 745, 1024, 3L);

        PackRenderPipeline pipeline =
                PackRenderPipeline.buildFromPlan(plan, (name, w, h, targets) -> null);

        Assertions.assertTrue(pipeline.hasGraph(), "the full post chain must stay executable");
        Assertions.assertSame(plan, pipeline.getChainPlan());
        Assertions.assertEquals(plan.postPrograms(),
                pipeline.getGraph().getExecutionOrder().stream().map(PassNode::name).toList(),
                "every pack pass must survive the graph build in plan order");
        PackChainPlan.clearForTests();
    }
}
