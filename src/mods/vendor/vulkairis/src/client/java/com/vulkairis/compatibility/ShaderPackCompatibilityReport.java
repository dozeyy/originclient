package com.vulkairis.compatibility;

import com.vulkairis.iris.ShaderPackProgramCatalog;
import net.irisshaders.iris.shaderpack.loading.ProgramGroup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Phase 14/16: an honest, shader-pack compatibility report.
 *
 * <p>Instead of claiming success for operations that were silently discarded, this classifies each
 * feature a pack uses as supported, partially supported, unsupported, or unused, based on the
 * discovered program set and whether the corresponding execution path is operational. It is the data
 * behind the {@code /vulkairis compatibility} command.</p>
 */
public final class ShaderPackCompatibilityReport {

    /** Degree to which a feature is handled. */
    public enum Support {
        SUPPORTED,
        PARTIAL,
        UNSUPPORTED,
        NOT_USED
    }

    /**
     * @param name    feature name
     * @param support support classification
     * @param note    why it is classified that way
     */
    public record Feature(String name, Support support, String note) {
    }

    private final String packName;
    private final List<Feature> features;

    private ShaderPackCompatibilityReport(String packName, List<Feature> features) {
        this.packName = packName;
        this.features = Collections.unmodifiableList(features);
    }

    /**
     * Evaluates a discovered pack.
     *
     * @param packName           the active pack name
     * @param catalog            the discovered catalog, or {@code null} when no pack is active
     * @param computeOperational whether the compute execution path is operational
     * @return the report
     */
    public static ShaderPackCompatibilityReport evaluate(
            String packName, ShaderPackProgramCatalog catalog, boolean computeOperational) {
        List<Feature> features = new ArrayList<>();
        if (catalog == null || catalog.isEmpty()) {
            features.add(new Feature("shader pack", Support.NOT_USED,
                    "no shader pack programs discovered; vanilla rendering is unaffected"));
            return new ShaderPackCompatibilityReport(packName, features);
        }

        features.add(feature("shadow pass",
                catalog.hasGroup(ProgramGroup.Shadow),
                true, "shadow programs discovered; shadow renderer available"));
        features.add(feature("gbuffers geometry",
                catalog.hasGroup(ProgramGroup.Gbuffers), true,
                "gbuffers programs discovered; geometry pass config available"));
        features.add(feature("deferred",
                catalog.hasGroup(ProgramGroup.Deferred), false,
                "deferred programs discovered; execution contract is partially wired"));
        features.add(feature("composite/final post-processing",
                catalog.hasGroup(ProgramGroup.Composite) || catalog.hasGroup(ProgramGroup.Final), true,
                "composite/final programs discovered; ping-pong chain planner available"));
        features.add(feature("multiple render targets",
                catalog.hasGroup(ProgramGroup.Gbuffers), false,
                "graph supports MRT; attachment wiring is device-side"));
        features.add(feature("depth textures",
                catalog.hasGroup(ProgramGroup.Gbuffers) || catalog.hasGroup(ProgramGroup.Composite), true,
                "scene depth is exposed as a sampled image"));

        boolean hasCompute = !catalog.getComputePrograms().isEmpty();
        features.add(hasCompute
                ? new Feature("compute", computeOperational ? Support.SUPPORTED : Support.PARTIAL,
                        computeOperational
                                ? "compute programs discovered; compute pipeline/dispatch available"
                                : "compute programs discovered; compute pipeline not proven operational")
                : new Feature("compute", Support.NOT_USED, "pack declares no compute programs"));
        features.add(feature("storage images",
                hasCompute && computeOperational, computeOperational,
                "storage image binding depends on the compute path"));

        return new ShaderPackCompatibilityReport(packName, features);
    }

    private static Feature feature(String name, boolean used, boolean fullySupported, String note) {
        if (!used) {
            return new Feature(name, Support.NOT_USED, "pack does not use this feature");
        }
        return new Feature(name, fullySupported ? Support.SUPPORTED : Support.PARTIAL, note);
    }

    public String getPackName() {
        return packName;
    }

    public List<Feature> getFeatures() {
        return features;
    }

