package com.vulkairis.rendergraph;

/**
 * How a pass uses a resource. Read/write classification drives dependency ordering, and the
 * from/to usage pairs drive the image-layout transitions tracked by later synchronization work.
 */
public enum ResourceUsage {
    /** Written as a color attachment (framebuffer color output). */
    COLOR_ATTACHMENT,

    /** Written as the depth (and optional stencil) attachment. */
    DEPTH_ATTACHMENT,

    /** Read as a sampled image (texture fetch). */
    SAMPLED,

    /** Random-access read/write (storage image, storage buffer). */
    STORAGE,

    /** Read as a transfer/copy/blit source. */
    TRANSFER_SRC,

    /** Written as a transfer/copy/blit destination. */
    TRANSFER_DST,

    /** Handed to the presentation engine. */
    PRESENT;

    /** @return whether this usage writes the resource */
    public boolean isWrite() {
        return this == COLOR_ATTACHMENT
                || this == DEPTH_ATTACHMENT
                || this == STORAGE
                || this == TRANSFER_DST
                || this == PRESENT;
    }

    /** @return whether this usage reads the resource */
    public boolean isRead() {
        return this == SAMPLED || this == STORAGE || this == TRANSFER_SRC;
    }
}
