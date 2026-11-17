package com.vulkairis.rendering.exec;

import com.vulkairis.config.ConfigManager;
import com.vulkairis.iris.IrisBridge;
import com.vulkairis.iris.ShaderPackProgramCatalog;
import com.vulkairis.resource.RenderTargetRegistry;
import com.vulkairis.rendering.TemporalHistoryInvalidator;
import net.minecraft.client.Minecraft;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * Coordinates frame-level render graph execution and lifecycle between Minecraft/VulkanMod and Vulkairis.
 *
 * <p>Drives {@link PackRenderPipeline} across frame boundaries, synchronizes render target
 * dimensions via {@link ResizeCoordinator}, and tracks whether render-graph execution is currently active.</p>
 */
public final class VulkairisFrameCoordinator {

    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkairis/FrameCoordinator");

    private static volatile PackRenderPipeline activePipeline = null;
    private static volatile ResizeCoordinator resizeCoordinator = null;
    /**
     * Phase 11: pipeline generation pinned for the current frame. A generation change between
     * frames means the plan was rebuilt (pack/dimension/option change); the next frame is
     * dropped once and the pin moves, so a stale plan never draws. -1 pins on first execution.
     */
    private static volatile long pinnedGeneration = -1L;
    /** Cumulative per-pass execution record backing the compatibility report. */
    private static final PassExecutionRecord executionRecord = new PassExecutionRecord();
    /** Last frame's trace validation (problems empty when the frame was clean). */
    private static volatile FrameTraceValidator.FrameTrace lastTrace =
            new FrameTraceValidator.FrameTrace(java.util.List.of());
    private static volatile boolean enabled = true;
    private static volatile boolean active = false;
    /** Phase 11: temporal history invalidation owner for the active pipeline. */
    private static final TemporalHistoryInvalidator TEMPORAL_HISTORY = new TemporalHistoryInvalidator();

    private VulkairisFrameCoordinator() {
    }

    /**
     * @return true if the frame coordinator is actively driving an executable render graph
     */
    public static boolean isActive() {
        return active && enabled && activePipeline != null && activePipeline.hasGraph();
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean isEnabled) {
        enabled = isEnabled;
    }

    public static PackRenderPipeline getActivePipeline() {
        return activePipeline;
    }

    public static void setActivePipeline(PackRenderPipeline pipeline) {
        activePipeline = pipeline;
        if (pipeline != null && pipeline.getTargets() != null) {
            Set<String> fixed = Set.of(RenderTargetRegistry.SHADOW_DEPTH, RenderTargetRegistry.SHADOW_COLOR);
            resizeCoordinator = new ResizeCoordinator(
                    pipeline.getTargets(),
                    fixed,
                    pipeline.getTargets().getWidth(),
                    pipeline.getTargets().getHeight()
            );
            active = pipeline.hasGraph();
        } else {
            resizeCoordinator = null;
            active = false;
        }
    }

    public static ResizeCoordinator getResizeCoordinator() {
        return resizeCoordinator;
    }

    /** @return the pipeline generation pinned for the current frame (-1 before first execution) */
    public static long pinnedGeneration() {
        return pinnedGeneration;
    }

    /** Resets the frame pin and execution record; tests only. */
    public static void resetFramePinForTests() {
        pinnedGeneration = -1L;
        executionRecord.clear();
        lastTrace = new FrameTraceValidator.FrameTrace(java.util.List.of());
    }

    /** @return the cumulative per-pass execution record */
    public static PassExecutionRecord lastExecutionRecord() {
        return executionRecord;
    }

    /** @return the last frame's trace validation */
    public static FrameTraceValidator.FrameTrace lastFrameTrace() {
        return lastTrace;
    }

    /**
     * Pins the pipeline generation for this frame.
     *
     * @return the drop reason when the generation moved since the pin (and re-pins),
     * or empty when the plan is stable
     */
    public static java.util.Optional<String> checkFramePlan() {
        long current = com.vulkairis.iris.SourceProvenance.currentGeneration();
        long pinned = pinnedGeneration;
        if (pinned < 0L) {
            pinnedGeneration = current;
            return java.util.Optional.empty();
        }
        if (pinned != current) {
            pinnedGeneration = current;
            executionRecord.clear();
            TEMPORAL_HISTORY.invalidate(TemporalHistoryInvalidator.Event.PACK_SWITCH);
            TEMPORAL_HISTORY.invalidate(TemporalHistoryInvalidator.Event.SHADER_RESET);
            return java.util.Optional.of("pipeline generation changed " + pinned + " -> "
                    + current + "; plan rebuilt, frame dropped");
        }
        return java.util.Optional.empty();
    }

