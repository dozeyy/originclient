package com.vulkairis.iris;

import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
import net.irisshaders.iris.shaderpack.loading.ProgramGroup;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.programs.ComputeSource;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The complete inventory of executable programs declared by an Iris shader pack for a dimension.
 *
 * <p>The previous bridge inspected composite programs only, which is why shadows and geometry
 * passes never entered the render graph. This catalog discovers <em>every</em> program group Iris
 * exposes: setup/begin, shadow, shadow-composite, prepare, gbuffers, deferred, composite, final and
 * distant-horizons programs, plus every compute program. Programs are classified by their real
 * stages and sorted into execution order.</p>
 *
 * <p>Discovery is deliberately separate from readiness. A non-empty catalog proves that Vulkairis
 * knows the pipeline; it does <em>not</em> prove that resources, pipelines and pass contracts
 * exist. Callers must not treat a discovered catalog as "shaders working".</p>
 */
public final class ShaderPackProgramCatalog {

    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkairis/ProgramCatalog");

    /**
     * Execution order: by program group, then array index, then graphics before compute within an
     * equal key. {@link List#sort} is stable, so programs that tie on all keys keep discovery
     * order (Iris enum declaration order).
     */
    private static final Comparator<ShaderPackProgram> EXECUTION_ORDER =
            Comparator.comparingInt(ShaderPackProgram::executionOrder)
                    .thenComparingInt(ShaderPackProgram::arrayIndex)
                    .thenComparingInt(program -> program.kind().ordinal());

    private final List<ShaderPackProgram> programs;
    private final Map<ProgramGroup, Integer> countsByGroup;

    private ShaderPackProgramCatalog(Collection<ShaderPackProgram> discovered) {
        this.programs = orderByExecution(discovered);
        this.countsByGroup = buildCounts(this.programs);
    }

    /**
     * Discovers every program in {@code programSet}. Never throws for a malformed or partial pack:
     * individual failures are logged at debug level and the remaining programs are still returned.
     *
     * @param programSet the Iris program set for the active dimension
     * @return an immutable, execution-ordered catalog
     * @throws NullPointerException if {@code programSet} is null
     */
    public static ShaderPackProgramCatalog discover(ProgramSet programSet) {
        Objects.requireNonNull(programSet, "programSet");

        List<ShaderPackProgram> found = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        discoverGraphicsSingles(programSet, found, seen);
        discoverGraphicsArrays(programSet, found, seen);
        discoverCompute(programSet, found, seen);

        ShaderPackProgramCatalog catalog = new ShaderPackProgramCatalog(found);
        LOGGER.info("[Vulkairis] Shader-pack discovery complete: {} program(s) across {} group(s)",
                catalog.size(), catalog.getPresentGroups().size());
        return catalog;
    }

    /**
     * Rebuilds a catalog from an explicit program collection. Useful for tests and for
     * reconstructing a catalog from a persisted discovery.
     *
     * @param programs the programs to include
     * @return an immutable, execution-ordered catalog
     */
    public static ShaderPackProgramCatalog of(Collection<ShaderPackProgram> programs) {
        return new ShaderPackProgramCatalog(Objects.requireNonNull(programs, "programs"));
    }

    /**
     * Orders an arbitrary collection of programs into execution order. Exposed so callers and tests
     * can reason about ordering without a live Iris pack.
     *
     * @param programs programs to order; must not be null
     * @return an immutable, execution-ordered list
     */
    public static List<ShaderPackProgram> orderByExecution(Collection<ShaderPackProgram> programs) {
        Objects.requireNonNull(programs, "programs");
        List<ShaderPackProgram> sorted = new ArrayList<>(programs);
        sorted.sort(EXECUTION_ORDER);
        return Collections.unmodifiableList(sorted);
    }

    // ------------------------------------------------------------------
    // Discovery passes
    // ------------------------------------------------------------------

    private static void discoverGraphicsSingles(
            ProgramSet set, List<ShaderPackProgram> out, Set<String> seen) {
        for (ProgramId id : ProgramId.values()) {
            try {
                Optional<ProgramSource> maybe = set.get(id);
                if (maybe == null || maybe.isEmpty()) {
                    continue;
                }
                ProgramSource source = maybe.get();
                if (source == null || !source.isValid()) {
                    continue;
                }
                if (!seen.add("graphics:" + source.getName())) {
                    continue;
                }
                String fallback = id.getFallback()
                        .map(ProgramId::getSourceName)
                        .orElse(null);
                out.add(toGraphics(source, id.getGroup(), 0, 1, fallback));
            } catch (Throwable t) {
                LOGGER.debug("[Vulkairis] Skipped single program {}: {}", id, t.getMessage());
            }
        }
    }

