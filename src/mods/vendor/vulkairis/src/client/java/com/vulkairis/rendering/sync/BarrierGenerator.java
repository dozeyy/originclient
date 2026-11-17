package com.vulkairis.rendering.sync;

import com.vulkairis.rendergraph.CompiledRenderGraph;
import com.vulkairis.rendergraph.ResourceTransition;
import com.vulkairis.rendergraph.ResourceUsage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;

/**
 * Generates Vulkan pipeline barriers from a compiled render graph's recorded usage transitions.
 *
 * <p>The graph knows a resource moved from (say) color-attachment use at one pass to sampled use at a
 * later pass. This turns each such change into a real image-layout transition with the correct source
 * and destination stage/access masks, which is what makes multi-pass chains reliable rather than
 * relying on submission order.</p>
 */
public final class BarrierGenerator {

    /**
     * A Vulkan pipeline barrier for one resource on one pass boundary.
     *
     * @param resourceName resource the barrier applies to
     * @param fromLayout   old {@code VkImageLayout}
     * @param toLayout     new {@code VkImageLayout}
     * @param srcStageMask source stage mask
     * @param dstStageMask destination stage mask
     * @param srcAccessMask source access mask
     * @param dstAccessMask destination access mask
     * @param passName     the pass at which the barrier is applied
     * @param initial      whether this is the resource's first use in the frame (from UNDEFINED)
     */
    public record PipelineBarrier(
            String resourceName,
            int fromLayout,
            int toLayout,
            int srcStageMask,
            int dstStageMask,
            int srcAccessMask,
            int dstAccessMask,
            String passName,
            boolean initial) {

        @Override
        public String toString() {
            return resourceName + " " + Integer.toHexString(fromLayout) + "->" + Integer.toHexString(toLayout)
                    + " @" + passName + (initial ? " (initial)" : "");
        }
    }

    private BarrierGenerator() {
    }

    /**
     * @param graph a compiled render graph
     * @return the barriers required, in execution order
     */
    public static List<PipelineBarrier> generate(CompiledRenderGraph graph) {
        List<PipelineBarrier> barriers = new ArrayList<>();
        for (ResourceTransition transition : graph.getTransitions()) {
            ResourceUsage to = transition.toUsage();
            Optional<ResourceUsage> from = transition.fromUsage();
            if (from.isPresent() && from.get() == to) {
                continue; // no usage change -> no layout change -> no barrier
            }

            int fromLayout = from.map(ImageLayoutMapper::layoutFor).orElse(VK_IMAGE_LAYOUT_UNDEFINED);
            int toLayout = ImageLayoutMapper.layoutFor(to);
            int srcStage = from.map(ImageLayoutMapper::stageMaskFor).orElse(VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT);
            int srcAccess = from.map(ImageLayoutMapper::accessMaskFor).orElse(0);
            int dstStage = ImageLayoutMapper.stageMaskFor(to);
            int dstAccess = ImageLayoutMapper.accessMaskFor(to);

            barriers.add(new PipelineBarrier(
                    transition.resource().name(), fromLayout, toLayout,
                    srcStage, dstStage, srcAccess, dstAccess,
                    transition.passName(), from.isEmpty()));
        }
        return Collections.unmodifiableList(barriers);
    }
}