    /**
     * Phase 11: merges static discovery with observed execution. Every requested program group
     * is listed with whether it ran, fell back, or was rejected, and a discovered-but-never-run
     * shadow pass is downgraded so a pack is never labeled supported from compile results alone.
     *
     * @param catalog the discovered catalog (may be {@code null})
     * @param record  the cumulative execution record (may be {@code null})
     * @return a new report with runtime lines appended
     */
    public ShaderPackCompatibilityReport withRuntime(
            ShaderPackProgramCatalog catalog,
            com.vulkairis.rendering.exec.PassExecutionRecord record) {
        List<Feature> merged = new ArrayList<>(features);
        if (catalog != null && !catalog.isEmpty() && record != null) {
            for (ProgramGroup group : catalog.getPresentGroups()) {
                Support outcome = groupOutcome(catalog, group, record);
                String note = switch (outcome) {
                    case SUPPORTED -> "observed executing";
                    case PARTIAL -> "requested but never observed executing cleanly";
                    case UNSUPPORTED -> "requested but rejected at execution";
                    case NOT_USED -> "not requested";
                };
                merged.add(new Feature("run: " + group.name().toLowerCase(), outcome, note));
            }
            merged = downgradeUnrunShadow(merged, catalog, record);
        }
        return new ShaderPackCompatibilityReport(packName, merged);
    }

    private static Support groupOutcome(ShaderPackProgramCatalog catalog, ProgramGroup group,
                                        com.vulkairis.rendering.exec.PassExecutionRecord record) {
        boolean fellBack = false;
        boolean rejected = false;
        boolean ran = false;
        boolean seen = false;
        for (com.vulkairis.iris.ShaderPackProgram program : catalog.getPrograms(group)) {
            com.vulkairis.rendering.exec.PassExecutionRecord.Outcome outcome =
                    record.outcomeOf(program.name());
            if (outcome == null) {
                continue;
            }
            seen = true;
            switch (outcome) {
                case RAN -> ran = true;
                case FELL_BACK -> fellBack = true;
                case REJECTED -> rejected = true;
            }
        }
        if (ran) {
            return Support.SUPPORTED;
        }
        if (fellBack) {
            return Support.PARTIAL;
        }
        if (rejected || seen) {
            return Support.UNSUPPORTED;
        }
        return Support.PARTIAL;
    }

    private static List<Feature> downgradeUnrunShadow(List<Feature> merged,
                                                      ShaderPackProgramCatalog catalog,
                                                      com.vulkairis.rendering.exec.PassExecutionRecord record) {
        if (!catalog.hasGroup(ProgramGroup.Shadow)) {
            return merged;
        }
        Support shadowOutcome = groupOutcome(catalog, ProgramGroup.Shadow, record);
        if (shadowOutcome == Support.SUPPORTED) {
            return merged;
        }
        List<Feature> adjusted = new ArrayList<>();
        for (Feature feature : merged) {
            if (feature.name().equals("shadow pass") && feature.support() == Support.SUPPORTED) {
                adjusted.add(new Feature("shadow pass", Support.PARTIAL,
                        feature.note() + "; declared but never observed executing"));
            } else {
                adjusted.add(feature);
            }
        }
        return adjusted;
    }

    /** @return how many features have the given classification */
    public long count(Support support) {
        return features.stream().filter(feature -> feature.support() == support).count();
    }

    /**
     * @return a multi-line, chat-friendly summary
     */
    public String summarize() {
        StringBuilder builder = new StringBuilder();
        builder.append("Shader pack compatibility: ").append(packName);
        builder.append(" [").append(count(Support.SUPPORTED)).append(" supported, ")
                .append(count(Support.PARTIAL)).append(" partial, ")
                .append(count(Support.UNSUPPORTED)).append(" unsupported]");
        for (Feature feature : features) {
            builder.append("\n  ").append(symbol(feature.support())).append(' ')
                    .append(feature.name()).append(" - ").append(feature.note());
        }
        return builder.toString();
    }

    private static String symbol(Support support) {
        return switch (support) {
            case SUPPORTED -> "[OK]";
            case PARTIAL -> "[~]";
            case UNSUPPORTED -> "[X]";
            case NOT_USED -> "[-]";
        };
    }
}
