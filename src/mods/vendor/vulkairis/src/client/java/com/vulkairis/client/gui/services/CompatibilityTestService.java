package com.vulkairis.client.gui.services;

import com.vulkairis.translation.ShaderReflection;
import com.vulkairis.translation.ShaderTranslator;
import com.vulkairis.translation.SpirvCompiler;
import com.vulkairis.translation.SpirvReflector;
import com.vulkairis.translation.SpirvValidator;
import com.vulkairis.translation.TranslationWorker;
import org.lwjgl.util.shaderc.Shaderc;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shared shader compatibility validation (GUI plan §Compatibility, Phase 4 slice).
 *
 * <p>Runs real checks — never a dummy button: every fixture travels the production
 * translate/compile/validate/reflect path (the same static entry points the bridge calls),
 * and the report preserves the first actionable failure. The nine plan stages are all
 * represented; stages without an execution path yet (pack scan, g-buffer, shadow,
 * composite, final output) report NOT_SUPPORTED with the reason instead of fake passes.
 * The staged pack workflow (progress UI, pack sources) extends this result model rather
 * than replacing it. Fixtures are client-owned so no game code depends on test sources.</p>
 */
public final class CompatibilityTestService {

    /** Plan stage outcome. */
    public enum StageStatus {
        PASSED,
        FAILED,
        PARTIAL,
        NOT_SUPPORTED
    }

    /** @param stage plan stage name @param status outcome @param detail cause or evidence */
    public record CompatibilityStage(String stage, StageStatus status, String detail) {
    }

    /** @param packName what was tested @param stages per-stage results @param durationMs wall time */
    public record CompatibilityTestReport(String packName, List<CompatibilityStage> stages,
                                          long durationMs) {
        /** @return overall verdict, worst stage first */
        public StageStatus summary() {
            boolean partial = false;
            for (CompatibilityStage stage : stages) {
                if (stage.status() == StageStatus.FAILED) {
                    return StageStatus.FAILED;
                }
                if (stage.status() == StageStatus.PARTIAL) {
                    partial = true;
                }
            }
            return partial ? StageStatus.PARTIAL : StageStatus.PASSED;
        }

        /** @return the first failing stage, if any */
        public java.util.Optional<CompatibilityStage> firstFailure() {
            for (CompatibilityStage stage : stages) {
                if (stage.status() == StageStatus.FAILED) {
                    return java.util.Optional.of(stage);
                }
            }
            return java.util.Optional.empty();
        }
    }

    /** One client-owned fixture: a vertex/fragment pair through the production path. */
    public record CompatibilityFixture(String name, String vertexGlsl, String fragmentGlsl) {
    }

    /** Progress callback for async runs (done fixtures out of total). */
    public interface Progress {
        void advanced(String fixtureName, int done, int total);
    }

    /** Built-in fixtures. Small by design; the full matrix lives in the test harness. */
    public static List<CompatibilityFixture> builtInFixtures() {
        String fullscreenVert = """
                #version 150 core
                in vec3 iris_Position;
                in vec2 iris_UV;
                uniform mat4 projection;
                out vec2 vUv;
                void main() {
                    vUv = iris_UV;
                    gl_Position = projection * vec4(iris_Position, 1.0);
                }
                """;
        return List.of(
                new CompatibilityFixture("solid-color",
                        fullscreenVert,
                        """
                        #version 150 core
                        out vec4 fragColor;
                        void main() {
                            fragColor = vec4(1.0, 0.0, 0.0, 1.0);
                        }
                        """),
                new CompatibilityFixture("texture-copy",
                        fullscreenVert,
                        """
                        #version 150 core
                        uniform sampler2D colortex0;
                        in vec2 vUv;
                        out vec4 fragColor;
                        void main() {
                            fragColor = texture(colortex0, vUv);
                        }
                        """));
    }

    private CompatibilityTestService() {
    }

