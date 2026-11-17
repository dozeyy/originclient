package com.vulkairis.rendering.sync;

import com.vulkairis.rendergraph.ResourceUsage;

import static org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_SHADER_READ_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_SHADER_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_READ_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TRANSFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_VERTEX_SHADER_BIT;

/**
 * Maps a render-graph {@link ResourceUsage} to the Vulkan image layout, pipeline stage mask, and
 * access mask a barrier needs.
 *
 * <p>Vulkan requires an image to be in the right layout for each use, and requires a barrier between
 * a write and a later read of the same image. Encoding that mapping once here means the barrier
 * generator — not every call site — decides layouts and masks.</p>
 */
public final class ImageLayoutMapper {

    private ImageLayoutMapper() {
    }

    /**
     * @param usage how a pass uses a resource
     * @return the {@code VkImageLayout} required for that usage
     */
    public static int layoutFor(ResourceUsage usage) {
        return switch (usage) {
            case COLOR_ATTACHMENT -> VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
            case DEPTH_ATTACHMENT -> VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL;
            case SAMPLED -> VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            case STORAGE -> VK_IMAGE_LAYOUT_GENERAL;
            case TRANSFER_SRC -> VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
            case TRANSFER_DST -> VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
            case PRESENT -> VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
        };
    }

    /**
     * @param usage how a pass uses a resource
     * @return the pipeline stage mask in which that usage executes
     */
    public static int stageMaskFor(ResourceUsage usage) {
        return switch (usage) {
            case COLOR_ATTACHMENT -> VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
            case DEPTH_ATTACHMENT -> VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT
                    | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT;
            case SAMPLED -> VK_PIPELINE_STAGE_VERTEX_SHADER_BIT
                    | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT
                    | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
            case STORAGE -> VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT
                    | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
            case TRANSFER_SRC, TRANSFER_DST -> VK_PIPELINE_STAGE_TRANSFER_BIT;
            case PRESENT -> VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT;
        };
    }

    /**
     * @param usage how a pass uses a resource
     * @return the access mask that usage performs
     */
    public static int accessMaskFor(ResourceUsage usage) {
        return switch (usage) {
            case COLOR_ATTACHMENT -> VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
            case DEPTH_ATTACHMENT -> VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
            case SAMPLED -> VK_ACCESS_SHADER_READ_BIT;
            case STORAGE -> VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
            case TRANSFER_SRC -> VK_ACCESS_TRANSFER_READ_BIT;
            case TRANSFER_DST -> VK_ACCESS_TRANSFER_WRITE_BIT;
            case PRESENT -> 0;
        };
    }
}
