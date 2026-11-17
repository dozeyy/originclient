package com.vulkairis.rendering.exec;

import com.vulkairis.iris.ShaderPackProgram;
import com.vulkairis.iris.ShaderPackProgramCatalog;
import com.vulkairis.resource.RenderTargetRegistry;
import net.irisshaders.iris.shaderpack.loading.ProgramGroup;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Phase E: the explicit pack chain plan, solved once per pack-dimension profile and replayed
 * every frame without re-solving.
 *
 * <p>The chain runs begins &rarr; shadow-capture &rarr; prepares &rarr; feature redirects &rarr;
 * composites/final, or a present-substitute when the pack ships no {@code final} program. The
 * target plan (which render targets exist at which size/format), the sampler plan (which sampling
 * policy each target is read with) and the draw-buffer plan (which program reads which target and
 * writes which target, in order) are all frozen in this immutable value: a frame replays the
 * cached plan for its profile key instead of rediscovering programs, reallocating target
 * descriptors or re-running the ping-pong solver.
 *
 * <p>A pack without a {@code final} program gets a present-equivalent step that writes the game
 * main target. That step is always labeled {@code SUBSTITUTE} in diagnostics and status text: it
 * is never a real final and is never reported as one.
 */
public final class PackChainPlan {

    /** Pseudo-program used for the present-equivalent when a pack has no post chain at all. */
    public static final String PRESENT_SUBSTITUTE_PROGRAM = "present-substitute";

    /** Chain stages in execution order; the last stage names the terminal behavior. */
    public static final String STAGE_BEGINS = "begins";
    public static final String STAGE_SHADOW_CAPTURE = "shadow-capture";
    public static final String STAGE_PREPARES = "prepares";
    public static final String STAGE_FEATURE_REDIRECTS = "feature-redirects";
    public static final String STAGE_COMPOSITES_FINAL = "composites-final";
    public static final String STAGE_PRESENT_SUBSTITUTE = "present-substitute";

    /**
     * One planned render target: identity, backing format, dimensions, sampling policy and
     * whether its resolution is fixed (shadow maps) rather than following the window.
     */
    public record TargetSlot(String name, String format, int width, int height,
                             String sampler, boolean fixedResolution) {
        public TargetSlot {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(format, "format");
            Objects.requireNonNull(sampler, "sampler");
        }
    }

    /**
     * One planned draw: the program to run, the target it samples, the target it writes, whether
     * it is the terminal step, and whether the terminal step is a substitute rather than a real
     * pack {@code final} program.
     */
    public record DrawStep(String programName, String inputTarget, String outputTarget,
                           boolean finalStep, boolean substituteFinal) {
        public DrawStep {
            Objects.requireNonNull(programName, "programName");
            Objects.requireNonNull(inputTarget, "inputTarget");
            Objects.requireNonNull(outputTarget, "outputTarget");
        }
    }

    private static final ConcurrentHashMap<String, PackChainPlan> CACHE = new ConcurrentHashMap<>();
    private static final AtomicInteger SOLVES = new AtomicInteger(0);

    private final String profileKey;
    private final String packId;
    private final String dimension;
    private final int width;
    private final int height;
    private final int shadowResolution;
    private final long generation;
    private final boolean hasRealFinal;
    private final boolean usesSubstituteFinal;
    private final List<TargetSlot> targets;
    private final Map<String, String> samplers;
    private final List<String> postPrograms;
    private final List<DrawStep> drawSteps;
    private final List<String> stages;
    private final String digest;

    private PackChainPlan(String profileKey, String packId, String dimension,
                          int width, int height, int shadowResolution, long generation,
                          boolean hasRealFinal, boolean usesSubstituteFinal,
                          List<TargetSlot> targets, Map<String, String> samplers,
                          List<String> postPrograms, List<DrawStep> drawSteps,
                          List<String> stages, String digest) {
        this.profileKey = profileKey;
        this.packId = packId;
        this.dimension = dimension;
        this.width = width;
        this.height = height;
        this.shadowResolution = shadowResolution;
        this.generation = generation;
        this.hasRealFinal = hasRealFinal;
        this.usesSubstituteFinal = usesSubstituteFinal;
        this.targets = targets;
        this.samplers = samplers;
        this.postPrograms = postPrograms;
        this.drawSteps = drawSteps;
        this.stages = stages;
        this.digest = digest;
    }

