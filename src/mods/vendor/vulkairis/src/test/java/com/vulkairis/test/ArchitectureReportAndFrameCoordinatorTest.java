package com.vulkairis.test;

import com.vulkairis.diagnostics.VulkairisArchitectureReport;
import com.vulkairis.rendering.exec.PackRenderPipeline;
import com.vulkairis.rendering.exec.RenderGraphExecutor;
import com.vulkairis.rendering.exec.VulkairisFrameCoordinator;
import com.vulkairis.resource.RenderTargetRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class ArchitectureReportAndFrameCoordinatorTest {

    @Test
    public void testArchitectureReportProbeEvaluation() {
        VulkairisArchitectureReport.Probe probe = new VulkairisArchitectureReport.Probe(
                true, true, 5, 10, 5, 1, true
        );
        VulkairisArchitectureReport report = VulkairisArchitectureReport.evaluate(probe);
        Assertions.assertNotNull(report);
        Assertions.assertTrue(report.count(VulkairisArchitectureReport.Status.IMPLEMENTED) > 0);
        Assertions.assertFalse(report.getSubsystems().isEmpty());

        String rendered = report.render();
        Assertions.assertTrue(rendered.contains("[OK] GLSL translation"));
        Assertions.assertTrue(rendered.contains("[OK] Render graph"));
        Assertions.assertTrue(rendered.contains("[OK] Graph execution wiring"));
    }

    @Test
    public void testArchitectureReportCaptureDoesNotThrow() {
        VulkairisArchitectureReport report = VulkairisArchitectureReport.capture();
        Assertions.assertNotNull(report);
        Assertions.assertFalse(report.render().isEmpty());
    }

    @Test
    public void testFrameCoordinatorLifecycle() {
        VulkairisFrameCoordinator.setActivePipeline(null);
        Assertions.assertFalse(VulkairisFrameCoordinator.isActive());

        // Test execution report when coordinator is inactive
        RenderGraphExecutor.ExecutionReport execReport = VulkairisFrameCoordinator.onExecutePasses(null);
        Assertions.assertFalse(execReport.submitted());
        Assertions.assertEquals("coordinator inactive", execReport.detail());

        // Rebuilding with null catalog should remain inactive safely
        VulkairisFrameCoordinator.rebuildPipeline(800, 600, 512, null);
        Assertions.assertFalse(VulkairisFrameCoordinator.isActive());

        // Frame begin and end lifecycle calls should be safe
        VulkairisFrameCoordinator.onBeginFrame(800, 600);
        VulkairisFrameCoordinator.onEndFrame();

        // Enable / disable flag toggle
        VulkairisFrameCoordinator.setEnabled(false);
        Assertions.assertFalse(VulkairisFrameCoordinator.isEnabled());
        VulkairisFrameCoordinator.setEnabled(true);
        Assertions.assertTrue(VulkairisFrameCoordinator.isEnabled());
    }
}