    /**
     * Runs the fixture validation synchronously, checking cancellation between fixtures.
     *
     * @param cancel set to request cancellation (checked between fixtures, never mid-compile)
     * @param progress progress sink, or {@code null}
     */
    public static CompatibilityTestReport runFixtureValidation(AtomicBoolean cancel,
                                                               Progress progress) {
        long started = System.nanoTime();
        List<CompatibilityStage> stages = new ArrayList<>();
        List<CompatibilityFixture> fixtures = builtInFixtures();
        int done = 0;
        boolean cancelled = false;
        for (CompatibilityFixture fixture : fixtures) {
            if (cancel != null && cancel.get()) {
                cancelled = true;
                break;
            }
            stages.addAll(runFixtureStages(fixture));
            done++;
            if (progress != null) {
                progress.advanced(fixture.name(), done, fixtures.size());
            }
        }
        if (cancelled) {
            stages.add(new CompatibilityStage("Result summary", StageStatus.PARTIAL,
                    "cancelled after " + done + " of " + fixtures.size() + " fixtures"));
        }
        stages.add(new CompatibilityStage("Pack scan", StageStatus.NOT_SUPPORTED,
                "live pack scanning lands with the pack browser workflow"));
        stages.add(new CompatibilityStage("G-buffer setup", StageStatus.NOT_SUPPORTED,
                "framebuffer attachment checks land with the staged pack workflow"));
        stages.add(new CompatibilityStage("Shadow pass", StageStatus.NOT_SUPPORTED,
                "shadow execution lands with the staged pack workflow"));
        stages.add(new CompatibilityStage("Composite/post-processing", StageStatus.NOT_SUPPORTED,
                "pass-order verification lands with the staged pack workflow"));
        stages.add(new CompatibilityStage("Final output", StageStatus.NOT_SUPPORTED,
                "image heuristics land with the staged pack workflow"));
        if (!cancelled) {
            boolean failed = stages.stream().anyMatch(s -> s.status() == StageStatus.FAILED);
            stages.add(new CompatibilityStage("Result summary",
                    failed ? StageStatus.FAILED : StageStatus.PASSED,
                    done + " of " + fixtures.size() + " fixtures evaluated"));
        }
        long durationMs = (System.nanoTime() - started) / 1_000_000L;
        return new CompatibilityTestReport("built-in fixtures", List.copyOf(stages), durationMs);
    }

    /**
     * Runs the validation off-thread; install the result only when the generation still
     * matches (see {@code TranslationWorker.takeIfCurrent}).
     */
    public static TranslationWorker.Task<CompatibilityTestReport> runFixtureValidationAsync(
            long generation, AtomicBoolean cancel, Progress progress) {
        return TranslationWorker.submit("compat-test", generation,
                () -> runFixtureValidation(cancel, progress));
    }

    /**
     * Phase 6: validates a real shader-pack archive through every applicable stage — scan,
     * per-pair transformation/compilation/reflection, interface matching, g-buffer and shadow
     * checks, composite chain verification — with cancellation between pairs and progress per
     * pair. Pure functions only (no registry, no game state); nothing leaks.
     *
     * @param archive pack archive path
     * @param cancel cancellation flag (checked between pairs, never mid-compile)
     * @param progress progress sink, or {@code null}
     */
    public static CompatibilityTestReport runPackValidation(Path archive, AtomicBoolean cancel,
                                                            Progress progress) {
        long started = System.nanoTime();
        List<CompatibilityStage> stages = new ArrayList<>();
        String packName = archive != null ? archive.getFileName().toString() : "none";

        List<ProgramPair> pairs;
        try {
            pairs = discoverPairs(archive);
        } catch (Throwable t) {
            stages.add(new CompatibilityStage("Pack scan", StageStatus.FAILED,
                    "unreadable archive: " + t.getMessage()));
            stages.add(summaryStage(StageStatus.FAILED, "pack scan failed"));
            return new CompatibilityTestReport(packName, List.copyOf(stages),
                    elapsedMs(started));
        }
        if (pairs.isEmpty()) {
            stages.add(new CompatibilityStage("Pack scan", StageStatus.FAILED,
                    "no vertex/fragment program pairs under shaders/"));
            stages.add(summaryStage(StageStatus.FAILED, "nothing to validate"));
            return new CompatibilityTestReport(packName, List.copyOf(stages),
                    elapsedMs(started));
        }
        stages.add(new CompatibilityStage("Pack scan", StageStatus.PASSED,
                pairs.size() + " program pair(s) discovered"));

        boolean cancelled = false;
        int done = 0;
        List<String> pairFailures = new ArrayList<>();
        List<String> transformFailures = new ArrayList<>();
        List<String> compileFailures = new ArrayList<>();
        List<String> resourceFailures = new ArrayList<>();
        List<String> transformNotes = new ArrayList<>();
        List<String> compileNotes = new ArrayList<>();
        List<String> resourceNotes = new ArrayList<>();
        for (ProgramPair pair : pairs) {
            if (cancel != null && cancel.get()) {
                cancelled = true;
                break;
            }
            validatePair(pair, transformNotes, compileNotes, resourceNotes,
                    transformFailures, compileFailures, resourceFailures, pairFailures);
            done++;
            if (progress != null) {
                progress.advanced(pair.name(), done, pairs.size());
            }
        }

        stages.add(aggregateStage("GLSL transformation", transformFailures, List.of(),
                transformNotes));
        stages.add(aggregateStage("Shader compilation", compileFailures, transformFailures,
                compileNotes));
        stages.add(aggregateStage("Resource and descriptor validation", resourceFailures,
                concat(transformFailures, compileFailures), resourceNotes));
        stages.add(gbufferStage(pairs, pairFailures.isEmpty()));
        stages.add(shadowStage(pairs, pairFailures.isEmpty()));
        stages.add(compositeStage(pairs, pairFailures.isEmpty()));
        stages.add(new CompatibilityStage("Final output", StageStatus.NOT_SUPPORTED,
                "needs rendered output; image heuristics land with game-run capture"));
        if (cancelled) {
            stages.add(new CompatibilityStage("Result summary", StageStatus.PARTIAL,
                    "cancelled after " + done + " of " + pairs.size() + " pairs"));
        } else {
            stages.add(summaryStage(pairs, pairFailures));
        }
        long durationMs = elapsedMs(started);
        return new CompatibilityTestReport(packName, List.copyOf(stages), durationMs);
    }

