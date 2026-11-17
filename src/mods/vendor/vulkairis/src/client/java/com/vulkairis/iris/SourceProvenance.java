package com.vulkairis.iris;

import com.vulkairis.diagnostics.DiagnosticManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ownership boundary between Iris and Vulkairis (Phase 2).
 *
 * <p>Iris owns pack loading (ZIP, includes), the option GUI, macro resolution and all
 * {@code TransformPatcher} passes. Vulkairis owns nothing before the
 * {@code GlShader.createShader} interception point; it captures the final Iris-transformed
 * source exactly once per pipeline generation and traces every translation output back to the
 * exact input hash, stage and generation.
 *
 * <p>The pipeline generation is bumped on pack change, dimension change, resource reload and
 * Iris pipeline rebuild. Units from an older generation are stale and must never be reused.
 */
public final class SourceProvenance {
    private static final Pattern DEFINE_PATTERN =
            Pattern.compile("^\\s*#\\s*define\\s+([A-Za-z_][A-Za-z0-9_]*)", Pattern.MULTILINE);

    private static final AtomicLong GENERATION = new AtomicLong(0);
    private static final ConcurrentHashMap<String, SourceUnit> UNITS = new ConcurrentHashMap<>();

    private SourceProvenance() {
    }

    /** @return the current pipeline generation */
    public static long currentGeneration() {
        return GENERATION.get();
    }

    /**
     * Starts a new pipeline generation, invalidating every previously captured unit.
     *
     * @param reason pack change, dimension change, resource reload or pipeline rebuild
     * @return the new generation
     */
    public static long nextGeneration(String reason) {
        long generation = GENERATION.incrementAndGet();
        DiagnosticManager.logStructured(DiagnosticManager.Category.COMPATIBILITY,
                DiagnosticManager.Phase.SCHEDULING,
                "Pipeline generation advanced", reason, null,
                null, null, null, -1, null,
                "Cached translations and pipelines from older generations are stale");
        return generation;
    }

    /** Normalizes a stage key so {@code VERTEX} and {@code 35633} address the same unit. */
    public static String normalizeStage(String stage) {
        if (stage == null) {
            return "UNKNOWN";
        }
        return switch (stage.trim().toUpperCase()) {
            case "35633", "VERTEX" -> "VERTEX";
            case "35632", "FRAGMENT" -> "FRAGMENT";
            case "36313", "GEOMETRY" -> "GEOMETRY";
            case "37305", "COMPUTE" -> "COMPUTE";
            default -> stage.trim().toUpperCase();
        };
    }

    private static String unitId(String packId, String program, String stage, long generation) {
        return packId + "|" + program + "|" + stage + "|" + generation;
    }

    /**
     * Captures an intercepted Iris-transformed source exactly once per generation. A repeated
     * capture of identical input returns the existing unit; changed input replaces it.
     */
    public static SourceUnit capture(String packId, String program, String stageKey, String source) {
        String stage = normalizeStage(stageKey);
        String pack = packId != null ? packId : "unknown-pack";
        String name = program != null ? program : "unknown-program";
        long generation = currentGeneration();
        String id = unitId(pack, name, stage, generation);
        String hash = DiagnosticManager.hashSource(source);

        SourceUnit existing = UNITS.get(id);
        if (existing != null && ((hash == null && existing.originalHash() == null)
                || (hash != null && hash.equals(existing.originalHash())))) {
            return existing;
        }

        SourceUnit unit = new SourceUnit(id, pack, name, stage, hash, null,
                "iris-transformed", observedMacros(source), generation);
        UNITS.put(id, unit);
        return unit;
    }

    /**
     * Records translator output against the captured unit for the same pack/program/stage in the
     * current generation. Sources that never passed the interception boundary (tests, dry-run
     * analysis) gain an {@code unintercepted} unit so they stay traceable too.
     */
    public static SourceUnit recordTranslation(
            String packId, String program, String stageKey, String translatedSource) {
        String stage = normalizeStage(stageKey);
        String pack = packId != null ? packId : "unknown-pack";
        String name = program != null ? program : "unknown-program";
        long generation = currentGeneration();
        String translatedHash = DiagnosticManager.hashSource(translatedSource);

        SourceUnit unit = UNITS.get(unitId(pack, name, stage, generation));
        if (unit == null) {
            unit = new SourceUnit(unitId(pack, name, stage, generation), pack, name, stage,
                    null, null, "unintercepted", List.of(), generation);
        }
        SourceUnit updated = unit.withTranslation(translatedHash);
        UNITS.put(updated.id(), updated);
        return updated;
    }

    /** @return whether the unit belongs to an older pipeline generation and must not be reused */
    public static boolean isStale(SourceUnit unit) {
        return unit == null || unit.pipelineGeneration() != currentGeneration();
    }

    /** @return live (current-generation) units, for diagnostics */
    public static List<SourceUnit> liveUnits() {
        long generation = currentGeneration();
        List<SourceUnit> live = new ArrayList<>();
        for (Map.Entry<String, SourceUnit> entry : UNITS.entrySet()) {
            if (entry.getValue().pipelineGeneration() == generation) {
                live.add(entry.getValue());
            }
        }
        return live;
    }

    static List<String> observedMacros(String source) {
        if (source == null || source.isBlank()) {
            return List.of();
        }
        List<String> macros = new ArrayList<>();
        Matcher matcher = DEFINE_PATTERN.matcher(source);
        while (matcher.find()) {
            String macro = matcher.group(1);
            if (!macros.contains(macro)) {
                macros.add(macro);
            }
        }
        return macros;
    }

    /** Clears all units and resets the generation; tests only. */
    public static void clearForTests() {
        UNITS.clear();
        GENERATION.set(0);
    }
}