    /**
     * Builds the cache/profile key for a pack-dimension configuration. Identical inputs produce
     * identical keys; any pack, dimension, resolution, generation or program-set change produces
     * a different key and therefore a fresh solve.
     */
    public static String profileKey(String packId, String dimension,
                                    int width, int height, int shadowResolution,
                                    long generation, ShaderPackProgramCatalog catalog) {
        return normalizePack(packId) + "|" + normalizeDimension(dimension)
                + "|" + Math.max(1, width) + "x" + Math.max(1, height)
                + "|shadow" + Math.max(1, shadowResolution)
                + "|gen" + generation
                + "|post:" + fingerprint(catalog);
    }

    /**
     * Solves the chain plan for the given inputs. Pure: no GPU objects, no Iris queries, no
     * caching. Every call solves; use {@link #planFor} for the cached replay path.
     */
    public static PackChainPlan resolve(ShaderPackProgramCatalog catalog,
                                        String packId, String dimension,
                                        int width, int height, int shadowResolution,
                                        long generation) {
        SOLVES.incrementAndGet();
        String pack = normalizePack(packId);
        String dim = normalizeDimension(dimension);
        int w = width <= 0 ? 1920 : width;
        int h = height <= 0 ? 1080 : height;
        int shadow = shadowResolution <= 0 ? 1024 : shadowResolution;

        List<String> postPrograms = postChain(catalog);
        boolean hasRealFinal = catalog != null && catalog.hasGroup(ProgramGroup.Final)
                && postPrograms.stream().anyMatch(name -> isFinalProgram(catalog, name));
        boolean substitute = !hasRealFinal;

        List<DrawStep> steps;
        if (postPrograms.isEmpty()) {
            steps = List.of(new DrawStep(PRESENT_SUBSTITUTE_PROGRAM,
                    RenderTargetRegistry.SCENE_COLOR, RenderTargetRegistry.FINAL,
                    true, true));
        } else {
            List<CompositeChainPlanner.CompositeStep> planned = CompositeChainPlanner.plan(
                    postPrograms,
                    RenderTargetRegistry.SCENE_COLOR,
                    RenderTargetRegistry.PING,
                    RenderTargetRegistry.PONG,
                    RenderTargetRegistry.FINAL);
            List<DrawStep> mapped = new ArrayList<>(planned.size());
            for (CompositeChainPlanner.CompositeStep step : planned) {
                mapped.add(new DrawStep(step.programName(),
                        step.input().name(), step.output().name(),
                        step.finalStep(), step.finalStep() && substitute));
            }
            steps = List.copyOf(mapped);
        }

        List<TargetSlot> targets = targetPlan(w, h, shadow);
        Map<String, String> samplers = new LinkedHashMap<>();
        for (TargetSlot slot : targets) {
            samplers.put(slot.name(), slot.sampler());
        }

        List<String> stages = List.of(STAGE_BEGINS, STAGE_SHADOW_CAPTURE, STAGE_PREPARES,
                STAGE_FEATURE_REDIRECTS,
                substitute ? STAGE_PRESENT_SUBSTITUTE : STAGE_COMPOSITES_FINAL);

        String key = profileKey(pack, dim, w, h, shadow, generation, catalog);
        String digest = digestOf(key, targets, samplers, steps);
        return new PackChainPlan(key, pack, dim, w, h, shadow, generation,
                hasRealFinal, substitute,
                List.copyOf(targets), Collections.unmodifiableMap(samplers),
                List.copyOf(postPrograms), steps, stages, digest);
    }

    /**
     * Returns the cached plan for the given inputs, solving once on a cache miss and replaying
     * the identical instance on every later frame with the same profile key.
     */
    public static PackChainPlan planFor(ShaderPackProgramCatalog catalog,
                                       String packId, String dimension,
                                       int width, int height, int shadowResolution,
                                       long generation) {
        String key = profileKey(packId, dimension, width, height, shadowResolution,
                generation, catalog);
        return CACHE.computeIfAbsent(key, ignored ->
                resolve(catalog, packId, dimension, width, height, shadowResolution, generation));
    }

    /** @return the cached plan for {@code profileKey}, or {@code null} on a cache miss */
    public static PackChainPlan cached(String profileKey) {
        if (profileKey == null) {
            return null;
        }
        return CACHE.get(profileKey);
    }

    /** @return how many plans were actually solved (cache misses), for replay tests */
    public static int solveCount() {
        return SOLVES.get();
    }

    /** Clears the plan cache; tests only. The solve counter is monotonic and is kept. */
    public static void clearForTests() {
        CACHE.clear();
    }

    public String profileKey() {
        return profileKey;
    }

    public String packId() {
        return packId;
    }

