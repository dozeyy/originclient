package com.vulkairis.rendergraph;

import java.util.Objects;
import java.util.Optional;

/**
 * A recorded change of a resource's usage at a specific pass, in execution order.
 *
 * <p>This is the graph-level half of synchronization: the graph knows a resource moved from (say)
 * {@link ResourceUsage#COLOR_ATTACHMENT} at one pass to {@link ResourceUsage#SAMPLED} at a later
 * pass. The synchronization layer turns each transition into a pipeline barrier and image-layout
 * change.</p>
 *
 * @param resource  the resource whose usage changes
 * @param fromUsage the previous usage, empty for the resource's first use
 * @param toUsage   the usage at {@code passName}
 * @param passName  the pass at which the new usage applies
 */
public record ResourceTransition(
        RenderResource resource,
        Optional<ResourceUsage> fromUsage,
        ResourceUsage toUsage,
        String passName) {

    public ResourceTransition {
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(fromUsage, "fromUsage");
        Objects.requireNonNull(toUsage, "toUsage");
        Objects.requireNonNull(passName, "passName");
    }

    /** @return whether this is the resource's first use in the frame */
    public boolean isInitial() {
        return fromUsage.isEmpty();
    }

    /** @return whether this transition actually changes usage (and therefore needs a barrier) */
    public boolean isUsageChange() {
        return fromUsage.isPresent() && fromUsage.get() != toUsage;
    }

    @Override
    public String toString() {
        return resource.name() + ": " + fromUsage.map(Enum::name).orElse("<initial>")
                + " -> " + toUsage + " @" + passName;
    }
}
