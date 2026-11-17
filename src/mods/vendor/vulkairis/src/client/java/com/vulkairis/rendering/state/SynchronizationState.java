package com.vulkairis.rendering.state;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.lwjgl.vulkan.VK10.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_INDIRECT_COMMAND_READ_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_MEMORY_READ_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_MEMORY_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_SHADER_READ_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_SHADER_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_READ_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TRANSFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_VERTEX_SHADER_BIT;

/**
 * Translates OpenGL synchronization and compute-dispatch requests into Vulkan equivalents, and
 * records them as pending work.
 *
 * <p>The previous bridge made {@code memoryBarrier} and {@code dispatchCompute} no-ops, which is the
 * most dangerous kind of false positive: the shader "succeeded" while the required barrier or
 * dispatch never happened. This class records a real barrier translation and validates dispatch
 * workgroup counts against device limits so unsupported work is reported rather than dropped.</p>
 */
public final class SynchronizationState {

    // OpenGL barrier bit values (GL 4.2+).
    public static final int GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT = 0x0001;
    public static final int GL_ELEMENT_ARRAY_BARRIER_BIT = 0x0002;
    public static final int GL_UNIFORM_BARRIER_BIT = 0x0004;
    public static final int GL_TEXTURE_FETCH_BARRIER_BIT = 0x0008;
    public static final int GL_SHADER_IMAGE_ACCESS_BARRIER_BIT = 0x0020;
    public static final int GL_COMMAND_BARRIER_BIT = 0x0040;
    public static final int GL_PIXEL_BUFFER_BARRIER_BIT = 0x0080;
    public static final int GL_TEXTURE_UPDATE_BARRIER_BIT = 0x0100;
    public static final int GL_BUFFER_UPDATE_BARRIER_BIT = 0x0200;
    public static final int GL_FRAMEBUFFER_BARRIER_BIT = 0x0400;
    public static final int GL_TRANSFORM_FEEDBACK_BARRIER_BIT = 0x0800;
    public static final int GL_ATOMIC_COUNTER_BARRIER_BIT = 0x1000;
    public static final int GL_SHADER_STORAGE_BARRIER_BIT = 0x2000;
    public static final int GL_ALL_BARRIER_BITS = 0xFFFFFFFF;

    /**
     * A Vulkan pipeline barrier derived from an OpenGL {@code glMemoryBarrier} mask.
     *
     * @param glBarriers   the original GL mask
     * @param srcStageMask Vulkan source stage mask
     * @param dstStageMask Vulkan destination stage mask
     * @param srcAccessMask Vulkan source access mask
     * @param dstAccessMask Vulkan destination access mask
     */
    public record Barrier(
            int glBarriers, int srcStageMask, int dstStageMask, int srcAccessMask, int dstAccessMask) {

        /** @return whether this barrier was an "all commands" barrier */
        public boolean isAllCommands() {
            return (glBarriers & GL_ALL_BARRIER_BITS) == GL_ALL_BARRIER_BITS;
        }
    }

    /**
     * A pending compute dispatch.
     *
     * @param groupsX workgroup count on X
     * @param groupsY workgroup count on Y
     * @param groupsZ workgroup count on Z
     */
    public record DispatchRequest(int groupsX, int groupsY, int groupsZ) {
    }

    private final List<Barrier> pendingBarriers = new CopyOnWriteArrayList<>();
    private final List<DispatchRequest> pendingDispatches = new CopyOnWriteArrayList<>();
    private final List<Long> pendingIndirectDispatches = new CopyOnWriteArrayList<>();

    private volatile int maxWorkGroupCountX = 65535;
    private volatile int maxWorkGroupCountY = 65535;
    private volatile int maxWorkGroupCountZ = 65535;

    /**
     * Records a memory barrier by translating the GL barrier mask into Vulkan stage/access masks.
     *
     * @param glBarriers the GL barrier bit mask
     * @return the translated barrier
     */
    public Barrier requestMemoryBarrier(int glBarriers) {
        Barrier barrier = translate(glBarriers);
        pendingBarriers.add(barrier);
        return barrier;
    }

    /** @return the pending barriers and clears them */
    public List<Barrier> drainBarriers() {
        List<Barrier> copy = new ArrayList<>(pendingBarriers);
        pendingBarriers.clear();
        return Collections.unmodifiableList(copy);
    }

    /** @return the currently pending barriers without clearing them */
    public List<Barrier> getPendingBarriers() {
        return Collections.unmodifiableList(new ArrayList<>(pendingBarriers));
    }

