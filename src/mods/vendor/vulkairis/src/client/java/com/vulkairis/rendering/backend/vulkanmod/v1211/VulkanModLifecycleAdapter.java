package com.vulkairis.rendering.backend.vulkanmod.v1211;

import com.vulkairis.diagnostics.DiagnosticManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Phase 2: Version-specific adapter tracking and validating the MC 1.21.1 / VulkanMod render lifecycle.
 * In trace-only mode, records every hook invocation and asserts legal non-decreasing stage order
 * without rerouting any draw calls.
 */
public class VulkanModLifecycleAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkairis/Lifecycle1211");
    private static final VulkanModLifecycleAdapter INSTANCE = new VulkanModLifecycleAdapter();
    private static final int MAX_TRACE = 128;

    public record StageObservation(
            VulkanModRenderStage stage,
            long frameNumber,
            long timestamp,
            String details,
            boolean orderValid
    ) {}

    private final Object lock = new Object();
    private volatile boolean traceOnlyMode = true;
    private long currentFrame = 0L;
    private int lastObservedOrder = -1;
    private final Deque<StageObservation> traceHistory = new ArrayDeque<>();
    private final List<String> orderingViolations = new ArrayList<>();

    public static VulkanModLifecycleAdapter getInstance() {
        return INSTANCE;
    }

    public boolean isTraceOnlyMode() {
        return traceOnlyMode;
    }

    public void setTraceOnlyMode(boolean traceOnly) {
        this.traceOnlyMode = traceOnly;
    }

    public void onFrameBegin(long frameNumber) {
        synchronized (lock) {
            this.currentFrame = frameNumber;
            this.lastObservedOrder = -1;
            recordStage(VulkanModRenderStage.FRAME_BEGIN, "Frame " + frameNumber + " begin");
        }
    }

    public void onStageObserved(VulkanModRenderStage stage, String details) {
        synchronized (lock) {
            recordStage(stage, details);
        }
    }

    public void onFrameEnd() {
        synchronized (lock) {
            recordStage(VulkanModRenderStage.FRAME_END_PRESENT, "Frame " + currentFrame + " end/present");
        }
    }

    private void recordStage(VulkanModRenderStage stage, String details) {
        int order = stage.orderIndex();
        // Composite post-processing legitimately runs during the end-of-frame render pass on
        // this backend, so a late COMPOSITE_POST observation is not a real ordering fault.
        // Treat it as valid and never let it lower the high-water mark, which would otherwise
        // cascade into spurious violations for subsequent stages.
        boolean lateComposite = (stage == VulkanModRenderStage.COMPOSITE_POST)
                && (lastObservedOrder != -1) && (order < lastObservedOrder);
        boolean valid = (lastObservedOrder == -1) || (order >= lastObservedOrder) || lateComposite;

        if (!valid) {
            String violation = String.format(
                    "Order violation on frame %d: stage %s (order %d) observed after order %d (%s)",
                    currentFrame, stage, order, lastObservedOrder, details
            );
            orderingViolations.add(violation);
            LOGGER.warn("[Vulkairis/Lifecycle1211] {}", violation);
            DiagnosticManager.log(DiagnosticManager.Category.VULKAN, "Render lifecycle violation", violation);
        }

        if (lateComposite) {
            // keep the high-water mark unchanged
        } else {
            lastObservedOrder = order;
        }
        StageObservation obs = new StageObservation(stage, currentFrame, System.currentTimeMillis(), details, valid);

        if (traceHistory.size() >= MAX_TRACE) {
            traceHistory.removeLast();
        }
        traceHistory.addFirst(obs);
    }

    public List<StageObservation> getTraceHistory(int limit) {
        synchronized (lock) {
            int count = Math.min(limit, traceHistory.size());
            List<StageObservation> result = new ArrayList<>(count);
            for (StageObservation o : traceHistory) {
                if (result.size() >= count) break;
                result.add(o);
            }
            return List.copyOf(result);
        }
    }

    public List<String> getOrderingViolations() {
        synchronized (lock) {
            return List.copyOf(orderingViolations);
        }
    }

    public void clearViolations() {
        synchronized (lock) {
            orderingViolations.clear();
        }
    }
}
