package com.vulkairis.vulkan.pipeline;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Phase D mesh contract: the Sodium 0.8 compact terrain vertex layout extended for
 * shader-pack attributes.
 *
 * <p>Semantics reimplemented from observed Iris behavior (not copied): Sodium's compact
 * terrain vertex occupies a 20-byte base which Vulkairis preserves verbatim. When a pack's
 * transformed shaders declare extended inputs, Iris selects an extended
 * {@code ChunkVertexType} via its format analysis ({@code hasBlockId/hasMidUv/hasNormal/
 * hasMidBlock}); this contract mirrors that decision with five appended attributes after
 * the 20-byte base, in fixed order:</p>
 *
 * <ol>
 *   <li>{@code BLOCK_ID} — pack block identity (iris {@code BLOCK_ID} attribute).</li>
 *   <li>{@code MID_TEX_COORD} — mid-quad UV (iris {@code MID_TEX_COORD}).</li>
 *   <li>{@code MID_BLOCK} — mid-quad block position (iris {@code MID_BLOCK}).</li>
 *   <li>{@code TANGENT_FRAME} — tangent + handedness frame (iris {@code TANGENT}).</li>
 *   <li>{@code TINT_AND_AO} — packed tint color and ambient occlusion (derived from the
 *       base color/light path; carried explicitly so the Vulkan fetch matches the
 *       pack's expectations).</li>
 * </ol>
 *
 * <p>Byte sizes below are the Vulkairis reimplementation choice (documented, deterministic,
 * device-free): the base is opaque, and each extension has a fixed size so offsets and the
 * total stride are computable without a GPU. The contract is settled once at renderer init
 * (see the Iris render-system path); settling is idempotent.</p>
 */
public final class TerrainVertexContract {

    /** Sodium 0.8 compact terrain vertex base size in bytes (preserved verbatim). */
    public static final int BASE_STRIDE_BYTES = 20;

    /** Attribute descriptor: name, byte size, and Vulkan-facing format hint. */
    public record TerrainAttribute(String name, int sizeBytes, String vkFormat, int components) {
    }

    public static final TerrainAttribute BLOCK_ID =
            new TerrainAttribute("BLOCK_ID", 4, "VK_FORMAT_R32_UINT", 1);
    public static final TerrainAttribute MID_TEX_COORD =
            new TerrainAttribute("MID_TEX_COORD", 8, "VK_FORMAT_R32G32_SFLOAT", 2);
    public static final TerrainAttribute MID_BLOCK =
            new TerrainAttribute("MID_BLOCK", 12, "VK_FORMAT_R32G32B32_SFLOAT", 3);
    public static final TerrainAttribute TANGENT_FRAME =
            new TerrainAttribute("TANGENT_FRAME", 16, "VK_FORMAT_R32G32B32A32_SFLOAT", 4);
    public static final TerrainAttribute TINT_AND_AO =
            new TerrainAttribute("TINT_AND_AO", 8, "VK_FORMAT_R32G32B32A32_SFLOAT_PACKED", 4);

    /** Extended attributes in append order after the 20-byte base. */
    public static final List<TerrainAttribute> EXTENDED_ATTRIBUTES =
            List.of(BLOCK_ID, MID_TEX_COORD, MID_BLOCK, TANGENT_FRAME, TINT_AND_AO);

    /** Total stride with every extension present. */
    public static final int EXTENDED_STRIDE_BYTES = BASE_STRIDE_BYTES
            + BLOCK_ID.sizeBytes() + MID_TEX_COORD.sizeBytes() + MID_BLOCK.sizeBytes()
            + TANGENT_FRAME.sizeBytes() + TINT_AND_AO.sizeBytes();

    /**
     * Element attribute names of {@code DefaultVertexFormat#BLOCK} in element order
     * ({@code Position, Color, UV0, UV2, Normal}), verified against the 1.21.1 Blaze3D
     * class. The block vertex fetch carries exactly the 20-byte Sodium base; none of the
     * five extended attributes above is part of this fetch.
     */
    public static final List<String> BLOCK_ATTRIBUTE_NAMES =
            List.of("Position", "Color", "UV0", "UV2", "Normal");

    private static final AtomicBoolean SETTLED = new AtomicBoolean(false);
    private static volatile String settledBy = "";

    private TerrainVertexContract() {
    }

    /**
     * Settles the contract once (renderer init). Repeat calls are no-ops.
     *
     * @param owner init path label for diagnostics (e.g. {@code "IrisRenderSystem.initRenderer"})
     * @return {@code true} on the first settle, {@code false} when already settled
     */
    public static boolean settle(String owner) {
        if (SETTLED.compareAndSet(false, true)) {
            settledBy = owner == null ? "" : owner;
            return true;
        }
        return false;
    }

    /** @return whether the contract was settled */
    public static boolean isSettled() {
        return SETTLED.get();
    }

    /** @return the init path that settled the contract, or "" when unsettled */
    public static String settledBy() {
        return settledBy;
    }

    /** Resets settle state. Test-only; renderer code never calls this. */
    public static void resetForTests() {
        SETTLED.set(false);
        settledBy = "";
    }

    /**
     * Byte offset of an extended attribute within the vertex.
     *
     * @param name attribute name (one of the five extended names)
     * @return byte offset from the vertex start
     * @throws IllegalArgumentException for unknown names
     */
    public static int offsetOf(String name) {
        int offset = BASE_STRIDE_BYTES;
        for (TerrainAttribute attr : EXTENDED_ATTRIBUTES) {
            if (attr.name().equals(name)) {
                return offset;
            }
            offset += attr.sizeBytes();
        }
        throw new IllegalArgumentException("Unknown terrain vertex attribute '" + name + "'");
    }

    /**
     * Maps each extended attribute name to its shader input location: base inputs occupy
     * locations {@code 0..baseLocationCount-1}, extensions follow in append order.
     *
     * @param baseLocationCount locations consumed by the base layout
     * @return attribute name to input location, in append order
     */
    public static Map<String, Integer> inputLocations(int baseLocationCount) {
        java.util.LinkedHashMap<String, Integer> locations = new java.util.LinkedHashMap<>();
        int location = Math.max(0, baseLocationCount);
        for (TerrainAttribute attr : EXTENDED_ATTRIBUTES) {
            locations.put(attr.name(), location++);
        }
        return Map.copyOf(locations);
    }

    /**
     * Validates a stride/offsets triple without a GPU.
     *
     * @param baseStride claimed base stride (must be {@value #BASE_STRIDE_BYTES})
     * @param stride claimed total stride (must be {@value #EXTENDED_STRIDE_BYTES})
     * @return problems found; empty when the layout matches this contract
     */
    public static List<String> validate(int baseStride, int stride) {
        java.util.List<String> problems = new java.util.ArrayList<>();
        if (baseStride != BASE_STRIDE_BYTES) {
            problems.add("terrain base stride is " + baseStride + "B, expected "
                    + BASE_STRIDE_BYTES + "B");
        }
        if (stride != EXTENDED_STRIDE_BYTES) {
            problems.add("terrain extended stride is " + stride + "B, expected "
                    + EXTENDED_STRIDE_BYTES + "B");
        }
        return List.copyOf(problems);
    }

    /** @return one-line summary for diagnostics */
    public static String describe() {
        return "terrain vertex base=" + BASE_STRIDE_BYTES + "B extended=" + EXTENDED_STRIDE_BYTES
                + "B attributes=" + EXTENDED_ATTRIBUTES.stream()
                .map(TerrainAttribute::name).toList();
    }

    /**
     * @param name attribute name (one of the five extended names)
     * @return byte offset from the vertex start, matching {@link #offsetOf(String)}
     */
    public static Map<String, Integer> offsets() {
        java.util.LinkedHashMap<String, Integer> offsets = new java.util.LinkedHashMap<>();
        for (TerrainAttribute attr : EXTENDED_ATTRIBUTES) {
            offsets.put(attr.name(), offsetOf(attr.name()));
        }
        return Collections.unmodifiableMap(offsets);
    }

    /**
     * Whether every required vertex input is served by the vanilla block fetch, without
     * any extended attribute.
     *
     * <p>Callers resolve legacy {@code vulkairis_*} names through
     * {@code VulkanModProgramBridge.LEGACY_ATTRIB_ALIASES} first (the bridge's
     * {@code chooseVertexFormat} does); this check covers vanilla names only.</p>
     *
     * @param requiredInputs vertex input names a pack shader declares (may be {@code null})
     * @return {@code true} when every name is a {@link #BLOCK_ATTRIBUTE_NAMES} entry
     *         (empty claims are vacuously covered)
     */
    public static boolean isCoveredByBlock(Collection<String> requiredInputs) {
        if (requiredInputs == null || requiredInputs.isEmpty()) {
            return true;
        }
        return BLOCK_ATTRIBUTE_NAMES.containsAll(requiredInputs);
    }

    /**
     * Evidence for why {@code DefaultVertexFormat.BLOCK} suffices on the Sodium 0.8 compact
     * path, and where the 68-byte extended stride applies instead.
     *
     * <p>The base 20 bytes are preserved verbatim and fetched through the block format's
     * {@code Position/Color/UV0/UV2/Normal} elements, so a pack shader that declares only
     * base inputs links without any extension. The five appended attributes (offsets
     * {@link #offsets()}, total {@value #EXTENDED_STRIDE_BYTES} bytes) are only read when
     * a pack's transformed shaders declare the corresponding extended inputs; inputs
     * beyond the block fetch fail the link with an exact reason instead of drawing with a
     * short vertex. The layout is settled once (see {@link #settle(String)}).</p>
     *
     * @return single-line evidence for diagnostics and fallback reasons
     */
    public static String describeBlockMapping() {
        return "terrain vertex base=" + BASE_STRIDE_BYTES + "B via DefaultVertexFormat.BLOCK "
                + BLOCK_ATTRIBUTE_NAMES + "; extended=" + EXTENDED_STRIDE_BYTES + "B offsets="
                + offsets() + " read only when the pack declares extended inputs;"
                + " uncovered inputs fail link with reason; settled by '" + settledBy() + "'";
    }
}
