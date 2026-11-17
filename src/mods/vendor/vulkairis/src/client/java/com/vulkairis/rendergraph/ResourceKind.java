package com.vulkairis.rendergraph;

/**
 * Coarse classification of a render-graph resource, used to reject attachments that a Vulkan image
 * of that kind cannot legally serve.
 *
 * <p>This is deliberately abstract: it describes the role of the resource, not its concrete
 * {@code VkFormat} or allocation. Concrete image/sampler creation is handled by the resource layer,
 * not by the graph.</p>
 */
public enum ResourceKind {
    /** A color image, usable as a color attachment and/or sampled or storage image. */
    COLOR_IMAGE,

    /** A depth (optionally stencil) image, usable as a depth attachment and/or sampled image. */
    DEPTH_IMAGE,

    /** An image usable for random-access read/write in compute passes. */
    STORAGE_IMAGE,

    /** A buffer (storage/uniform/indirect), never an attachment. */
    BUFFER;

    /**
     * @param usage the usage being requested
     * @return whether a resource of this kind can legally be used with {@code usage}
     */
    public boolean supports(ResourceUsage usage) {
        return switch (this) {
            case COLOR_IMAGE -> usage != ResourceUsage.DEPTH_ATTACHMENT;
            case DEPTH_IMAGE -> usage == ResourceUsage.DEPTH_ATTACHMENT
                    || usage == ResourceUsage.SAMPLED
                    || usage == ResourceUsage.STORAGE
                    || usage == ResourceUsage.TRANSFER_SRC
                    || usage == ResourceUsage.TRANSFER_DST;
            case STORAGE_IMAGE -> usage == ResourceUsage.STORAGE
                    || usage == ResourceUsage.SAMPLED
                    || usage == ResourceUsage.TRANSFER_SRC
                    || usage == ResourceUsage.TRANSFER_DST;
            case BUFFER -> usage == ResourceUsage.STORAGE
                    || usage == ResourceUsage.TRANSFER_SRC
                    || usage == ResourceUsage.TRANSFER_DST;
        };
    }

    /** @return whether this kind is an image (as opposed to a buffer) */
    public boolean isImage() {
        return this != BUFFER;
    }
}
