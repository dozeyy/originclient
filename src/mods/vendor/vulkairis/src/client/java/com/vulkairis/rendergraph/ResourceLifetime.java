package com.vulkairis.rendergraph;

import java.util.Objects;
import java.util.Optional;

/**
 * Where a resource is first and last used within one compiled frame graph.
 *
 * <p>Transient post-processing images (ping-pong buffers, bloom chain targets) only need to exist
 * between {@link #firstPassIndex()} and {@link #lastPassIndex()}. Once the last pass has consumed
 * them their backing memory can be recycled for another transient resource, which is how a graph
 * keeps a long composite chain cheap.</p>
 *
 * @param resource        the resource being tracked
 * @param firstPassIndex  index of the first pass that touches the resource
 * @param lastPassIndex   index of the last pass that touches the resource
 * @param producerPass    the first pass that writes the resource, empty for external-only resources
 * @param external        whether the resource was imported (not created by the graph)
 */
public record ResourceLifetime(
        RenderResource resource,
        int firstPassIndex,
        int lastPassIndex,
        Optional<String> producerPass,
        boolean external) {

    public ResourceLifetime {
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(producerPass, "producerPass");
        if (firstPassIndex < 0 || lastPassIndex < firstPassIndex) {
            throw new IllegalArgumentException("invalid lifetime range " + firstPassIndex + ".." + lastPassIndex
                    + " for " + resource.name());
        }
    }

    /**
     * @param passIndex a pass index in the compiled order
     * @return whether the resource is still needed at {@code passIndex}
     */
    public boolean isLiveAt(int passIndex) {
        return passIndex >= firstPassIndex && passIndex <= lastPassIndex;
    }

    /** @return the index of the last pass that needs this resource */
    public int recyclableAfterPassIndex() {
        return lastPassIndex;
    }

    /** @return whether this resource can be recycled once its last pass completes */
    public boolean isRecyclable() {
        return resource.transientResource() && !external;
    }
}
