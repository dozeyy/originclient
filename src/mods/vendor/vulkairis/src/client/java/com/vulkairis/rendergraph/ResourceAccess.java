package com.vulkairis.rendergraph;

import java.util.Objects;

/**
 * A single declaration that a pass uses a resource in a particular way. {@code PassNode} reads and
 * writes are lists of these, which is what makes the dependencies (and later the barriers)
 * derivable rather than hand-maintained.
 *
 * @param resource the resource being used
 * @param usage    how it is used
 */
public record ResourceAccess(RenderResource resource, ResourceUsage usage) {

    public ResourceAccess {
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(usage, "usage");
    }

    /** @return whether this access reads the resource */
    public boolean isRead() {
        return usage.isRead();
    }

    /** @return whether this access writes the resource */
    public boolean isWrite() {
        return usage.isWrite();
    }

    @Override
    public String toString() {
        return resource.name() + ":" + usage;
    }
}
