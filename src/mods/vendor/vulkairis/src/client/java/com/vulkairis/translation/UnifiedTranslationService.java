package com.vulkairis.translation;

import com.vulkairis.diagnostics.DiagnosticManager;
import com.vulkairis.iris.IrisBridge;
import com.vulkairis.iris.SourceProvenance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 4: unified translation coordinator.
 *
 * <p>Eliminates the divergence between per-stage compilation ({@link SpirvCompiler} via
 * {@code GlShaderMixin}) and program-level pipeline construction
 * ({@link com.vulkairis.bridge.VulkanModProgramBridge}). Both paths now derive their
 * translated GLSL from the same canonical {@link ProgramTranslator.TranslatedProgram},
 * which runs the union block, union varyings, legacy builtin, and fragment-output
 * rewrite across all stages of a program together.</p>
 *
 * <h3>Lifecycle</h3>
 * <ol>
 *   <li>{@code GlShaderMixin.createShader()} calls {@link #bufferRawSource} to record the
 *       raw post-Iris GLSL and the virtual shader id that was assigned.</li>
 *   <li>{@code ProgramCreatorMixin.create()} calls {@link #translateProgram} with the
 *       program name and attached shader ids. This retrieves the buffered raw sources,
 *       feeds them through {@link ProgramTranslator#translate}, compiles each translated
 *       stage through {@link SpirvCompiler}, and re-registers the resulting
 *       {@link VulkairisShader}s with updated GLSL, SPIR-V, and reflection.</li>
 *   <li>{@code VulkanModProgramBridge.link()} then reads the already-unified translated
 *       GLSL from the shader objects — no more ad-hoc regex pipeline.</li>
 * </ol>
 *
 * <p>The service is thread-safe: raw-source buffering and translation are guarded by
 * concurrent data structures, and translation itself is idempotent (the underlying
 * {@link ProgramTranslator} cache deduplicates identical requests).</p>
 */
public final class UnifiedTranslationService {

    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkairis/UnifiedTranslation");

    /**
     * A buffered raw source captured at per-stage intercept time, before any
     * cross-stage union or layout policy has been applied.
     */
    public record BufferedSource(
            int virtualShaderId,
            String name,
            String rawGlsl,
            String stage,
            long generation) {
    }

    /**
     * Result of translating a full program through {@link ProgramTranslator}.
     *
     * @param program     the canonical translated program, or {@code null} on failure
     * @param recompiled  whether the attached shaders were successfully recompiled
     * @param errors      any errors encountered during translation or recompilation
     */
    public record TranslationOutcome(
            ProgramTranslator.TranslatedProgram program,
            boolean recompiled,
            List<String> errors) {

        public boolean succeeded() {
            return program != null && recompiled && errors.isEmpty();
        }
    }

    /** Buffered raw sources keyed by virtual shader id. */
    private static final Map<Integer, BufferedSource> BUFFERED = new ConcurrentHashMap<>();

    /** Completed translations keyed by program name + generation. */
    private static final Map<String, TranslationOutcome> OUTCOMES = new ConcurrentHashMap<>();

    private UnifiedTranslationService() {
    }

    // ------------------------------------------------------------------
    // Step 1: Buffer raw source at GlShaderMixin intercept time.
    // ------------------------------------------------------------------

    /**
     * Records the raw post-Iris GLSL for a shader stage. Called from
     * {@code GlShaderMixin.createShader()} immediately after source capture.
     *
     * @param virtualShaderId the virtual shader id assigned to this stage
     * @param name            shader name from Iris (e.g. {@code gbuffers_terrain.vsh})
     * @param rawGlsl         the post-Iris-transform GLSL source
     * @param stageHint       the stage name or GL type name ({@code VERTEX_SHADER}, etc.)
     */
    public static void bufferRawSource(int virtualShaderId, String name,
                                        String rawGlsl, String stageHint) {
        String stage = normalizeStageHint(stageHint);
        long generation = SourceProvenance.currentGeneration();
        BUFFERED.put(virtualShaderId, new BufferedSource(
                virtualShaderId, name, rawGlsl, stage, generation));
        LOGGER.debug("[Vulkairis/UnifiedTranslation] Buffered raw source for shader {} '{}' (stage={}, gen={})",
                virtualShaderId, name, stage, generation);
    }

    /**
     * Retrieves the buffered raw source for a shader id, or {@code null} if none.
     */
    public static BufferedSource getBuffered(int virtualShaderId) {
        return BUFFERED.get(virtualShaderId);
    }

    // ------------------------------------------------------------------
    // Step 2: Translate program at ProgramCreatorMixin.create() time.
    // ------------------------------------------------------------------

    /**
     * Translates all stages of a program together through {@link ProgramTranslator},
     * recompiles each stage from the unified translated GLSL, and updates the
     * shader registry entries with the new GLSL, SPIR-V, and reflection.
     *
     * <p>If any stage's raw source was not buffered (e.g. the shader failed intercept),
     * the unbuffered stage is skipped with a diagnostic note.</p>
     *
     * @param programName      the Iris program name (e.g. {@code gbuffers_terrain})
     * @param attachedShaderIds the virtual shader ids attached to this program
     * @return the translation outcome
     */
    public static TranslationOutcome translateProgram(String programName,
                                                       List<Integer> attachedShaderIds) {
        long generation = SourceProvenance.currentGeneration();
        String outcomeKey = programName + "|" + generation;

        // Fast path: already translated this program at this generation.
        TranslationOutcome cached = OUTCOMES.get(outcomeKey);
        if (cached != null) {
            return cached;
        }

        List<String> errors = new ArrayList<>();
        String packId = IrisBridge.getCurrentPackName();
        if (packId == null) {
            packId = "unknown-pack";
        }

        // Collect buffered raw sources into ProgramTranslator.StageUnit inputs.
        List<ProgramTranslator.StageUnit> units = new ArrayList<>();
        List<Integer> orderedIds = new ArrayList<>(); // parallel to units

        for (int shaderId : attachedShaderIds) {
            BufferedSource buf = BUFFERED.get(shaderId);
            if (buf == null) {
                // Fallback: read raw GLSL from the already-registered shader.
                VulkairisShader existing = VulkanShaderRegistry.getShader(shaderId);
                if (existing != null && existing.glslSource() != null) {
                    String stage = mapGlStageToTranslatorStage(existing.getStage());
                    String includerPath = existing.getName();
                    units.add(new ProgramTranslator.StageUnit(stage, existing.glslSource(), includerPath));
                    orderedIds.add(shaderId);
                    LOGGER.debug("[Vulkairis/UnifiedTranslation] Using registry fallback for shader {} '{}' (stage={})",
                            shaderId, existing.getName(), stage);
                } else {
                    errors.add("shader " + shaderId + " has no buffered or registered source");
                }
                continue;
            }
            units.add(new ProgramTranslator.StageUnit(buf.stage(), buf.rawGlsl(), buf.name()));
            orderedIds.add(shaderId);
        }

        if (units.isEmpty()) {
            errors.add("no translatable stages for program '" + programName + "'");
            TranslationOutcome outcome = new TranslationOutcome(null, false, List.copyOf(errors));
            OUTCOMES.put(outcomeKey, outcome);
            return outcome;
        }

        // DH degrade pass: without the Distant Horizons mod a fully DH-driven program
        // (every stage references DH-only identifiers) can never compile, so reject the
        // whole program once with the labeled reason instead of translating and dumping
        // one shaderc error per stage. Mixed programs keep the per-stage behavior below.
        if (isDistantHorizonsProgram(programName, units)) {
            String reason = com.vulkairis.compatibility.DistantHorizonsSupport.skipReason(programName);
            errors.add(reason);
            LOGGER.warn("[Vulkairis/UnifiedTranslation] Skipping program '{}': {}",
                    programName, reason);
            DiagnosticManager.logStructured(DiagnosticManager.Category.SHADER,
                    DiagnosticManager.Phase.TRANSLATE,
                    com.vulkairis.compatibility.DistantHorizonsSupport.SKIP_HEAD,
                    reason, null,
                    programName, null, null, -1, null,
                    "No action needed; far terrain degrades to vanilla rendering");
            TranslationOutcome outcome = new TranslationOutcome(null, false, List.copyOf(errors));
            OUTCOMES.put(outcomeKey, outcome);
            return outcome;
        }

        // Run the canonical program-together translation.
        ProgramTranslator.TranslatedProgram translated;
        try {
            translated = ProgramTranslator.translate(programName, units, packId, generation);
        } catch (Throwable t) {
            errors.add("ProgramTranslator.translate() failed: " + t.getMessage());
            LOGGER.warn("[Vulkairis/UnifiedTranslation] Translation failed for '{}': {}",
                    programName, t.getMessage(), t);
            DiagnosticManager.logStructured(DiagnosticManager.Category.SHADER,
                    DiagnosticManager.Phase.TRANSLATE,
                    "Unified translation failed", t.getMessage(), t,
                    programName, null, null, -1, null,
                    "Check the shader source for unsupported constructs");
            TranslationOutcome outcome = new TranslationOutcome(null, false, List.copyOf(errors));
            OUTCOMES.put(outcomeKey, outcome);
            return outcome;
        }

        // Recompile each stage from the unified translated GLSL and update the registry.
        boolean allRecompiled = true;
        for (int i = 0; i < orderedIds.size(); i++) {
            int shaderId = orderedIds.get(i);
            String unitStage = units.get(i).stage();

            ProgramTranslator.TranslatedStage translatedStage = translated.stages().get(unitStage);
            if (translatedStage == null) {
                errors.add("stage '" + unitStage + "' missing from translated program");
                allRecompiled = false;
                continue;
            }

            String translatedGlsl = translatedStage.glsl();
            int shadercKind = "VERTEX".equals(unitStage)
                    ? org.lwjgl.util.shaderc.Shaderc.shaderc_glsl_vertex_shader
                    : org.lwjgl.util.shaderc.Shaderc.shaderc_glsl_fragment_shader;

            ShaderCompileResult result;
            try {
                result = SpirvCompiler.compileGlslToSpirvDetailed(
                        programName + "_" + unitStage.toLowerCase(), translatedGlsl, shadercKind);
            } catch (Throwable t) {
                errors.add("SPIR-V compilation failed for stage " + unitStage + ": " + t.getMessage());
                allRecompiled = false;
                continue;
            }

            if (!result.succeeded()) {
                errors.add("SPIR-V compilation failed for stage " + unitStage + ": " + result.compilerLog());
                allRecompiled = false;
                continue;
            }

            // Reflect the compiled SPIR-V.
            ShaderReflection reflection;
            try {
                reflection = SpirvReflector.reflect(result.spirv().duplicate());
            } catch (Throwable t) {
                errors.add("SPIR-V reflection failed for stage " + unitStage + ": " + t.getMessage());
                reflection = new ShaderReflection();
                reflection.markIncomplete();
            }

            // Validate the SPIR-V.
            SpirvValidator.ValidationResult validation =
                    SpirvValidator.validate(result.spirv().duplicate());
            if (!validation.valid()) {
                errors.add("SPIR-V validation failed for stage " + unitStage + ": " + validation.detail());
                // Non-fatal: continue with the compiled bytecode.
            }

            // Re-register the shader with the unified translated GLSL, new SPIR-V,
            // and fresh reflection. The virtual id stays the same.
            VulkairisShader existing = VulkanShaderRegistry.getShader(shaderId);
            if (existing != null) {
                int glStage = existing.getStage();
                VulkairisShader updated = new VulkairisShader(
                        shaderId, existing.getName(), glStage,
                        existing.getVkShaderModule(),
                        result.spirv(), translatedGlsl,
                        reflection.getEntryPointName(), reflection);
                VulkanShaderRegistry.registerShader(updated);
                LOGGER.debug("[Vulkairis/UnifiedTranslation] Re-registered shader {} '{}' with unified GLSL ({} chars, {} SPIR-V bytes)",
                        shaderId, existing.getName(), translatedGlsl.length(),
                        result.spirv() != null ? result.spirv().remaining() : 0);
            }
        }

        // Log notes from the translator.
        for (String note : translated.notes()) {
            LOGGER.info("[Vulkairis/UnifiedTranslation] {}: {}", programName, note);
        }

        TranslationOutcome outcome = new TranslationOutcome(
                translated, allRecompiled, List.copyOf(errors));
        OUTCOMES.put(outcomeKey, outcome);

        if (outcome.succeeded()) {
            LOGGER.info("[Vulkairis/UnifiedTranslation] Program '{}' translated and recompiled successfully ({} stages)",
                    programName, translated.stages().size());
        } else {
            LOGGER.warn("[Vulkairis/UnifiedTranslation] Program '{}' translation completed with {} error(s): {}",
                    programName, errors.size(), errors);
        }

        return outcome;
    }

    // ------------------------------------------------------------------
    // Queries.
    // ------------------------------------------------------------------

    /**
     * Returns the last translation outcome for a program, or {@code null} if none.
     */
    public static TranslationOutcome getOutcome(String programName, long generation) {
        return OUTCOMES.get(programName + "|" + generation);
    }

    /**
     * Returns the number of buffered raw sources. Diagnostic aid.
     */
    public static int bufferedCount() {
        return BUFFERED.size();
    }

    /**
     * Returns the number of completed translation outcomes. Diagnostic aid.
     */
    public static int outcomeCount() {
        return OUTCOMES.size();
    }

    // ------------------------------------------------------------------
    // Reset.
    // ------------------------------------------------------------------

    /**
     * Clears all buffered sources and cached outcomes. Called on pack change,
     * dimension switch, or test reset.
     */
    public static void reset() {
        BUFFERED.clear();
        OUTCOMES.clear();
        LOGGER.debug("[Vulkairis/UnifiedTranslation] Buffers and outcomes cleared");
    }

    // ------------------------------------------------------------------
    // Helpers.
    // ------------------------------------------------------------------

    private static String normalizeStageHint(String hint) {
        if (hint == null || hint.isEmpty()) {
            return "FRAGMENT";
        }
        String upper = hint.toUpperCase(java.util.Locale.ROOT);
        if (upper.contains("VERTEX")) return "VERTEX";
        if (upper.contains("FRAGMENT")) return "FRAGMENT";
        if (upper.contains("GEOMETRY")) return "GEOMETRY";
        if (upper.contains("COMPUTE")) return "COMPUTE";
        if (upper.contains("TESS")) return "TESSELLATION";
        return upper;
    }

    private static String mapGlStageToTranslatorStage(int glStage) {
        return switch (glStage) {
            case 35633, 1 -> "VERTEX";
            case 35632, 2 -> "FRAGMENT";
            case 36313 -> "GEOMETRY";
            case 37305 -> "COMPUTE";
            default -> "FRAGMENT";
        };
    }

    /**
     * DH degrade pass predicate: true only when the DH mod is absent and the program is
     * distant by name or every collected stage source requires DH identifiers.
     */
    private static boolean isDistantHorizonsProgram(
            String programName, List<ProgramTranslator.StageUnit> units) {
        if (com.vulkairis.compatibility.DistantHorizonsSupport.isAvailable()
                || units == null || units.isEmpty()) {
            return false;
        }
        if (com.vulkairis.compatibility.DistantHorizonsSupport.isDistantProgramName(programName)) {
            return true;
        }
        List<com.vulkairis.compatibility.DistantHorizonsSupport.ProgramTranslatorStage> stages =
                new ArrayList<>();
        for (ProgramTranslator.StageUnit unit : units) {
            stages.add(new com.vulkairis.compatibility.DistantHorizonsSupport.ProgramTranslatorStage(
                    unit != null ? unit.source() : null));
        }
        return com.vulkairis.compatibility.DistantHorizonsSupport.shouldSkipProgram(programName, stages);
    }
}