    private static void discoverGraphicsArrays(
            ProgramSet set, List<ShaderPackProgram> out, Set<String> seen) {
        for (ProgramArrayId id : ProgramArrayId.values()) {
            try {
                ProgramSource[] sources = set.getComposite(id);
                if (sources == null || sources.length == 0) {
                    continue;
                }
                for (int i = 0; i < sources.length; i++) {
                    ProgramSource source = sources[i];
                    if (source == null || !source.isValid()) {
                        continue;
                    }
                    if (!seen.add("graphics:" + source.getName())) {
                        continue;
                    }
                    out.add(toGraphics(source, id.getGroup(), i, sources.length, null));
                }
            } catch (Throwable t) {
                LOGGER.debug("[Vulkairis] Skipped array program {}: {}", id, t.getMessage());
            }
        }
    }

    private static void discoverCompute(
            ProgramSet set, List<ShaderPackProgram> out, Set<String> seen) {
        // Frame setup compute programs (setup / begin).
        addCompute(out, seen, safeArray(set::getSetup), ProgramGroup.Setup);

        // Compute programs associated with a graphics array element (prepare/deferred/composite/...).
        for (ProgramArrayId id : ProgramArrayId.values()) {
            ComputeSource[][] byProgram = safeMatrix(set, id);
            if (byProgram == null) {
                continue;
            }
            for (int i = 0; i < byProgram.length; i++) {
                ComputeSource[] group = byProgram[i];
                if (group == null) {
                    continue;
                }
                addComputeAt(out, seen, group, id.getGroup(), i);
            }
        }

        // Shadow-composite and final compute programs.
        addCompute(out, seen, safeArray(set::getShadowCompute), ProgramGroup.ShadowComposite);
        addCompute(out, seen, safeArray(set::getFinalCompute), ProgramGroup.Final);
    }

    private static void addCompute(
            List<ShaderPackProgram> out, Set<String> seen, ComputeSource[] sources, ProgramGroup group) {
        if (sources == null) {
            return;
        }
        addComputeAt(out, seen, sources, group, 0);
    }

    private static void addComputeAt(
            List<ShaderPackProgram> out,
            Set<String> seen,
            ComputeSource[] sources,
            ProgramGroup group,
            int index) {
        int length = Math.max(1, sources.length);
        for (ComputeSource source : sources) {
            if (source == null || !source.isValid()) {
                continue;
            }
            if (!seen.add("compute:" + source.getName())) {
                continue;
            }
            out.add(new ShaderPackProgram.Compute(
                    source.getName(), group, index, length, source, safeWorkGroups(source)));
        }
    }

    private static ComputeSource[] safeArray(java.util.function.Supplier<ComputeSource[]> supplier) {
        try {
            return supplier.get();
        } catch (Throwable t) {
            LOGGER.debug("[Vulkairis] Skipped compute array: {}", t.getMessage());
            return null;
        }
    }

    private static ComputeSource[][] safeMatrix(ProgramSet set, ProgramArrayId id) {
        try {
            return set.getCompute(id);
        } catch (Throwable t) {
            LOGGER.debug("[Vulkairis] Skipped compute array {}: {}", id, t.getMessage());
            return null;
        }
    }

    private static org.joml.Vector3i safeWorkGroups(ComputeSource source) {
        try {
            return source.getWorkGroups();
        } catch (Throwable t) {
            return null;
        }
    }

    private static ShaderPackProgram.Graphics toGraphics(
            ProgramSource source, ProgramGroup group, int index, int length, String fallback) {
        return new ShaderPackProgram.Graphics(
                source.getName(),
                group,
                index,
                length,
                source,
                source.getVertexSource().isPresent(),
                source.getFragmentSource().isPresent(),
                source.getGeometrySource().isPresent(),
                source.getTessControlSource().isPresent(),
                source.getTessEvalSource().isPresent(),
                fallback);
    }

    // ------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------

    /** @return every discovered program, in execution order */
    public List<ShaderPackProgram> getPrograms() {
        return programs;
    }

    /** @return the number of discovered programs */
    public int size() {
        return programs.size();
    }

    /** @return whether no programs were discovered */
    public boolean isEmpty() {
        return programs.isEmpty();
    }

