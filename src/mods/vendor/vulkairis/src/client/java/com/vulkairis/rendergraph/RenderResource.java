package com.vulkairis.rendergraph;

import java.util.Objects;

/**
 * A named resource in the shader-pack render graph (scene color, depth, shadow map, a ping-pong
 * intermediate, an MRT attachment, a storage image, ...).
 *
 * <p>The graph treats this as a logical handle. The concrete Vulkan image/view/sampler is produced
 * by the resource layer once a pass contract is resolved.</p>
 *
 * @param name                unique resource name within the graph
 * @param kind                coarse resource classification
 * @param transientResource   whether the resource is frame-transient (memory may be recycled after
 *                            its last use) as opposed to persistent (e.g. the swapchain)
 */
public record RenderResource(String name, ResourceKind kind, boolean transientResource) {

    public RenderResource {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
        if (name.isBlank()) {
            throw new IllegalArgumentException("resource name must be non-blank");
        }
    }

    /** @return a persistent color image resource with the given name */
    public static RenderResource color(String name) {
        return new RenderResource(name, ResourceKind.COLOR_IMAGE, false);
    }

    /** @return a persistent depth image resource with the given name */
    public static RenderResource depth(String name) {
        return new RenderResource(name, ResourceKind.DEPTH_IMAGE, false);
    }

    /** @return a persistent storage image resource with the given name */
    public static RenderResource storageImage(String name) {
        return new RenderResource(name, ResourceKind.STORAGE_IMAGE, false);
    }

    /** @return a persistent buffer resource with the given name */
    public static RenderResource buffer(String name) {
        return new RenderResource(name, ResourceKind.BUFFER, false);
    }

    /**
     * @return a frame-transient color image resource with the given name
     * @param name the resource name
     */
    public static RenderResource transientColor(String name) {
        return new RenderResource(name, ResourceKind.COLOR_IMAGE, true);
    }

    /** @return the same resource marked frame-transient */
    public RenderResource asTransient() {
        return new RenderResource(name, kind, true);
    }

    @Override
    public String toString() {
        return name + "(" + kind + (transientResource ? ",transient" : "") + ")";
    }
}