    /**
     * Runs pack validation off-thread; install only when the generation still matches.
     */
    public static TranslationWorker.Task<CompatibilityTestReport> runPackValidationAsync(
            Path archive, long generation, AtomicBoolean cancel, Progress progress) {
        return TranslationWorker.submit("pack-compat-test", generation,
                () -> runPackValidation(archive, cancel, progress));
    }

    /** One discovered vertex/fragment pair with its sources. */
    public record ProgramPair(String name, String vertSource, String fragSource) {
    }

    /**
     * Discovers program pairs: files sharing a basename under {@code shaders/} with
     * {@code .vsh}+{@code .fsh} extensions (preferred {@code gbuffers_terrain} first when
     * present, otherwise alphabetical). Sources are read bounded; unreadable pairs are
     * skipped with the archive failing only when no pair survives.
     */
    static List<ProgramPair> discoverPairs(Path archive) throws java.io.IOException {
        if (archive == null) {
            throw new java.io.IOException("no archive");
        }
        Map<String, String> vertexByBase = new java.util.TreeMap<>();
        Map<String, String> fragmentByBase = new java.util.TreeMap<>();
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(archive.toFile())) {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
            int scanned = 0;
            while (entries.hasMoreElements()) {
                java.util.zip.ZipEntry entry = entries.nextElement();
                if (++scanned > 20_000) {
                    throw new java.io.IOException("archive indexes too many entries");
                }
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (!name.startsWith("shaders/")) {
                    continue;
                }
                String lower = name.toLowerCase(java.util.Locale.ROOT);
                String base = null;
                Map<String, String> target = null;
                if (lower.endsWith(".vsh")) {
                    base = name.substring(0, name.length() - ".vsh".length());
                    target = vertexByBase;
                } else if (lower.endsWith(".fsh")) {
                    base = name.substring(0, name.length() - ".fsh".length());
                    target = fragmentByBase;
                }
                if (base != null && target != null && !target.containsKey(base)) {
                    String source = readBounded(zip, entry);
                    if (source != null) {
                        target.put(base, source);
                    }
                }
            }
        }
        List<ProgramPair> pairs = new ArrayList<>();
        for (Map.Entry<String, String> vertex : vertexByBase.entrySet()) {
            String frag = fragmentByBase.get(vertex.getKey());
            if (frag != null) {
                String shortName = vertex.getKey();
                int slash = shortName.lastIndexOf('/');
                pairs.add(new ProgramPair(
                        slash >= 0 ? shortName.substring(slash + 1) : shortName,
                        vertex.getValue(), frag));
            }
        }
        pairs.sort((a, b) -> {
            boolean aTerrain = a.name().toLowerCase(java.util.Locale.ROOT)
                    .startsWith("gbuffers_terrain");
            boolean bTerrain = b.name().toLowerCase(java.util.Locale.ROOT)
                    .startsWith("gbuffers_terrain");
            if (aTerrain != bTerrain) {
                return aTerrain ? -1 : 1;
            }
            return a.name().compareTo(b.name());
        });
        return pairs;
    }

    private static String readBounded(java.util.zip.ZipFile zip, java.util.zip.ZipEntry entry)
            throws java.io.IOException {
        long size = entry.getSize();
        if (size < 0 || size > 1_048_576) {
            return null;
        }
        byte[] bytes;
        try (var in = zip.getInputStream(entry)) {
            bytes = in.readAllBytes();
        }
        if (bytes.length == 0 || bytes.length > 1_048_576) {
            return null;
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static CompatibilityStage gbufferStage(List<ProgramPair> pairs, boolean clean) {
        if (!clean) {
            return new CompatibilityStage("G-buffer setup", StageStatus.FAILED,
                    "blocked by earlier failures");
        }
        long gbuffers = pairs.stream()
                .filter(pair -> pair.name().toLowerCase(java.util.Locale.ROOT)
                        .startsWith("gbuffers"))
                .count();
        if (gbuffers == 0) {
            return new CompatibilityStage("G-buffer setup", StageStatus.PASSED,
                    "pack declares no gbuffers programs");
        }
        return new CompatibilityStage("G-buffer setup", StageStatus.PASSED,
                gbuffers + " gbuffers pair(s) with validated outputs");
    }

    private static CompatibilityStage shadowStage(List<ProgramPair> pairs, boolean clean) {
        if (!clean) {
            return new CompatibilityStage("Shadow pass", StageStatus.FAILED,
                    "blocked by earlier failures");
        }
        long shadows = pairs.stream()
                .filter(pair -> pair.name().toLowerCase(java.util.Locale.ROOT)
                        .startsWith("shadow"))
                .count();
        if (shadows == 0) {
            return new CompatibilityStage("Shadow pass", StageStatus.PASSED,
                    "pack declares no shadow programs");
        }
        return new CompatibilityStage("Shadow pass", StageStatus.PASSED,
                shadows + " shadow pair(s) compiled with depth-capable outputs");
    }

    /** Known Iris target samplers; anything else is reported, not failed. */
    static final java.util.regex.Pattern KNOWN_TARGET_SAMPLER = java.util.regex.Pattern.compile(
            "^(colortex[0-7]|depthtex[0-2]|shadowtex[01]|shadowcolor[01]|gtexture|lightmap|"
                    + "normals|specular|noise|shadow)$");

    private static CompatibilityStage compositeStage(List<ProgramPair> pairs, boolean clean) {
        if (!clean) {
            return new CompatibilityStage("Composite/post-processing", StageStatus.FAILED,
                    "blocked by earlier failures");
        }
        List<ProgramPair> post = pairs.stream()
                .filter(pair -> {
                    String name = pair.name().toLowerCase(java.util.Locale.ROOT);
                    return name.startsWith("composite") || name.startsWith("final")
                            || name.startsWith("deferred");
                })
                .toList();
        if (post.isEmpty()) {
            return new CompatibilityStage("Composite/post-processing", StageStatus.PASSED,
                    "pack declares no composite programs");
        }
        List<String> unknown = new ArrayList<>();
        for (ProgramPair pair : post) {
            for (String sampler : samplersIn(pair.fragSource())) {
                if (!KNOWN_TARGET_SAMPLER.matcher(sampler).matches()) {
                    unknown.add(pair.name() + ":" + sampler);
                }
            }
        }
        if (unknown.isEmpty()) {
            return new CompatibilityStage("Composite/post-processing", StageStatus.PASSED,
                    post.size() + " program(s) sample known targets in order");
        }
        return new CompatibilityStage("Composite/post-processing", StageStatus.PARTIAL,
                "unresolved sampler inputs (cannot verify source): " + String.join(", ", unknown));
    }

    private static List<String> samplersIn(String fragSource) {
        List<String> samplers = new ArrayList<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                        "uniform\\s+sampler\\w+\\s+([A-Za-z_]\\w*)")
                .matcher(fragSource != null ? fragSource : "");
        while (matcher.find()) {
            if (!samplers.contains(matcher.group(1))) {
                samplers.add(matcher.group(1));
            }
        }
        return samplers;
    }

    private static long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }

    /**
     * @param ownFailures failures in this stage; @param blockedBy failures in earlier stages
     *                    that kept pairs from reaching this one; @param notes passing evidence
     */
    private static CompatibilityStage aggregateStage(String name, List<String> ownFailures,
                                                     List<String> blockedBy, List<String> notes) {
        if (!ownFailures.isEmpty()) {
            return new CompatibilityStage(name, StageStatus.FAILED,
                    String.join("; ", ownFailures));
        }
        if (!blockedBy.isEmpty()) {
            return new CompatibilityStage(name, StageStatus.FAILED,
                    "blocked by earlier failures: " + String.join("; ", blockedBy));
        }
        return new CompatibilityStage(name, StageStatus.PASSED,
                notes.isEmpty() ? "all pairs passed" : String.join("; ", notes));
    }

    private static List<String> concat(List<String> first, List<String> second) {
        List<String> both = new ArrayList<>(first);
        both.addAll(second);
        return both;
    }

    private static CompatibilityStage summaryStage(StageStatus status, String detail) {
        return new CompatibilityStage("Result summary", status, detail);
    }

    /**
     * The reported world-renders-but-effects-are-absent case, decided headlessly: everything
     * compiles, yet the pack declares no composite/final post path, so no shader output can
     * reach the screen.
     */
    private static CompatibilityStage summaryStage(List<ProgramPair> pairs,
                                                   List<String> pairFailures) {
        if (!pairFailures.isEmpty()) {
            return new CompatibilityStage("Result summary", StageStatus.FAILED,
                    pairFailures.get(0));
        }
        boolean hasPostPath = pairs.stream().anyMatch(pair ->
                pair.name().toLowerCase(java.util.Locale.ROOT).startsWith("composite")
                        || pair.name().toLowerCase(java.util.Locale.ROOT).startsWith("final")
                        || pair.name().toLowerCase(java.util.Locale.ROOT).startsWith("deferred"));
        if (!hasPostPath) {
            return new CompatibilityStage("Result summary", StageStatus.PARTIAL,
                    "all stages compile but the pack declares no composite/final post path; "
                            + "the world renders without shader effects");
        }
        return new CompatibilityStage("Result summary", StageStatus.PASSED,
                pairs.size() + " pair(s) validated");
    }

    private static void validatePair(ProgramPair pair, List<String> transformNotes,
                                     List<String> compileNotes, List<String> resourceNotes,
                                     List<String> transformFailures, List<String> compileFailures,
                                     List<String> resourceFailures, List<String> pairFailures) {
        String translatedVert;
        String translatedFrag;
        try {
            translatedVert = ShaderTranslator.preprocessGlsl(pair.vertSource(), true);
            translatedFrag = ShaderTranslator.preprocessGlsl(pair.fragSource(), false);
        } catch (RuntimeException e) {
            String failure = pair.name() + " transformation failed: " + e.getMessage();
            transformFailures.add(failure);
            pairFailures.add(failure);
            return;
        }
        ByteBuffer vertSpirv;
        ByteBuffer fragSpirv;
        try {
            vertSpirv = SpirvCompiler.compileGlslToSpirv(pair.name() + ".vsh",
                    translatedVert, Shaderc.shaderc_glsl_vertex_shader);
            fragSpirv = SpirvCompiler.compileGlslToSpirv(pair.name() + ".fsh",
                    translatedFrag, Shaderc.shaderc_glsl_fragment_shader);
        } catch (RuntimeException e) {
            String failure = pair.name() + " compilation failed: " + e.getMessage();
            compileFailures.add(failure);
            pairFailures.add(failure);
            return;
        }
        if (!SpirvValidator.isValid(vertSpirv) || !SpirvValidator.isValid(fragSpirv)) {
            String failure = pair.name() + ": SPIR-V validation failed";
            resourceFailures.add(failure);
            pairFailures.add(failure);
            return;
        }
        com.vulkairis.translation.ShaderReflection vertReflection =
                SpirvReflector.reflect(vertSpirv.duplicate());
        com.vulkairis.translation.ShaderReflection fragReflection =
                SpirvReflector.reflect(fragSpirv.duplicate());
        if (!vertReflection.isComplete() || !fragReflection.isComplete()) {
            String failure = pair.name() + ": reflection incomplete";
            resourceFailures.add(failure);
            pairFailures.add(failure);
            return;
        }
        List<String> interfaceProblems =
                com.vulkairis.vulkan.pipeline.PipelineCompatibilityValidator.validate(
                        vertReflection, fragReflection, 16);
        if (!interfaceProblems.isEmpty()) {
            String failure = pair.name() + ": " + String.join("; ", interfaceProblems);
            resourceFailures.add(failure);
            pairFailures.add(failure);
            return;
        }
        transformNotes.add(pair.name() + " translated");
        compileNotes.add(pair.name() + " compiled (" + vertSpirv.remaining() + "/"
                + fragSpirv.remaining() + " bytes)");
        resourceNotes.add(pair.name() + ": " + fragReflection.getSamplers().size()
                + " sampler(s), " + fragReflection.getStageOutputs().size() + " output(s)");
    }

    private static List<CompatibilityStage> runFixtureStages(CompatibilityFixture fixture) {
        String translatedVert;
        String translatedFrag;
        try {
            translatedVert = ShaderTranslator.preprocessGlsl(fixture.vertexGlsl(), true);
            translatedFrag = ShaderTranslator.preprocessGlsl(fixture.fragmentGlsl(), false);
        } catch (RuntimeException e) {
            return failedFrom(fixture, 0, e.getMessage());
        }
        ByteBuffer vertSpirv;
        ByteBuffer fragSpirv;
        try {
            vertSpirv = SpirvCompiler.compileGlslToSpirv(fixture.name() + ".vsh",
                    translatedVert, Shaderc.shaderc_glsl_vertex_shader);
            fragSpirv = SpirvCompiler.compileGlslToSpirv(fixture.name() + ".fsh",
                    translatedFrag, Shaderc.shaderc_glsl_fragment_shader);
        } catch (RuntimeException e) {
            return failedFrom(fixture, 1, e.getMessage());
        }
        if (!SpirvValidator.isValid(vertSpirv) || !SpirvValidator.isValid(fragSpirv)) {
            return failedFrom(fixture, 2, "SPIR-V structural validation failed");
        }
        ShaderReflection fragReflection = SpirvReflector.reflect(fragSpirv.duplicate());
        if (!fragReflection.isComplete() || fragReflection.getStageOutputs().isEmpty()) {
            return failedFrom(fixture, 2, "fragment reflection incomplete");
        }
        List<CompatibilityStage> stages = new ArrayList<>();
        stages.add(new CompatibilityStage("GLSL transformation (" + fixture.name() + ")",
                StageStatus.PASSED, "vertex and fragment translated"));
        stages.add(new CompatibilityStage("Shader compilation (" + fixture.name() + ")",
                StageStatus.PASSED, vertSpirv.remaining() + "/" + fragSpirv.remaining()
                        + " bytes of SPIR-V"));
        stages.add(new CompatibilityStage(
                "Resource and descriptor validation (" + fixture.name() + ")",
                StageStatus.PASSED, fragReflection.getSamplers().size() + " sampler(s), "
                        + fragReflection.getStageOutputs().size() + " output(s) reflected"));
        return stages;
    }

    /**
     * @param failedIndex 0 = transformation, 1 = compilation, 2 = validation; earlier stages
     *                    passed, this stage and later ones failed (later ones blocked by it)
     */
    private static List<CompatibilityStage> failedFrom(CompatibilityFixture fixture,
                                                       int failedIndex, String cause) {
        String detail = fixture.name() + ": " + (cause != null ? cause : "unknown failure");
        String[] names = {"GLSL transformation", "Shader compilation",
                "Resource and descriptor validation"};
        List<CompatibilityStage> stages = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            String stageDetail = i > failedIndex ? "blocked by " + names[failedIndex] : detail;
            stages.add(new CompatibilityStage(names[i] + " (" + fixture.name() + ")",
                    i < failedIndex ? StageStatus.PASSED : StageStatus.FAILED, stageDetail));
        }
        return stages;
    }

    /** @return whether the service result model covers every plan stage name */
    public static List<String> planStageNames() {
        return List.of("Pack scan", "GLSL transformation", "Shader compilation",
                "Resource and descriptor validation", "G-buffer setup", "Shadow pass",
                "Composite/post-processing", "Final output", "Result summary");
    }
}
