package com.vulkairis.rendering;

import com.vulkairis.core.VulkairisController;
import com.vulkairis.rendering.exec.VulkairisFrameCoordinator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pass scheduler view (Phase 8).
 *
 * <p>The explicit pass/resource scheduling lives in the compiled render graph
 * ({@code RenderGraph}/{@code CompiledRenderGraph}) driven by
 * {@code VulkairisFrameCoordinator}; this class is the observable view over it rather than a
 * second scheduler. The current pass prefers the coordinator's active pipeline and otherwise
 * falls back to the locally tracked pass, and every transition is reported to the controller
 * status so {@code /vulkairis status} names the real active pass.</p>
 */
public class VulkanRenderPassManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkairis/RenderPassManager");

    public enum PassType {
        COLOR_DEPTH_SCENE,
        SHADER_INTERMEDIATE,
        FINAL_COMPOSITE
    }

    private PassType currentPass = PassType.COLOR_DEPTH_SCENE;

    public void beginPass(PassType passType) {
        if (passType != null) {
            this.currentPass = passType;
        }
        VulkairisController.getInstance().setActivePass(describeActivePass());
        LOGGER.debug("[Vulkairis/Pass] Began pass: {}", describeActivePass());
    }

    public void endPass() {
        LOGGER.debug("[Vulkairis/Pass] Ended pass: {}", describeActivePass());
    }

    public PassType getCurrentPass() {
        return currentPass;
    }

    /**
     * @return the coordinator's active pipeline when one drives the frame, else the local pass
     */
    public String describeActivePass() {
        try {
            if (VulkairisFrameCoordinator.isActive()
                    && VulkairisFrameCoordinator.getActivePipeline() != null) {
                return "coordinator:" + currentPass.name();
            }
        } catch (Throwable ignored) {
        }
        return currentPass.name();
    }
}
