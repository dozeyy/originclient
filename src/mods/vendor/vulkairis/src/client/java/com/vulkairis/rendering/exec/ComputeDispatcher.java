package com.vulkairis.rendering.exec;

import com.vulkairis.diagnostics.StageDiagnostics;
import com.vulkairis.rendering.state.SynchronizationState;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.LongBuffer;

/**
 * Phase 9: records real compute dispatch commands.
 *
 * <p>Replaces the no-op {@code dispatchCompute}/{@code dispatchComputeIndirect}. Workgroup counts are
 * validated against device limits before recording so an unsupported dispatch is reported rather than
 * silently dropped, and a compute-to-graphics barrier is provided so later passes see the results.</p>
 */
public final class ComputeDispatcher {

    private ComputeDispatcher() {
    }

    /**
     * Binds the compute pipeline/descriptor set and records a direct dispatch.
     *
     * @return whether a dispatch was recorded
     */
    public static boolean dispatch(VkCommandBuffer cmd,
                                   long pipeline,
                                   long pipelineLayout,
                                   long descriptorSet,
                                   int groupsX, int groupsY, int groupsZ,
                                   SynchronizationState synchronization) {
        if (cmd == null || pipeline == 0L) {
            return false;
        }
        if (synchronization != null && !synchronization.isValidDispatch(groupsX, groupsY, groupsZ)) {
            StageDiagnostics.warn("compute dispatch",
                    "rejected out-of-limit dispatch " + groupsX + "x" + groupsY + "x" + groupsZ);
            return false;
        }

        VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
        bindDescriptorSet(cmd, pipelineLayout, descriptorSet);
        VK10.vkCmdDispatch(cmd, groupsX, groupsY, groupsZ);
        return true;
    }

    /**
     * Binds the compute pipeline/descriptor set and records an indirect dispatch.
     *
     * @return whether an indirect dispatch was recorded
     */
    public static boolean dispatchIndirect(VkCommandBuffer cmd,
                                           long pipeline,
                                           long pipelineLayout,
                                           long descriptorSet,
                                           long indirectBuffer,
                                           long indirectOffset) {
        if (cmd == null || pipeline == 0L || indirectBuffer == 0L) {
            return false;
        }
        VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
        bindDescriptorSet(cmd, pipelineLayout, descriptorSet);
        VK10.vkCmdDispatchIndirect(cmd, indirectBuffer, indirectOffset);
        return true;
    }

    /**
     * Inserts the compute-to-graphics barrier so subsequent graphics/compute passes observe the
     * writes produced by a dispatch.
     *
     * @param cmd the command buffer
     */
    public static void barrierAfterCompute(VkCommandBuffer cmd) {
        if (cmd == null) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            org.lwjgl.vulkan.VkMemoryBarrier.Buffer barrier =
                    org.lwjgl.vulkan.VkMemoryBarrier.calloc(1, stack)
                            .sType(VK10.VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                            .srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT)
                            .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT);
            VK10.vkCmdPipelineBarrier(
                    cmd,
                    VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK10.VK_PIPELINE_STAGE_VERTEX_SHADER_BIT
                            | VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT
                            | VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    0, barrier, null, null);
        }
    }

    private static void bindDescriptorSet(VkCommandBuffer cmd, long pipelineLayout, long descriptorSet) {
        if (descriptorSet == 0L || pipelineLayout == 0L) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer sets = stack.longs(descriptorSet);
            VK10.vkCmdBindDescriptorSets(
                    cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, sets, null);
        }
    }
}