    /**
     * Requests a compute dispatch. The request is rejected (and not queued) when it exceeds the
     * configured device workgroup limits, so unsupported dispatches are reported instead of silently
     * dropped.
     *
     * @param groupsX X workgroup count
     * @param groupsY Y workgroup count
     * @param groupsZ Z workgroup count
     * @return whether the dispatch was accepted
     */
    public boolean dispatchCompute(int groupsX, int groupsY, int groupsZ) {
        if (!isValidDispatch(groupsX, groupsY, groupsZ)) {
            return false;
        }
        pendingDispatches.add(new DispatchRequest(groupsX, groupsY, groupsZ));
        return true;
    }

    /**
     * Requests an indirect compute dispatch.
     *
     * @param indirectOffset byte offset of the indirect dispatch buffer
     * @return whether the request was accepted
     */
    public boolean dispatchComputeIndirect(long indirectOffset) {
        if (indirectOffset < 0) {
            return false;
        }
        pendingIndirectDispatches.add(indirectOffset);
        return true;
    }

    /** @return the pending dispatches and clears them */
    public List<DispatchRequest> drainDispatches() {
        List<DispatchRequest> copy = new ArrayList<>(pendingDispatches);
        pendingDispatches.clear();
        pendingIndirectDispatches.clear();
        return Collections.unmodifiableList(copy);
    }

    public int getPendingDispatchCount() {
        return pendingDispatches.size() + pendingIndirectDispatches.size();
    }

    /** @return whether a dispatch falls within the configured workgroup limits */
    public boolean isValidDispatch(int groupsX, int groupsY, int groupsZ) {
        return groupsX >= 0 && groupsY >= 0 && groupsZ >= 0
                && groupsX <= maxWorkGroupCountX
                && groupsY <= maxWorkGroupCountY
                && groupsZ <= maxWorkGroupCountZ;
    }

    public void setMaxWorkGroupCounts(int x, int y, int z) {
        this.maxWorkGroupCountX = x;
        this.maxWorkGroupCountY = y;
        this.maxWorkGroupCountZ = z;
    }

    public void clear() {
        pendingBarriers.clear();
        pendingDispatches.clear();
        pendingIndirectDispatches.clear();
    }

    /**
     * Translates a GL barrier bit mask into Vulkan stage and access masks. Unknown or empty masks
     * degrade to a conservative top-of-pipe -> all-commands barrier rather than being discarded.
     *
     * @param glBarriers the GL barrier bit mask
     * @return the translated barrier
     */
    public static Barrier translate(int glBarriers) {
        if ((glBarriers & GL_ALL_BARRIER_BITS) == GL_ALL_BARRIER_BITS) {
            return new Barrier(glBarriers,
                    VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                    VK_ACCESS_MEMORY_WRITE_BIT, VK_ACCESS_MEMORY_READ_BIT);
        }

        int srcStage = 0;
        int dstStage = 0;
        int srcAccess = 0;
        int dstAccess = 0;

        int shaderBits = GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_SHADER_STORAGE_BARRIER_BIT
                | GL_ATOMIC_COUNTER_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT
                | GL_UNIFORM_BARRIER_BIT;
        if ((glBarriers & shaderBits) != 0) {
            int shaderStages = VK_PIPELINE_STAGE_VERTEX_SHADER_BIT
                    | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT
                    | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
            srcStage |= shaderStages;
            dstStage |= shaderStages;
            srcAccess |= VK_ACCESS_SHADER_WRITE_BIT;
            dstAccess |= VK_ACCESS_SHADER_READ_BIT;
        }

        if ((glBarriers & GL_FRAMEBUFFER_BARRIER_BIT) != 0) {
            srcStage |= VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
            srcAccess |= VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
            dstStage |= VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT
                    | VK_PIPELINE_STAGE_VERTEX_SHADER_BIT
                    | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
            dstAccess |= VK_ACCESS_SHADER_READ_BIT;
        }

        if ((glBarriers & GL_COMMAND_BARRIER_BIT) != 0) {
            srcStage |= VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
            srcAccess |= VK_ACCESS_SHADER_WRITE_BIT;
            dstStage |= VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT;
            dstAccess |= VK_ACCESS_INDIRECT_COMMAND_READ_BIT;
        }

        int transferBits = GL_BUFFER_UPDATE_BARRIER_BIT | GL_TEXTURE_UPDATE_BARRIER_BIT
                | GL_PIXEL_BUFFER_BARRIER_BIT;
        if ((glBarriers & transferBits) != 0) {
            srcStage |= VK_PIPELINE_STAGE_TRANSFER_BIT;
            srcAccess |= VK_ACCESS_TRANSFER_WRITE_BIT;
            dstStage |= VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
            dstAccess |= VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_TRANSFER_READ_BIT;
        }

        if (srcStage == 0) {
            srcStage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
            dstStage |= VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
        }

        return new Barrier(glBarriers, srcStage, dstStage, srcAccess, dstAccess);
    }
}