    public String dimension() {
        return dimension;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int shadowResolution() {
        return shadowResolution;
    }

    public long generation() {
        return generation;
    }

    /** @return whether the pack ships a real {@code final} program backing the terminal step */
    public boolean hasRealFinal() {
        return hasRealFinal;
    }

    /**
     * @return whether the terminal step is a present-equivalent substitute. A substitute writes
     * the game main target but is never a real final and must never be reported as one.
     */
    public boolean usesSubstituteFinal() {
        return usesSubstituteFinal;
    }

    /** @return the planned render targets in registration order */
    public List<TargetSlot> targets() {
        return targets;
    }

    /** @return target name to sampler policy name, in target order */
    public Map<String, String> samplers() {
        return samplers;
    }

    /** @return the ordered real post programs backing the draw plan (empty for substitute-only) */
    public List<String> postPrograms() {
        return postPrograms;
    }

    /** @return the ordered draw-buffer plan */
    public List<DrawStep> drawSteps() {
        return drawSteps;
    }

    /** @return the chain stages in execution order */
    public List<String> stages() {
        return stages;
    }

    /** @return the stable digest identifying this exact plan */
    public String digest() {
        return digest;
    }

    /** @return the game main target the terminal step writes */
    public static String mainTarget() {
        return RenderTargetRegistry.FINAL;
    }

    /**
     * One-line terminal label: either the real pack final or an explicitly labeled substitute.
     * The substitute wording never claims a real final.
     */
    public String finalLabel() {
        if (!usesSubstituteFinal) {
            String last = drawSteps.isEmpty()
                    ? "final" : drawSteps.get(drawSteps.size() - 1).programName();
            return "final: real pack final program '" + last
                    + "' writing game main target '" + mainTarget() + "'";
        }
        if (postPrograms.isEmpty()) {
            return "final: SUBSTITUTE present-equivalent '" + PRESENT_SUBSTITUTE_PROGRAM
                    + "' writing game main target '" + mainTarget()
                    + "' (pack ships no post chain; not a real final and never reported as one)";
        }
        String last = drawSteps.get(drawSteps.size() - 1).programName();
        return "final: SUBSTITUTE terminal step '" + last
                + "' writing game main target '" + mainTarget()
                + "' (pack ships no final program; not a real final and never reported as one)";
    }

    /** @return the plan rendered as honest status lines, substitute labeled when present */
    public List<String> statusLines() {
        List<String> lines = new ArrayList<>();
        lines.add("chain plan: " + profileKey + " digest=" + digest);
        lines.add("pack '" + packId + "' dimension '" + dimension + "' gen " + generation
                + " " + width + "x" + height + " shadow " + shadowResolution + "x" + shadowResolution);
        StringBuilder targetLine = new StringBuilder("targets (" + targets.size() + "):");
        for (TargetSlot slot : targets) {
            targetLine.append(' ').append(slot.name()).append(' ')
                    .append(slot.width()).append('x').append(slot.height())
                    .append(' ').append(slot.format())
                    .append(" sampler=").append(slot.sampler());
            if (slot.fixedResolution()) {
                targetLine.append(" [fixed]");
            }
            targetLine.append(';');
        }
        lines.add(targetLine.toString());
        if (drawSteps.isEmpty()) {
            lines.add("draw: (no steps)");
        } else {
            StringBuilder drawLine = new StringBuilder("draw (" + drawSteps.size() + "):");
            for (DrawStep step : drawSteps) {
                drawLine.append(' ').append(step.programName()).append(": ")
                        .append(step.inputTarget()).append(" -> ").append(step.outputTarget());
                if (step.finalStep()) {
                    drawLine.append(" [terminal]");
                }
                if (step.substituteFinal()) {
                    drawLine.append(" [SUBSTITUTE]");
                }
                drawLine.append(';');
            }
            lines.add(drawLine.toString());
        }
        lines.add(finalLabel());
        lines.add("stages: " + String.join(" -> ", stages));
        return List.copyOf(lines);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PackChainPlan that)) {
            return false;
        }
        return generation == that.generation
                && width == that.width
                && height == that.height
                && shadowResolution == that.shadowResolution
                && hasRealFinal == that.hasRealFinal
                && usesSubstituteFinal == that.usesSubstituteFinal
                && profileKey.equals(that.profileKey)
                && packId.equals(that.packId)
                && dimension.equals(that.dimension)
                && targets.equals(that.targets)
                && samplers.equals(that.samplers)
                && postPrograms.equals(that.postPrograms)
                && drawSteps.equals(that.drawSteps)
                && stages.equals(that.stages)
                && digest.equals(that.digest);
    }

    @Override
    public int hashCode() {
        return Objects.hash(profileKey, digest);
    }

    @Override
    public String toString() {
        return "PackChainPlan[" + profileKey + " digest=" + digest
                + (usesSubstituteFinal ? " SUBSTITUTE-final" : " real-final") + "]";
    }

    private static String normalizePack(String packId) {
        return packId == null || packId.isBlank() ? "unknown-pack" : packId;
    }

    private static String normalizeDimension(String dimension) {
        return dimension == null || dimension.isBlank() ? "unknown-dimension" : dimension;
    }

    private static List<String> postChain(ShaderPackProgramCatalog catalog) {
        List<String> post = new ArrayList<>();
        if (catalog == null) {
            return post;
        }
        for (ShaderPackProgram program : catalog.getPrograms()) {
            if (program.isGraphics()
                    && (program.group() == ProgramGroup.Composite
                            || program.group() == ProgramGroup.Final)) {
                post.add(program.name());
            }
        }
        return post;
    }

    private static boolean isFinalProgram(ShaderPackProgramCatalog catalog, String name) {
        for (ShaderPackProgram program : catalog.getPrograms(ProgramGroup.Final)) {
            if (program.name().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static List<TargetSlot> targetPlan(int width, int height, int shadow) {
        List<TargetSlot> slots = new ArrayList<>();
        try {
            RenderTargetRegistry registry = RenderTargetRegistry.standard(width, height, shadow);
            for (var resource : registry.all()) {
                String name = resource.getSpec().resource().name();
                boolean fixed = RenderTargetRegistry.SHADOW_DEPTH.equals(name)
                        || RenderTargetRegistry.SHADOW_COLOR.equals(name);
                slots.add(new TargetSlot(name,
                        String.valueOf(resource.getFormat()),
                        resource.getSpec().width(), resource.getSpec().height(),
                        String.valueOf(resource.getSpec().sampler()), fixed));
            }
            if (!slots.isEmpty()) {
                return List.copyOf(slots);
            }
        } catch (Throwable ignored) {
        }
        slots.clear();
        slots.add(new TargetSlot(RenderTargetRegistry.SCENE_COLOR, "RGBA16F",
                width, height, "LINEAR_CLAMP", false));
        slots.add(new TargetSlot(RenderTargetRegistry.SCENE_DEPTH, "DEPTH32F",
                width, height, "NEAREST_CLAMP", false));
        slots.add(new TargetSlot(RenderTargetRegistry.SHADOW_DEPTH, "DEPTH32F",
                shadow, shadow, "SHADOW_COMPARE", true));
        slots.add(new TargetSlot(RenderTargetRegistry.SHADOW_COLOR, "RGBA8",
                shadow, shadow, "NEAREST_CLAMP", true));
        slots.add(new TargetSlot(RenderTargetRegistry.PING, "RGBA16F",
                width, height, "LINEAR_CLAMP", false));
        slots.add(new TargetSlot(RenderTargetRegistry.PONG, "RGBA16F",
                width, height, "LINEAR_CLAMP", false));
        slots.add(new TargetSlot(RenderTargetRegistry.FINAL, "RGBA8",
                width, height, "NEAREST_CLAMP", false));
        return List.copyOf(slots);
    }

    private static String fingerprint(ShaderPackProgramCatalog catalog) {
        if (catalog == null || catalog.isEmpty()) {
            return "empty";
        }
        StringBuilder builder = new StringBuilder();
        for (ShaderPackProgram program : catalog.getPrograms()) {
            builder.append(program.name()).append('#').append(program.group())
                    .append('#').append(program.kind())
                    .append('#').append(program.arrayIndex())
                    .append('x').append(program.arrayLength()).append(',');
        }
        return Integer.toHexString(builder.toString().hashCode());
    }

    private static String digestOf(String key, List<TargetSlot> targets,
                                   Map<String, String> samplers, List<DrawStep> steps) {
        StringBuilder canonical = new StringBuilder(key).append('|');
        for (TargetSlot slot : targets) {
            canonical.append(slot.name()).append('=').append(slot.format())
                    .append('@').append(slot.width()).append('x').append(slot.height())
                    .append(':').append(slot.sampler())
                    .append(slot.fixedResolution() ? "[fixed]" : "").append(';');
        }
        canonical.append('|');
        for (Map.Entry<String, String> sampler : samplers.entrySet()) {
            canonical.append(sampler.getKey()).append("->").append(sampler.getValue()).append(';');
        }
        canonical.append('|');
        for (DrawStep step : steps) {
            canonical.append(step.programName()).append(':')
                    .append(step.inputTarget()).append("->").append(step.outputTarget())
                    .append(step.finalStep() ? "[t]" : "")
                    .append(step.substituteFinal() ? "[s]" : "").append(';');
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 6 && i < hash.length; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (Exception e) {
            return Integer.toHexString(canonical.toString().hashCode());
        }
    }
}
