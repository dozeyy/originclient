package com.vulkairis.test;

import com.vulkairis.compatibility.ShaderPackCompatibilityReport;
import com.vulkairis.iris.ShaderPackProgram;
import com.vulkairis.iris.ShaderPackProgramCatalog;
import com.vulkairis.rendering.exec.FrameTraceValidator;
import com.vulkairis.rendering.exec.PassExecutionRecord;
import com.vulkairis.rendering.exec.PassGates;
import net.irisshaders.iris.shaderpack.loading.ProgramGroup;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Phase 11: gates map packs to required families honestly, frame traces validate against the
 * plan, and the compatibility report lists ran/fell-back/rejected instead of compile claims.
 */
public class PassSchedulingTest {

    private static ShaderPackProgram graphics(String name, ProgramGroup group) {
        return new ShaderPackProgram.Graphics(name, group, 0, 1, null, true, true,
                false, false, false, null);
    }

    private static ShaderPackProgramCatalog catalogOf(ShaderPackProgram... programs) {
        return ShaderPackProgramCatalog.of(List.of(programs));
    }

    @Test
    public void testRequiredGatesFollowGroups() {
        Assertions.assertEquals(java.util.Set.of(PassGates.Gate.SHADOW),
                PassGates.requiredFor(catalogOf(graphics("shadow", ProgramGroup.Shadow))));

        Assertions.assertEquals(
                java.util.Set.of(PassGates.Gate.TERRAIN, PassGates.Gate.OPAQUE_ENTITIES),
                PassGates.requiredFor(catalogOf(graphics("gbuffers_terrain", ProgramGroup.Gbuffers))));

        Assertions.assertTrue(PassGates.requiredFor(
                        catalogOf(graphics("gbuffers_water", ProgramGroup.Gbuffers)))
                        .contains(PassGates.Gate.TRANSLUCENT));
        Assertions.assertTrue(PassGates.requiredFor(
                        catalogOf(graphics("gbuffers_hand", ProgramGroup.Gbuffers)))
                        .contains(PassGates.Gate.FINAL_HUD));

        Assertions.assertEquals(
                java.util.Set.of(PassGates.Gate.DEFERRED_COMPOSITE, PassGates.Gate.FINAL_HUD),
                PassGates.requiredFor(catalogOf(
                        graphics("composite", ProgramGroup.Composite),
                        graphics("final", ProgramGroup.Final))));

        Assertions.assertTrue(PassGates.requiredFor(null).isEmpty());
        Assertions.assertTrue(PassGates.requiredFor(
                catalogOf(new ShaderPackProgram.Compute("setup", ProgramGroup.Setup, 0, 1, null, null)))
                .isEmpty(), "compute-only packs require no raster gates");
    }

    @Test
    public void testImplementedTableIsHonest() {
        var table = PassGates.implemented();
        Assertions.assertEquals(PassGates.Status.OPEN,
                table.get(PassGates.Gate.FULLSCREEN_COMPOSITION).status());
        Assertions.assertEquals(PassGates.Status.CLOSED, table.get(PassGates.Gate.TERRAIN).status());
        Assertions.assertEquals(PassGates.Status.CLOSED, table.get(PassGates.Gate.SHADOW).status());
        Assertions.assertEquals(PassGates.Status.PARTIAL,
                table.get(PassGates.Gate.DEFERRED_COMPOSITE).status());

        var evaluation = PassGates.evaluate(catalogOf(
                graphics("shadow", ProgramGroup.Shadow),
                graphics("gbuffers_terrain", ProgramGroup.Gbuffers)));
        Assertions.assertTrue(evaluation.stream()
                .anyMatch(s -> s.gate() == PassGates.Gate.SHADOW && s.status() == PassGates.Status.CLOSED));
    }

