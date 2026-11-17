package com.vulkairis.diagnostics;

import com.vulkairis.core.VulkairisController;

/**
 * Narrow exception policy for render-thread hooks (Phase 12).
 *
 * <p>Expected runtime failures are caught narrowly, recorded once (deduplicated), and never
 * crash the frame. Truly unrecoverable JVM failures ({@link Error}: device loss, linkage
 * breakage, invalid command-buffer state) escalate through a controlled bridge fallback with
 * the cause preserved, and the frame continues on the vanilla path. Broad {@code catch
 * (Throwable)} as silent control flow is banned here.</p>
 */
public final class RenderHookGuard {

    private RenderHookGuard() {
    }

    /**
     * Runs a render-hook body under the hook policy.
     *
     * @param hook  hook identity for diagnostics
     * @param phase pipeline phase for the diagnostic record
     * @param body  the hook body
     */
    public static void runHook(String hook, DiagnosticManager.Phase phase, Runnable body) {
        try {
            body.run();
        } catch (RuntimeException e) {
            DiagnosticManager.logStructured(DiagnosticManager.Category.PIPELINE, phase,
                    "Render hook failure", hook + ": " + e.getMessage(), e,
                    null, null, null, -1, null,
                    "Hook skipped this frame; check the named subsystem");
        } catch (Error fatal) {
            DiagnosticManager.logStructured(DiagnosticManager.Category.PIPELINE, phase,
                    "Unrecoverable render hook failure", hook + ": " + fatal, fatal,
                    null, null, null, -1, null,
                    "Bridge moved to fallback; vanilla rendering continues");
            VulkairisController.getInstance()
                    .triggerFallback("Render hook failure: " + hook, fatal.toString());
        }
    }
}
