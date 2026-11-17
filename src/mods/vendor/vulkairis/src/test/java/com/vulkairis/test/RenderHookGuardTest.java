package com.vulkairis.test;

import com.vulkairis.core.BackendState;
import com.vulkairis.core.VulkairisController;
import com.vulkairis.diagnostics.DiagnosticManager;
import com.vulkairis.diagnostics.RenderHookGuard;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Phase 12: render hooks fail narrowly and loudly, and fatal failures fall back with guidance
 * instead of crashing the frame.
 */
public class RenderHookGuardTest {

    @Test
    public void testNormalPathRuns() {
        AtomicBoolean ran = new AtomicBoolean(false);
        RenderHookGuard.runHook("probe", DiagnosticManager.Phase.DRAW, () -> ran.set(true));
        Assertions.assertTrue(ran.get());
    }

    @Test
    public void testExpectedFailureRecordedOnce() {
        DiagnosticManager.clearHistory();
        long suppressedBefore = DiagnosticManager.getSuppressedDuplicateCount();
        RenderHookGuard.runHook("flaky-hook", DiagnosticManager.Phase.DRAW, () -> {
            throw new IllegalStateException("unique-flaky-failure-1");
        });
        RenderHookGuard.runHook("flaky-hook", DiagnosticManager.Phase.DRAW, () -> {
            throw new IllegalStateException("unique-flaky-failure-1");
        });
        Assertions.assertEquals(1, DiagnosticManager.getRecentRecords(50).size(),
                "identical hook failures log once, not per frame");
        Assertions.assertEquals(suppressedBefore + 1, DiagnosticManager.getSuppressedDuplicateCount());
    }

    @Test
    public void testFatalFailureFallsBackWithGuidance() {
        try {
            RenderHookGuard.runHook("dying-hook", DiagnosticManager.Phase.DRAW, () -> {
                throw new LinkageError("unique-fatal-failure-1");
            });
            Assertions.assertTrue(VulkairisController.getInstance().isFallbackTriggered(),
                    "unrecoverable hook failures must fall back");
            String guidance = VulkairisController.getInstance().getFallbackGuidance();
            Assertions.assertTrue(guidance.contains("OpenGL"),
                    "guidance must name the relaunch path: " + guidance);
            Assertions.assertTrue(guidance.contains("unique-fatal-failure-1"),
                    "guidance must carry the reason: " + guidance);
        } finally {
            VulkairisController.getInstance().setState(BackendState.DISABLED);
            Assertions.assertEquals("", VulkairisController.getInstance().getFallbackGuidance(),
                    "no fallback means no guidance");
        }
    }
}