    @Test
    public void testExecutionRecordOverwriteRules() {
        PassExecutionRecord record = new PassExecutionRecord();
        record.record("composite0", PassExecutionRecord.Outcome.REJECTED, "no binding");
        Assertions.assertEquals(PassExecutionRecord.Outcome.REJECTED, record.outcomeOf("composite0"));
        record.record("composite0", PassExecutionRecord.Outcome.RAN, "recorded");
        Assertions.assertEquals(PassExecutionRecord.Outcome.RAN, record.outcomeOf("composite0"));
        record.record("composite0", PassExecutionRecord.Outcome.FELL_BACK, "downgrade attempt");
        Assertions.assertEquals(PassExecutionRecord.Outcome.RAN, record.outcomeOf("composite0"),
                "RAN must stick once observed");
        Assertions.assertNull(record.outcomeOf("never"));
        Assertions.assertTrue(record.ranAnything());
        record.clear();
        Assertions.assertEquals(0, record.size());
    }

    @Test
    public void testFrameTraceValidation() {
        Assertions.assertTrue(FrameTraceValidator
                .validate(List.of("a", "b", "c"), List.of("a", "b", "c")).clean());

        var missing = FrameTraceValidator.validate(List.of("a", "b", "c"), List.of("a", "c"));
        Assertions.assertFalse(missing.clean());
        Assertions.assertTrue(missing.problems().stream().anyMatch(p -> p.contains("missing pass 'b'")),
                missing.problems().toString());

        var duplicate = FrameTraceValidator.validate(List.of("a", "b"), List.of("a", "a", "b"));
        Assertions.assertTrue(duplicate.problems().stream().anyMatch(p -> p.contains("duplicate")),
                duplicate.problems().toString());

        var unexpected = FrameTraceValidator.validate(List.of("a"), List.of("a", "ghost"));
        Assertions.assertTrue(unexpected.problems().stream().anyMatch(p -> p.contains("unplanned")),
                unexpected.problems().toString());

        var unordered = FrameTraceValidator.validate(List.of("a", "b", "c"), List.of("a", "c", "b"));
        Assertions.assertTrue(unordered.problems().stream().anyMatch(p -> p.contains("out of order")),
                unordered.problems().toString());

        Assertions.assertTrue(FrameTraceValidator.validate(null, null).clean());
    }

    @Test
    public void testRuntimeReportListsOutcomes() {
        var catalog = catalogOf(
                graphics("shadow", ProgramGroup.Shadow),
                graphics("composite", ProgramGroup.Composite));
        PassExecutionRecord record = new PassExecutionRecord();
        record.record("composite", PassExecutionRecord.Outcome.RAN, "recorded");

        ShaderPackCompatibilityReport report = ShaderPackCompatibilityReport
                .evaluate("pack", catalog, false)
                .withRuntime(catalog, record);

        Assertions.assertTrue(report.summarize().contains("run: composite"),
                report.summarize());
        Assertions.assertTrue(report.summarize().contains("run: shadow"),
                report.summarize());
        long supportedRuns = report.getFeatures().stream()
                .filter(f -> f.name().startsWith("run:") && f.support() == ShaderPackCompatibilityReport.Support.SUPPORTED)
                .count();
        Assertions.assertEquals(1, supportedRuns, "only the observed family claims ran");
        Assertions.assertTrue(report.getFeatures().stream()
                        .filter(f -> f.name().equals("shadow pass"))
                        .allMatch(f -> f.support() == ShaderPackCompatibilityReport.Support.PARTIAL),
                "discovered-but-unrun shadow must not read supported");
    }

    @Test
    public void testRuntimeReportNullSafe() {
        var catalog = catalogOf(graphics("composite", ProgramGroup.Composite));
        ShaderPackCompatibilityReport base =
                ShaderPackCompatibilityReport.evaluate("pack", catalog, false);
        Assertions.assertEquals(base.getFeatures().size(),
                base.withRuntime(catalog, null).getFeatures().size());
        Assertions.assertEquals(base.getFeatures().size(),
                base.withRuntime(null, new PassExecutionRecord()).getFeatures().size());
    }
}
