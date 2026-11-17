package com.vulkairis.rendering.sync;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;

/**
 * Tracks the current {@code VkImageLayout} of every resource so barriers are only emitted when a
 * layout actually changes, and so a resource is never assumed to be in a layout it is not.
 */
public final class ResourceStateTracker {

    private final Map<String, Integer> layouts = new LinkedHashMap<>();

    /** @return the tracked layout for {@code resourceName}, or UNDEFINED if untracked */
    public int getLayout(String resourceName) {
        return layouts.getOrDefault(resourceName, VK_IMAGE_LAYOUT_UNDEFINED);
    }

    /**
     * Applies a barrier, moving the resource to its destination layout.
     *
     * @param barrier the barrier to apply
     */
    public void apply(BarrierGenerator.PipelineBarrier barrier) {
        layouts.put(barrier.resourceName(), barrier.toLayout());
    }

    /** Applies a sequence of barriers in order. */
    public void applyAll(List<BarrierGenerator.PipelineBarrier> barriers) {
        for (BarrierGenerator.PipelineBarrier barrier : barriers) {
            apply(barrier);
        }
    }

    /** @return the number of tracked resources */
    public int trackedCount() {
        return layouts.size();
    }

    /** Resets all tracked layouts (e.g. on resize or shader reload). */
    public void reset() {
        layouts.clear();
    }
}
