package com.vulkairis.core;

import com.vulkairis.config.ConfigManager;
import com.vulkairis.config.VulkairisConfig;
import com.vulkairis.diagnostics.DiagnosticManager;
import com.vulkairis.diagnostics.FallbackRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Central controller coordinating Vulkairis backend state, rendering path,
 * bridge subsystems, and fallback transitions.
 */
public class VulkairisController {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkairis/Controller");
    private static final VulkairisController INSTANCE = new VulkairisController();

    private volatile BackendState state = BackendState.DISABLED;
    private volatile String activeShaderPackName = "None (Internal)";
    /** Phase 1: last compile failure summary, or "none". */
    private volatile String lastCompileError = "none";
    /** Phase 1: last program-link failure summary, or "none". */
    private volatile String lastLinkError = "none";
    /** Phase 1: currently executing Iris pass, or "none" (populated as pass routing lands).
     * Canonical per-frame pack pass evidence: the terrain door writes the effective pack
     * pass label here on every handed-pass replacement (plus a StageDiagnostics line),
     * mirrored into RenderStateTracker for the plan's §6.5/§8 acceptance. */
    private volatile String activePass = "none";
    /** Phase 1: current render target, or "none" (populated as target work lands).
     * Canonical per-frame pack target evidence alongside {@link #activePass}. */
    private volatile String currentTarget = "none";
    /** Phase 1: a resource reload / pack change is waiting to rebuild pipelines. */
    private volatile boolean pendingReload = false;
    private final AtomicLong frameCount = new AtomicLong(0);
    private final AtomicLong compiledShadersCount = new AtomicLong(0);
    private final AtomicLong createdPipelinesCount = new AtomicLong(0);
    /** Phase E: the most recent fully-attributed fallback engagement, or {@code null}. */
    private volatile FallbackRecord.Entry lastFallback = null;
    /** Phase 1: production readiness state machine. */
    private final PackReadinessStateMachine readinessStateMachine = new PackReadinessStateMachine();

    private VulkairisController() {
    }

    public static VulkairisController getInstance() {
        return INSTANCE;
    }

    public PackReadinessStateMachine getReadinessStateMachine() {
        return readinessStateMachine;
    }

    public PackReadinessState getReadinessState() {
        return readinessStateMachine.getState();
    }

    public synchronized void initialize() {
        LOGGER.info("[Vulkairis] Initializing Vulkairis Controller...");
        VulkairisConfig config = ConfigManager.loadConfig();

        if (!config.isEnabled()) {
            LOGGER.info("[Vulkairis] Mod is disabled via configuration.");
            this.state = BackendState.DISABLED;
            return;
        }

        this.state = BackendState.INITIALIZING;
        DiagnosticManager.log(DiagnosticManager.Category.VULKAN, "Starting Vulkairis controller initialization");

        try {
            // Further subsystem startup will transition state to READY once verified
            if (com.vulkairis.compatibility.CompatibilityMatrix.shouldFallback()) {
                triggerFallback("GPU Compatibility Failure", "GPU vendor marked as unstable/broken in compatibility matrix");
            } else {
                LOGGER.info("[Vulkairis] Controller initial state configured.");
            }
        } catch (Throwable t) {
            triggerFallback("Controller initialization failed", t.getMessage());
        }

    }

    public BackendState getState() {
        return this.state;
    }

    public synchronized void setState(BackendState newState) {
        LOGGER.info("[Vulkairis] Backend state transition: {} -> {}", this.state, newState);
        this.state = newState;
    }

    public synchronized void triggerFallback(String reason, String details) {
        // Phase E: every engagement records the full honesty identity. The legacy two-argument
        // path derives its context (requested backend from config, vanilla VulkanMod as the
        // effective path, current pass/pack/target as stage/pack/shader) so no engagement
        // ever lands without requested-vs-effective, stage, pack+shader and excerpt.
        triggerFallbackDetailed(requestedBackendName(), EFFECTIVE_FALLBACK_BACKEND,
                activePass, activeShaderPackName, currentTarget, reason, details);
    }

    /**
     * Phase E: engages fallback with an explicit honesty record. The effective backend is the
     * path actually rendering afterwards; the reason and log excerpt are user-visible, so the
     * excerpt is sanitized and bounded before it is stored or mirrored by status text.
     */
    public synchronized void triggerFallbackDetailed(
            String requestedBackend, String effectiveBackend, String stage,
            String packId, String shaderId, String reason, String logExcerpt) {
        LOGGER.warn("[Vulkairis] Triggering FALLBACK mode. Reason: {} ({})", reason, logExcerpt);
        this.state = BackendState.FALLBACK;
        FallbackRecord.Entry entry = DiagnosticManager.recordFallbackStructured(
                requestedBackend, effectiveBackend, stage, packId, shaderId, reason, logExcerpt);
        this.lastFallback = entry;
    }

    /** The backend that renders after any fallback: unaffected vanilla VulkanMod rendering. */
    public static final String EFFECTIVE_FALLBACK_BACKEND = "vanilla-vulkanmod";

