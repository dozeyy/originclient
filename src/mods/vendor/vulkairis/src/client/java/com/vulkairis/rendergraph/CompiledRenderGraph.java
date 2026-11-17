package com.vulkairis.rendergraph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An immutable, resolved render graph: passes in dependency order, their prerequisite/dependent
 * passes, per-resource lifetimes, and the usage transitions the synchronization layer must honour.
 *
 * <p>Produced by {@link RenderGraph#compile()}. This is the structure command recording walks:
 * bind pass 0's pipelines and attachments, record, move to pass 1, and so on.</p>
 */
public final class CompiledRenderGraph {

    private final List<PassNode> executionOrder;
    private final Map<String, Integer> indexByName;
    private final Map<String, List<String>> dependencies;
    private final Map<String, List<String>> dependents;
    private final Map<String, ResourceLifetime> lifetimes;
    private final List<ResourceTransition> transitions;
    private final Set<String> externalResources;

    CompiledRenderGraph(
            List<PassNode> executionOrder,
            Map<String, List<String>> dependencies,
            Map<String, List<String>> dependents,
            Map<String, ResourceLifetime> lifetimes,
            List<ResourceTransition> transitions,
            Set<String> externalResources) {
        this.executionOrder = Collections.unmodifiableList(new ArrayList<>(executionOrder));
        this.indexByName = new LinkedHashMap<>();
        for (int i = 0; i < executionOrder.size(); i++) {
            this.indexByName.put(executionOrder.get(i).name(), i);
        }
        this.dependencies = freeze(dependencies);
        this.dependents = freeze(dependents);
        this.lifetimes = Collections.unmodifiableMap(new LinkedHashMap<>(lifetimes));
        this.transitions = Collections.unmodifiableList(new ArrayList<>(transitions));
        this.externalResources = Collections.unmodifiableSet(externalResources);
    }

    private static Map<String, List<String>> freeze(Map<String, List<String>> source) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, Collections.unmodifiableList(new ArrayList<>(value))));
        return Collections.unmodifiableMap(copy);
    }

    /** @return the passes in a valid execution order */
    public List<PassNode> getExecutionOrder() {
        return executionOrder;
    }

    /** @return the number of passes */
    public int size() {
        return executionOrder.size();
    }

    /**
     * @param passName a pass name
     * @return that pass's index in the execution order, or {@code -1} if unknown
     */
    public int indexOf(String passName) {
        return indexByName.getOrDefault(passName, -1);
    }

    /**
     * @param passName a pass name
     * @return the names of passes that must execute before {@code passName}
     */
    public List<String> getDependencies(String passName) {
        return dependencies.getOrDefault(passName, Collections.emptyList());
    }

    /**
     * @param passName a pass name
     * @return the names of passes that depend on {@code passName}
     */
    public List<String> getDependents(String passName) {
        return dependents.getOrDefault(passName, Collections.emptyList());
    }

    /** @return every tracked resource's lifetime, keyed by resource name */
    public Map<String, ResourceLifetime> getLifetimes() {
        return lifetimes;
    }

    /** @return the recorded usage transitions, in execution order */
    public List<ResourceTransition> getTransitions() {
        return transitions;
    }

    /** @return the names of resources imported from outside the graph */
    public Set<String> getExternalResources() {
        return externalResources;
    }

    /**
     * Returns the transient resources whose last use is {@code passIndex}, i.e. their memory can be
     * recycled once that pass completes.
     *
     * @param passIndex a pass index in the execution order
     * @return the resources recyclable after {@code passIndex}
     */
    public List<RenderResource> getRecyclableResourcesAfter(int passIndex) {
        List<RenderResource> recyclable = new ArrayList<>();
        for (ResourceLifetime lifetime : lifetimes.values()) {
            if (lifetime.isRecyclable() && lifetime.recyclableAfterPassIndex() == passIndex) {
                recyclable.add(lifetime.resource());
            }
        }
        return Collections.unmodifiableList(recyclable);
    }

    /**
     * Renders a frame-graph dump: pass order with inputs/outputs, then resource lifetimes and usage
     * transitions. This is the text form of the "frame graph dump" diagnostic.
     *
     * @return the rendered dump
     */
    public String describe() {
        StringBuilder builder = new StringBuilder();
        builder.append("Render graph: ").append(executionOrder.size()).append(" pass(es), ")
                .append(lifetimes.size()).append(" resource(s)");
        for (int i = 0; i < executionOrder.size(); i++) {
            PassNode pass = executionOrder.get(i);
            builder.append("\n  #").append(i).append(' ').append(pass.describe());
        }
        builder.append("\n  resources:");
        for (ResourceLifetime lifetime : lifetimes.values()) {
            builder.append("\n    ").append(lifetime.resource().name())
                    .append(" live ").append(lifetime.firstPassIndex())
                    .append("..").append(lifetime.lastPassIndex())
                    .append(" producer=").append(lifetime.producerPass().orElse("<external>"))
                    .append(lifetime.external() ? " external" : "")
                    .append(lifetime.isRecyclable() ? " recyclable" : "");
        }
        builder.append("\n  transitions:");
        for (ResourceTransition transition : transitions) {
            builder.append("\n    ").append(transition);
        }
        return builder.toString();
    }

    @Override
    public String toString() {
        return "CompiledRenderGraph{" + executionOrder.size() + " passes, "
                + lifetimes.size() + " resources}";
    }
}
