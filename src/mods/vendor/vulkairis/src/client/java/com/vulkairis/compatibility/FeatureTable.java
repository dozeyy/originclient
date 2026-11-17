package com.vulkairis.compatibility;

import java.util.List;

/**
 * The published feature table (Phase 13): what Vulkairis implements, what the automated suite
 * tests, what is experimental, and what is unsupported — pinned to exact dependency versions.
 *
 * <p>There is deliberately no "all shaders compatible" row: a pack is supported only for the
 * families below marked accordingly, and anything else is rejected with a reason instead of
 * rendered wrong. {@code TESTED} means covered by the automated suite; {@code EXPERIMENTAL}
 * means implemented but still awaiting game-run evidence; game-run proof is tracked per row.</p>
 */
public final class FeatureTable {

    /** Pinned target stack. Must match {@code gradle.properties} and {@code fabric.mod.json}. */
    public static final String MINECRAFT_VERSION = "1.21.1";
    /** Pinned target stack. Must match {@code gradle.properties} and {@code fabric.mod.json}. */
    public static final String IRIS_VERSION = "1.8.14-beta.1+mc1.21.1";
    /** Pinned target stack. Must match {@code gradle.properties} and {@code fabric.mod.json}. */
    public static final String VULKANMOD_VERSION = "0.6.7+1.21.1";

    /** Maturity of one feature. */
    public enum Maturity {
        IMPLEMENTED,
        TESTED,
        EXPERIMENTAL,
        UNSUPPORTED
    }

    /** @param feature what @param maturity where it stands @param note evidence or reason */
    public record FeatureRow(String feature, Maturity maturity, String note) {
    }

    private FeatureTable() {
    }

    /** @return the full published table, in plan order */
    public static List<FeatureRow> rows() {
        return List.of(
                new FeatureRow("Iris source interception (post-transform boundary)",
                        Maturity.TESTED, "provenance units, generations and idempotence in suite"),
                new FeatureRow("Token-based GLSL translation (legacy keywords)",
                        Maturity.TESTED, "lexer/stream/pass goldens; remaining rewrites migrate per fixture"),
                new FeatureRow("SPIR-V compilation, vertex/fragment, Vulkan 1.1",
                        Maturity.TESTED, "honest results, stage gates, profile digest in suite"),
                new FeatureRow("Structural SPIR-V validation",
                        Maturity.TESTED, "framing, ids, entry points in suite"),
                new FeatureRow("Full semantic SPIR-V validation (spirv-val)",
                        Maturity.UNSUPPORTED, "native SPIRV-Tools unavailable to the offline build"),
                new FeatureRow("SPIR-V reflection (interfaces, descriptors, blocks)",
                        Maturity.TESTED, "entry points, locations, sets/bindings, offsets in suite"),
                new FeatureRow("Program link validation",
                        Maturity.TESTED, "stage set, interface types, outputs, sampler budget in suite"),
                new FeatureRow("Virtual GL status facade",
                        Maturity.TESTED, "status/log/uniform/attribute queries in suite"),
                new FeatureRow("VulkanMod pipeline integration (composite/final post chain)",
                        Maturity.EXPERIMENTAL, "route verified against 0.6.7 bytecode; game-run proof pending"),
                new FeatureRow("Controlled test draws (explicit toggle only)",
                        Maturity.EXPERIMENTAL, "gated, per-pass compatible, state-restoring; game-run proof pending"),
                new FeatureRow("Render targets, graph, barriers, layouts",
                        Maturity.TESTED, "descriptors, validation, viewer, clear tracking in suite"),
                new FeatureRow("Uniform isolation and std140 packing",
                        Maturity.TESTED, "frame store, strides, planner end-to-end in suite"),
                new FeatureRow("Pass scheduling gates and frame traces",
                        Maturity.TESTED, "gates, validator, pinning, runtime reports in suite"),
                new FeatureRow("Terrain/entity render-stage execution",
                        Maturity.UNSUPPORTED, "gates closed; no execution path yet"),
                new FeatureRow("Shadow-map execution",
                        Maturity.EXPERIMENTAL, "config and matrices tested; game-run proof pending"),
                new FeatureRow("Water/translucency execution",
                        Maturity.UNSUPPORTED, "gate closed; no execution path yet"),
                new FeatureRow("Tessellation/geometry/compute execution",
                        Maturity.UNSUPPORTED, "rejected at link with reasons"),
                new FeatureRow("HUD/GUI interception",
                        Maturity.UNSUPPORTED, "by design: GUI rendering is never intercepted"));
    }

    /** @return rows at the given maturity */
    public static List<FeatureRow> atMaturity(Maturity maturity) {
        return rows().stream().filter(row -> row.maturity() == maturity).toList();
    }
}