    /**
     * @return the requested backend name derived from config, headless-safe
     * ({@code "unknown"} when config is unreadable)
     */
    public static String requestedBackendName() {
        try {
            VulkairisConfig config = ConfigManager.getConfig();
            if (config.isAutoBackend()) {
                return "Auto";
            }
            return config.isUseVulkanRenderer() ? "Vulkan" : "OpenGL";
        } catch (Throwable t) {
            return "unknown";
        }
    }

    /** @return the most recent fully-attributed fallback engagement, or {@code null} */
    public synchronized FallbackRecord.Entry getLastFallbackRecord() {
        FallbackRecord.Entry entry = lastFallback;
        if (entry == null) {
            entry = DiagnosticManager.getLastFallbackRecord();
        }
        return entry;
    }

    /** @return recent fallback engagements, newest last */
    public List<FallbackRecord.Entry> getFallbackHistory(int limit) {
        return DiagnosticManager.getFallbackHistory(limit);
    }

    /**
     * Phase E: the last fallback engagement rendered as status lines, verbatim. GUI status and
     * {@code /vulkairis status|shader} print these lines unchanged, so a fallback is never
     * mistaken for working shaders.
     */
    public synchronized List<String> getFallbackStatusLines() {
        FallbackRecord.Entry entry = getLastFallbackRecord();
        if (entry == null && !isFallbackTriggered()) {
            return List.of("fallback: none (backend healthy)");
        }
        List<String> lines = new ArrayList<>(FallbackRecord.describe(entry));
        if (isFallbackTriggered() && entry == null) {
            lines.add("state: FALLBACK (legacy engagement, no structured record)");
        }
        return List.copyOf(lines);
    }

    public boolean isVulkanActive() {
        return this.state.isOperational() && ConfigManager.getConfig().isUseVulkanRenderer();
    }

    public String getActiveShaderPackName() {
        return activeShaderPackName;
    }

    public void setActiveShaderPackName(String activeShaderPackName) {
        this.activeShaderPackName = (activeShaderPackName != null) ? activeShaderPackName : "None (Internal)";
    }

    public long incrementFrameCount() {
        return frameCount.incrementAndGet();
    }

    public long getFrameCount() {
        return frameCount.get();
    }

    public void incrementShaderCount() {
        compiledShadersCount.incrementAndGet();
    }

    public long getCompiledShadersCount() {
        return compiledShadersCount.get();
    }

    public void incrementPipelineCount() {
        createdPipelinesCount.incrementAndGet();
    }

    public long getCreatedPipelinesCount() {
        return createdPipelinesCount.get();
    }

    public void reset() {
        frameCount.set(0);
        this.state = BackendState.INITIALIZING;
    }

    public synchronized String getLastCompileError() {
        DiagnosticManager.DiagnosticRecord record = DiagnosticManager.getLastCompileError();
        if (record != null) {
            return record.id() + " " + record.message()
                    + (record.details() != null ? ": " + record.details() : "");
        }
        return lastCompileError;
    }

    public synchronized void setLastCompileError(String lastCompileError) {
        this.lastCompileError = (lastCompileError != null) ? lastCompileError : "none";
    }

    public synchronized String getLastLinkError() {
        DiagnosticManager.DiagnosticRecord record = DiagnosticManager.getLastLinkError();
        if (record != null) {
            return record.id() + " " + record.message()
                    + (record.details() != null ? ": " + record.details() : "");
        }
        return lastLinkError;
    }

    public synchronized void setLastLinkError(String lastLinkError) {
        this.lastLinkError = (lastLinkError != null) ? lastLinkError : "none";
    }

    public synchronized String getActivePass() {
        return activePass;
    }

    /**
     * Records the effective pack pass label for per-frame evidence. Canonical store;
     * the terrain door also mirrors the label into RenderStateTracker.
     */
    public synchronized void setActivePass(String activePass) {
        this.activePass = (activePass != null) ? activePass : "none";
    }

    public synchronized String getCurrentTarget() {
        return currentTarget;
    }

    /**
     * Records the effective pack target label for per-frame evidence. Canonical store;
     * the terrain door also mirrors the label into RenderStateTracker.
     */
    public synchronized void setCurrentTarget(String currentTarget) {
        this.currentTarget = (currentTarget != null) ? currentTarget : "none";
    }

    public synchronized boolean isPendingReload() {
        return pendingReload;
    }

    public synchronized void setPendingReload(boolean pendingReload) {
        this.pendingReload = pendingReload;
    }

    public boolean isFallbackTriggered() {
        return this.state == BackendState.FALLBACK;
    }

    /**
     * Phase 12: explicit fallback guidance. Names the reason, preserves normal VulkanMod
     * rendering, and tells the user exactly how to run the affected pack instead.
     */
    public synchronized String getFallbackGuidance() {
        if (!isFallbackTriggered()) {
            return "";
        }
        return "Vulkairis disabled shader execution (" + DiagnosticManager.getLastFallbackReason() + "). "
                + "Vanilla VulkanMod rendering continues unaffected. "
                + "To use this shader pack, relaunch with the OpenGL renderer and Iris.";
    }
}
