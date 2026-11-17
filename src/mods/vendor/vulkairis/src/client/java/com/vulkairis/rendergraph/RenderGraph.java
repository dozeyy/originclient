package com.vulkairis.rendergraph;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * A declarative graph of shader-pack passes that compiles into a dependency-ordered execution list.
 *
 * <p>Callers declare passes and the resources they read/write (including depth and MRT outputs).
 * {@link #compile()} derives the read-after-write, write-after-write and write-after-read ordering
 * edges, topologically sorts the passes into a real execution order, rejects cycles with a concrete
 * diagnostic, and records per-resource lifetimes and usage transitions.</p>
 *
 * <p>Resources never written by a pass must be declared with {@link #addExternalResource} (the
 * Minecraft scene color, the swapchain, GUI targets) so that "read but never produced" is caught as
 * a real error instead of silently ordering against nothing.</p>
 *
 * <p>The graph is pure data: it creates no Vulkan objects. That is what lets the whole pass order be
 * verified before any GPU work exists.</p>
 */
public final class RenderGraph {

    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkairis/RenderGraph");

    private final Map<String, PassNode> passes = new LinkedHashMap<>();
    private final Map<String, RenderResource> resources = new LinkedHashMap<>();
    private final Set<String> externalResources = new LinkedHashSet<>();

    /**
     * Declares a resource that the graph reads or writes but does not create (scene color, swapchain,
     * GUI target, ...). External resources are exempt from the "must have a producer" rule.
     *
     * @param resource the imported resource
     * @return this graph, for chaining
     */
    public RenderGraph addExternalResource(RenderResource resource) {
        Objects.requireNonNull(resource, "resource");
        externalResources.add(resource.name());
        register(resource);
        return this;
    }

    /**
     * Adds a pass to the graph.
     *
     * @param pass the pass to add
     * @return this graph, for chaining
     * @throws RenderGraphException if a pass with the same name already exists
     */
    public RenderGraph addPass(PassNode pass) {
        Objects.requireNonNull(pass, "pass");
        if (passes.containsKey(pass.name())) {
            throw new RenderGraphException("duplicate pass name '" + pass.name() + "'");
        }
        passes.put(pass.name(), pass);
        for (ResourceAccess access : pass.reads()) {
            register(access.resource());
        }
        for (ResourceAccess access : pass.writes()) {
            register(access.resource());
        }
        return this;
    }

    private void register(RenderResource resource) {
        RenderResource existing = resources.putIfAbsent(resource.name(), resource);
        if (existing != null && existing.kind() != resource.kind()) {
            throw new RenderGraphException("resource '" + resource.name() + "' declared with conflicting kinds "
                    + existing.kind() + " and " + resource.kind());
        }
    }

    /** @return the number of passes currently in the graph */
    public int passCount() {
        return passes.size();
    }

    /**
     * Compiles the declared graph into an execution-ordered, immutable result.
     *
     * @return the compiled graph
     * @throws RenderGraphException if a resource has no producer and is not external, or the graph
     *                              contains a dependency cycle
     */
    public CompiledRenderGraph compile() {
        List<PassNode> passList = new ArrayList<>(passes.values());
        int n = passList.size();
        if (n == 0) {
            throw new RenderGraphException("cannot compile an empty render graph");
        }

        Map<String, List<Access>> accessesByResource = collectAccesses(passList);
        validateProducers(accessesByResource);

        List<Set<Integer>> adjacency = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            adjacency.add(new LinkedHashSet<>());
        }
        buildEdges(accessesByResource, adjacency);
        applyRunAfter(passList, adjacency);

        List<Integer> order = topologicalSort(adjacency);
        if (order == null) {
            List<Integer> cycle = findCycle(adjacency, n);
            throw new RenderGraphException("render graph contains a dependency cycle: " + cyclePath(cycle, passList));
        }

        List<PassNode> executionOrder = new ArrayList<>(n);
        for (int index : order) {
            executionOrder.add(passList.get(index));
        }

        Map<String, List<String>> dependencies = new LinkedHashMap<>();
        Map<String, List<String>> dependents = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            String name = passList.get(i).name();
            dependencies.put(name, new ArrayList<>());
            dependents.put(name, new ArrayList<>());
        }
        for (int from = 0; from < n; from++) {
            for (int to : adjacency.get(from)) {
                dependencies.get(passList.get(to).name()).add(passList.get(from).name());
                dependents.get(passList.get(from).name()).add(passList.get(to).name());
            }
        }

        Map<Integer, Integer> positionInOrder = new HashMap<>();
        for (int i = 0; i < order.size(); i++) {
            positionInOrder.put(order.get(i), i);
        }
        Map<String, ResourceLifetime> lifetimes = buildLifetimes(accessesByResource, positionInOrder, passList);
        List<ResourceTransition> transitions = buildTransitions(accessesByResource, positionInOrder, passList);

        CompiledRenderGraph compiled = new CompiledRenderGraph(
                executionOrder, dependencies, dependents, lifetimes, transitions, externalResources);
        LOGGER.debug("[Vulkairis/RenderGraph] Compiled {} pass(es):\n{}", n, compiled.describe());
        return compiled;
    }

    private Map<String, List<Access>> collectAccesses(List<PassNode> passList) {
        Map<String, List<Access>> byResource = new LinkedHashMap<>();
        for (int i = 0; i < passList.size(); i++) {
            PassNode pass = passList.get(i);
            for (ResourceAccess access : pass.reads()) {
                byResource.computeIfAbsent(access.resource().name(), ignored -> new ArrayList<>())
                        .add(new Access(i, access));
            }
            for (ResourceAccess access : pass.writes()) {
                byResource.computeIfAbsent(access.resource().name(), ignored -> new ArrayList<>())
                        .add(new Access(i, access));
            }
        }
        return byResource;
    }

    private void validateProducers(Map<String, List<Access>> accessesByResource) {
        for (Map.Entry<String, List<Access>> entry : accessesByResource.entrySet()) {
            String name = entry.getKey();
            if (externalResources.contains(name)) {
                continue;
            }
            boolean hasProducer = false;
            for (Access access : entry.getValue()) {
                if (isWriter(access.usage())) {
                    hasProducer = true;
                    break;
                }
            }
            if (!hasProducer) {
                throw new RenderGraphException("resource '" + name
                        + "' is read by a pass but never written and not imported as an external resource");
            }
        }
    }

    private void buildEdges(
            Map<String, List<Access>> accessesByResource,
            List<Set<Integer>> adjacency) {
        for (Map.Entry<String, List<Access>> entry : accessesByResource.entrySet()) {
            String resourceName = entry.getKey();
            List<Access> accesses = entry.getValue();
            // Consider each unordered pair exactly once, choosing the correct edge direction from
            // the read/write roles plus declaration order. A read-after-write edge always runs
            // writer-before-reader (the reader consumes the produced value, wherever the passes
            // were declared). A write-after-write edge runs earlier-before-later so the last
            // declared writer wins deterministically. A write-after-read (anti-dependency) edge
            // runs reader-before-later-writer only when the read observes a real value: an
            // external import or an earlier writer. That is the ping-pong composite reuse case
            // (composite1/composite3 shape): pass N must finish sampling the intermediate before
            // pass N+1 overwrites it. Pointing that edge writer-before-reader instead fabricates
            // a cycle (composite1 -> composite3 -> composite1) out of a linear chain. When the
            // read has no earlier producer it can only be satisfied by the later writer, so the
            // edge runs writer-before-reader instead; paired with the matching read-after-write
            // edge that forms a true cycle, which compile() rejects with a path diagnostic.
            for (int i = 0; i < accesses.size(); i++) {
                for (int j = i + 1; j < accesses.size(); j++) {
                    Access a = accesses.get(i);
                    Access b = accesses.get(j);
                    if (a.passIndex() == b.passIndex()) {
                        continue;
                    }
                    boolean aWrite = isWriter(a.usage());
                    boolean aRead = isReader(a.usage());
                    boolean bWrite = isWriter(b.usage());
                    boolean bRead = isReader(b.usage());
                    if (aWrite && bRead) {
                        adjacency.get(a.passIndex()).add(b.passIndex()); // read-after-write
                    } else if (aRead && bWrite) {
                        if (externalResources.contains(resourceName) || hasEarlierWriter(accesses, i)) {
                            adjacency.get(a.passIndex()).add(b.passIndex()); // write-after-read
                        } else {
                            adjacency.get(b.passIndex()).add(a.passIndex()); // unbacked read: writer must run first
                        }
                    } else if (aWrite && bWrite) {
                        adjacency.get(a.passIndex()).add(b.passIndex()); // write-after-write
                    }
                    // reader/reader pairs impose no ordering.
                }
            }
        }
    }

    /**
     * Whether any access before {@code endExclusive} in declaration order produces the resource.
     * Backs the write-after-read rule: a read that already observes a produced value must stay
     * ordered before a later overwrite instead of fabricating a reverse edge.
     */
    private static boolean hasEarlierWriter(List<Access> accesses, int endExclusive) {
        for (int k = 0; k < endExclusive; k++) {
            if (isWriter(accesses.get(k).usage())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Applies explicit ordering constraints as extra edges. Unknown targets fail the compile
     * loudly instead of ordering against nothing.
     */
    private void applyRunAfter(List<PassNode> passList, List<Set<Integer>> adjacency) {
        Map<String, Integer> indexByName = new HashMap<>();
        for (int i = 0; i < passList.size(); i++) {
            indexByName.put(passList.get(i).name(), i);
        }
        for (int i = 0; i < passList.size(); i++) {
            for (String after : passList.get(i).runAfter()) {
                Integer from = indexByName.get(after);
                if (from == null) {
                    throw new RenderGraphException("pass '" + passList.get(i).name()
                            + "' must run after unknown pass '" + after + "'");
                }
                adjacency.get(from).add(i);
            }
        }
    }

    /**
     * For dependency purposes a storage access counts as a producer: it is ordered after earlier
     * producers and before later consumers, which avoids artificial read/write cycles on a
     * read-write image while still ordering it correctly.
     */
    private static boolean isWriter(ResourceUsage usage) {
        return usage.isWrite();
    }

    private static boolean isReader(ResourceUsage usage) {
        return usage == ResourceUsage.SAMPLED || usage == ResourceUsage.TRANSFER_SRC;
    }

    private List<Integer> topologicalSort(List<Set<Integer>> adjacency) {
        int n = adjacency.size();
        int[] indegree = new int[n];
        for (Set<Integer> successors : adjacency) {
            for (int to : successors) {
                indegree[to]++;
            }
        }
        PriorityQueue<Integer> ready = new PriorityQueue<>();
        for (int i = 0; i < n; i++) {
            if (indegree[i] == 0) {
                ready.add(i);
            }
        }
        List<Integer> order = new ArrayList<>(n);
        while (!ready.isEmpty()) {
            int from = ready.poll();
            order.add(from);
            for (int to : adjacency.get(from)) {
                if (--indegree[to] == 0) {
                    ready.add(to);
                }
            }
        }
        return order.size() == n ? order : null;
    }

    private List<Integer> findCycle(List<Set<Integer>> adjacency, int n) {
        int[] color = new int[n]; // 0 white, 1 gray, 2 black
        int[] parent = new int[n];
        Arrays.fill(parent, -1);
        for (int start = 0; start < n; start++) {
            if (color[start] == 0) {
                List<Integer> found = dfsCycle(start, adjacency, color, parent);
                if (!found.isEmpty()) {
                    return found;
                }
            }
        }
        return Collections.emptyList();
    }

    private List<Integer> dfsCycle(int node, List<Set<Integer>> adjacency, int[] color, int[] parent) {
        color[node] = 1;
        for (int next : adjacency.get(node)) {
            if (color[next] == 1) {
                LinkedList<Integer> cycle = new LinkedList<>();
                cycle.addFirst(next);
                int cursor = node;
                while (cursor != -1 && cursor != next) {
                    cycle.addFirst(cursor);
                    cursor = parent[cursor];
                }
                cycle.addFirst(next);
                return cycle;
            }
            if (color[next] == 0) {
                parent[next] = node;
                List<Integer> deeper = dfsCycle(next, adjacency, color, parent);
                if (!deeper.isEmpty()) {
                    return deeper;
                }
            }
        }
        color[node] = 2;
        return Collections.emptyList();
    }

    private static String cyclePath(List<Integer> cycle, List<PassNode> passList) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < cycle.size(); i++) {
            if (i > 0) {
                builder.append(" -> ");
            }
            int index = cycle.get(i);
            builder.append(index >= 0 && index < passList.size()
                    ? passList.get(index).name()
                    : "?" + index);
        }
        return builder.toString();
    }

    private Map<String, ResourceLifetime> buildLifetimes(
            Map<String, List<Access>> accessesByResource,
            Map<Integer, Integer> positionInOrder,
            List<PassNode> passList) {
        Map<String, ResourceLifetime> lifetimes = new LinkedHashMap<>();
        for (Map.Entry<String, List<Access>> entry : accessesByResource.entrySet()) {
            String name = entry.getKey();
            RenderResource resource = resources.get(name);
            int first = Integer.MAX_VALUE;
            int last = Integer.MIN_VALUE;
            String producer = null;
            int producerPosition = Integer.MAX_VALUE;
            for (Access access : entry.getValue()) {
                int position = positionInOrder.getOrDefault(access.passIndex(), 0);
                first = Math.min(first, position);
                last = Math.max(last, position);
                if (isWriter(access.usage()) && position < producerPosition) {
                    producerPosition = position;
                    producer = passList.get(access.passIndex()).name();
                }
            }
            if (first == Integer.MAX_VALUE) {
                first = 0;
            }
            if (last == Integer.MIN_VALUE) {
                last = 0;
            }
            lifetimes.put(name, new ResourceLifetime(
                    resource,
                    first,
                    last,
                    Optional.ofNullable(producer),
                    externalResources.contains(name)));
        }
        return lifetimes;
    }

    private List<ResourceTransition> buildTransitions(
            Map<String, List<Access>> accessesByResource,
            Map<Integer, Integer> positionInOrder,
            List<PassNode> passList) {
        List<ResourceTransition> all = new ArrayList<>();
        for (Map.Entry<String, List<Access>> entry : accessesByResource.entrySet()) {
            RenderResource resource = resources.get(entry.getKey());

            // Group accesses by pass, order the passes by their position in the execution order.
            Map<Integer, List<ResourceUsage>> usagesByPass = new LinkedHashMap<>();
            for (Access access : entry.getValue()) {
                usagesByPass.computeIfAbsent(access.passIndex(), ignored -> new ArrayList<>())
                        .add(access.usage());
            }
            List<Integer> orderedPasses = new ArrayList<>(usagesByPass.keySet());
            orderedPasses.sort((a, b) -> Integer.compare(
                    positionInOrder.getOrDefault(a, 0), positionInOrder.getOrDefault(b, 0)));

            ResourceUsage previous = null;
            for (int passIndex : orderedPasses) {
                ResourceUsage representative = representativeUsage(usagesByPass.get(passIndex));
                all.add(new ResourceTransition(
                        resource,
                        Optional.ofNullable(previous),
                        representative,
                        passList.get(passIndex).name()));
                previous = representative;
            }
        }
        return all;
    }

    private static ResourceUsage representativeUsage(List<ResourceUsage> usages) {
        for (ResourceUsage usage : usages) {
            if (usage.isWrite()) {
                return usage;
            }
        }
        for (ResourceUsage usage : usages) {
            if (usage.isRead()) {
                return usage;
            }
        }
        return usages.get(0);
    }

    private record Access(int passIndex, ResourceAccess access) {
        ResourceUsage usage() {
            return access.usage();
        }
    }
}