    /**
     * @param group the group to filter by
     * @return every discovered program in {@code group}, in execution order
     */
    public List<ShaderPackProgram> getPrograms(ProgramGroup group) {
        List<ShaderPackProgram> result = new ArrayList<>();
        for (ShaderPackProgram program : programs) {
            if (program.group() == group) {
                result.add(program);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /** @return only the raster graphics programs */
    public List<ShaderPackProgram> getGraphicsPrograms() {
        List<ShaderPackProgram> result = new ArrayList<>();
        for (ShaderPackProgram program : programs) {
            if (program.isGraphics()) {
                result.add(program);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /** @return only the compute programs */
    public List<ShaderPackProgram> getComputePrograms() {
        List<ShaderPackProgram> result = new ArrayList<>();
        for (ShaderPackProgram program : programs) {
            if (program.isCompute()) {
                result.add(program);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * @return only the distant-horizons programs ({@code Dh} group or {@code dh_} name), in
     *         execution order; empty when the pack ships none
     */
    public List<ShaderPackProgram> getDistantPrograms() {
        List<ShaderPackProgram> result = new ArrayList<>();
        for (ShaderPackProgram program : programs) {
            if (com.vulkairis.compatibility.DistantHorizonsSupport.isDistantProgram(program)) {
                result.add(program);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * @return whether the pack ships any distant-horizons program
     */
    public boolean hasDistantPrograms() {
        for (ShaderPackProgram program : programs) {
            if (com.vulkairis.compatibility.DistantHorizonsSupport.isDistantProgram(program)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param group the group to test
     * @return whether at least one program was discovered in {@code group}
     */
    public boolean hasGroup(ProgramGroup group) {
        return countsByGroup.getOrDefault(group, 0) > 0;
    }

    /**
     * @return true if the catalog contains shadow passes
     */
    public boolean hasShadowPass() {
        return hasGroup(ProgramGroup.Shadow) || hasGroup(ProgramGroup.ShadowComposite);
    }

    /**
     * @param name program name to search for
     * @return true if a program with this name was discovered
     */
    public boolean hasProgram(String name) {
        if (name == null) return false;
        for (ShaderPackProgram program : programs) {
            if (program.name().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the pack ships a world/post-processing execution path at all. A catalog that only
     * contains shadow or setup programs cannot produce a final image.
     *
     * @return {@code true} when a gbuffers, deferred, composite or final program exists
     */
    public boolean hasGraphicsExecutionPath() {
        return hasGroup(ProgramGroup.Gbuffers)
                || hasGroup(ProgramGroup.Deferred)
                || hasGroup(ProgramGroup.Composite)
                || hasGroup(ProgramGroup.Final);
    }

    /** @return the groups present in the pack, in execution order */
    public List<ProgramGroup> getPresentGroups() {
        List<ProgramGroup> present = new ArrayList<>();
        for (ShaderPackProgram program : programs) {
            if (!present.contains(program.group())) {
                present.add(program.group());
            }
        }
        return Collections.unmodifiableList(present);
    }

    /** @return an immutable count of programs per group, including groups with zero programs */
    public Map<ProgramGroup, Integer> getCountsByGroup() {
        return countsByGroup;
    }

    /**
     * A multi-line, log-friendly summary of the catalog grouped by program group.
     *
     * @return the rendered summary
     */
    public String describe() {
        StringBuilder builder = new StringBuilder();
        builder.append("Shader-pack program catalog: ").append(size()).append(" program(s)");
        if (isEmpty()) {
            return builder.append(" (empty)").toString();
        }
        Map<ProgramGroup, List<ShaderPackProgram>> byGroup = new LinkedHashMap<>();
        for (ShaderPackProgram program : programs) {
            byGroup.computeIfAbsent(program.group(), ignored -> new ArrayList<>()).add(program);
        }
        for (Map.Entry<ProgramGroup, List<ShaderPackProgram>> entry : byGroup.entrySet()) {
            builder.append("\n  ").append(entry.getKey())
                    .append(" (").append(entry.getValue().size()).append("):");
            for (ShaderPackProgram program : entry.getValue()) {
                builder.append("\n    - ").append(program.describe());
            }
        }
        return builder.toString();
    }

    private static Map<ProgramGroup, Integer> buildCounts(List<ShaderPackProgram> programs) {
        Map<ProgramGroup, Integer> counts = new EnumMap<>(ProgramGroup.class);
        for (ProgramGroup group : ProgramGroup.values()) {
            counts.put(group, 0);
        }
        for (ShaderPackProgram program : programs) {
            counts.merge(program.group(), 1, Integer::sum);
        }
        return Collections.unmodifiableMap(counts);
    }
}