    /**
     * Initializes or reconfigures the pipeline when the shader pack changes or dimensions change.
     *
     * <p>Phase E: the chain plan is solved once per pack-dimension profile
     * ({@link PackChainPlan#planFor}) and replayed on later frames without re-solving. When the
     * cached plan for the current profile is already installed, this is a no-op replay; only a
     * profile change (pack, dimension, resolution, generation, program set) solves a new plan and
     * rebuilds the pipeline.
     */
    public static void rebuildPipeline(int width, int height, int shadowResolution,
                                      PackRenderPipeline.BindingResolver resolver) {
        try {
            ShaderPackProgramCatalog catalog = IrisBridge.getProgramCatalog();
            if (catalog == null || !catalog.hasGraphicsExecutionPath()) {
                setActivePipeline(null);
                return;
            }
            if (width <= 0 || height <= 0) {
                width = 1920;
                height = 1080;
            }
            if (shadowResolution <= 0) {
                shadowResolution = 1024;
            }
            String packId = IrisBridge.currentPackId();
            String dimension = IrisBridge.currentDimensionId();
            long generation = com.vulkairis.iris.SourceProvenance.currentGeneration();
            String profileKey = PackChainPlan.profileKey(
                    packId, dimension, width, height, shadowResolution, generation, catalog);

            PackChainPlan installed = activePipeline != null ? activePipeline.getChainPlan() : null;
            if (installed != null && profileKey.equals(installed.profileKey())) {
                // Replay: the installed pipeline already executes this exact plan.
                IrisBridge.noteChainPlan(installed);
                LOGGER.debug("[Vulkairis/FrameCoordinator] Replaying chain plan {} (no re-solve)",
                        profileKey);
                return;
            }

            PackChainPlan plan = PackChainPlan.planFor(
                    catalog, packId, dimension, width, height, shadowResolution, generation);
            PackRenderPipeline pipeline = PackRenderPipeline.buildFromPlan(plan, resolver);
            setActivePipeline(pipeline);
            IrisBridge.noteChainPlan(plan);
            LOGGER.info("[Vulkairis/FrameCoordinator] Rebuilt pack render pipeline (active={}, hasGraph={}, plan={})",
                    active, pipeline.hasGraph(), plan);
        } catch (Throwable t) {
            LOGGER.error("[Vulkairis/FrameCoordinator] Failed to build pack render pipeline: {}", t.getMessage(), t);
            setActivePipeline(null);
        }
    }

    /**
     * Phase E: the chain plan backing the active pipeline, or {@code null} when no planned
     * pipeline is installed.
     */
    /** @return the temporal history invalidator for this coordinator */
    public static TemporalHistoryInvalidator temporalHistory() {
        return TEMPORAL_HISTORY;
    }

    public static PackChainPlan getChainPlan() {
        PackRenderPipeline pipeline = activePipeline;
        return pipeline != null ? pipeline.getChainPlan() : null;
    }

    /**
     * Phase E: the active chain plan rendered as honest status lines, substitute labeled when
     * present. Never empty: without a plan it says so instead of implying shaders work.
     */
    public static java.util.List<String> describeChainPlan() {
        PackChainPlan plan = getChainPlan();
        if (plan == null) {
            return java.util.List.of(
                    "chain plan: none (no shader pack execution path installed)");
        }
        return plan.statusLines();
    }

    /** Clears the chain-plan cache; tests only. The solve counter is monotonic and is kept. */
    public static void resetChainPlanForTests() {
        PackChainPlan.clearForTests();
    }

    /**
     * Called at the start of a frame. Refreshes dimensions if needed.
     */
    public static void onBeginFrame(int width, int height) {
        if (resizeCoordinator != null && width > 0 && height > 0) {
            resizeCoordinator.onResize(width, height);
        }
    }

    /**
     * Called at pass completion (or before swapchain presentation) to record composite/post passes.
     */
    public static RenderGraphExecutor.ExecutionReport onExecutePasses(VkCommandBuffer cmdBuffer) {
        if (!isActive() || cmdBuffer == null) {
            return new RenderGraphExecutor.ExecutionReport(0, 0, false, "coordinator inactive");
        }
        java.util.Optional<String> planProblem = checkFramePlan();
        if (planProblem.isPresent()) {
            lastTrace = new FrameTraceValidator.FrameTrace(
                    java.util.List.of(planProblem.get()));
            executionRecord.clear();
            LOGGER.info("[Vulkairis/FrameCoordinator] {}", planProblem.get());
            return new RenderGraphExecutor.ExecutionReport(0, 0, false, planProblem.get());
        }
        try {
            java.util.List<String> recorded = new java.util.ArrayList<>();
            java.util.List<String> skipped = new java.util.ArrayList<>();

            // ponytail: executing composite chain directly through the active pipeline
            // Transport uniforms to VulkanMod UBOs before executing the graph
            if (activePipeline != null) {
                com.vulkairis.bridge.VulkanModProgramBridge.uploadUniforms(activePipeline.getLinkedPipeline());
            }
            RenderGraphExecutor.ExecutionReport report =
                    activePipeline.executeTraced(recorded, skipped,
                            activePipeline.getFramebuffers(), activePipeline.getRenderPasses());

            java.util.List<String> expected = new java.util.ArrayList<>();
            if (activePipeline.getGraph() != null) {
                for (com.vulkairis.rendergraph.PassNode pass
                        : activePipeline.getGraph().getExecutionOrder()) {
                    expected.add(pass.name());
                }
            }
            lastTrace = FrameTraceValidator.validate(expected, recorded);
            for (String problem : lastTrace.problems()) {
                LOGGER.warn("[Vulkairis/FrameCoordinator] Frame trace: {}", problem);
            }
            for (String pass : recorded) {
                executionRecord.record(pass, PassExecutionRecord.Outcome.RAN, "recorded");
            }
            for (String pass : skipped) {
                executionRecord.record(pass, PassExecutionRecord.Outcome.REJECTED,
                        "no binding or gated compute");
            }
            for (String pass : expected) {
                if (!recorded.contains(pass) && !skipped.contains(pass)) {
                    executionRecord.record(pass, PassExecutionRecord.Outcome.FELL_BACK,
                            "expected but not recorded");
                }
            }
            return report;
        } catch (Throwable t) {
            LOGGER.error("[Vulkairis/FrameCoordinator] Error recording render graph execution: {}", t.getMessage(), t);
            return new RenderGraphExecutor.ExecutionReport(0, 0, false, "error: " + t.getMessage());
        }
    }

    /**
     * Called at the end of a frame.
     */
    public static void onEndFrame() {
        // Post-frame cleanup or metrics tracking if needed
    }
}
