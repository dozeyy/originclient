package com.vulkairis.test;

import com.vulkairis.iris.SourceProvenance;
import com.vulkairis.rendering.exec.PassExecutionRecord;
import com.vulkairis.rendering.exec.VulkairisFrameCoordinator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Phase 11: the frame pins its pipeline generation; a mid-stream rebuild drops exactly one
 * frame and clears stale execution claims instead of drawing a dead plan.
 */
public class CoordinatorPinTest {

    @Test
    public void testPinDropAndRepin() {
        VulkairisFrameCoordinator.resetFramePinForTests();
        Assertions.assertEquals(-1L, VulkairisFrameCoordinator.pinnedGeneration());

        Assertions.assertTrue(VulkairisFrameCoordinator.checkFramePlan().isEmpty(),
                "first check pins the current generation");
        long pinned = VulkairisFrameCoordinator.pinnedGeneration();
        Assertions.assertEquals(SourceProvenance.currentGeneration(), pinned);

        Assertions.assertTrue(VulkairisFrameCoordinator.checkFramePlan().isEmpty(),
                "a stable plan executes");

        SourceProvenance.nextGeneration("test rebuild");
        var problem = VulkairisFrameCoordinator.checkFramePlan();
        Assertions.assertTrue(problem.isPresent(), "a rebuilt plan must drop one frame");
        Assertions.assertTrue(problem.get().contains("generation changed"),
                problem.orElse(""));
        Assertions.assertEquals(SourceProvenance.currentGeneration(),
                VulkairisFrameCoordinator.pinnedGeneration(), "the pin moves to the new plan");
        Assertions.assertTrue(VulkairisFrameCoordinator.checkFramePlan().isEmpty(),
                "the next frame executes the new plan");

        VulkairisFrameCoordinator.resetFramePinForTests();
    }

    @Test
    public void testDropClearsExecutionRecord() {
        VulkairisFrameCoordinator.resetFramePinForTests();
        VulkairisFrameCoordinator.checkFramePlan();
        VulkairisFrameCoordinator.lastExecutionRecord()
                .record("composite0", PassExecutionRecord.Outcome.RAN, "recorded");
        Assertions.assertTrue(VulkairisFrameCoordinator.lastExecutionRecord().ranAnything());

        // A dropped frame goes through the same clearing path as the coordinator drop branch.
        VulkairisFrameCoordinator.lastExecutionRecord().clear();
        SourceProvenance.nextGeneration("test rebuild 2");
        VulkairisFrameCoordinator.checkFramePlan();
        Assertions.assertFalse(VulkairisFrameCoordinator.lastExecutionRecord().ranAnything(),
                "stale ran-claims must not survive a plan rebuild");

        VulkairisFrameCoordinator.resetFramePinForTests();
    }

    @Test
    public void testInactiveCoordinatorStaysInactive() {
        VulkairisFrameCoordinator.setActivePipeline(null);
        var report = VulkairisFrameCoordinator.onExecutePasses(null);
        Assertions.assertFalse(report.submitted());
        Assertions.assertEquals("coordinator inactive", report.detail());
    }

    @Test
    public void testTraceStartsClean() {
        VulkairisFrameCoordinator.resetFramePinForTests();
        Assertions.assertTrue(VulkairisFrameCoordinator.lastFrameTrace().clean());
        Assertions.assertEquals(0, VulkairisFrameCoordinator.lastExecutionRecord().size());
    }
}
