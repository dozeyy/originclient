package com.vulkairis.rendering.exec;

import com.vulkairis.bridge.VulkanModProgramBridge;
import com.vulkairis.bridge.VulkanModTerrainHook;
import com.vulkairis.iris.ShaderPackProgram;
import com.vulkairis.iris.ShaderPackProgramCatalog;
import com.vulkairis.translation.ShaderReflection;
import com.vulkairis.vulkan.descriptor.DescriptorContractValidator;
import com.vulkairis.vulkan.pipeline.TerrainPipelineDoor;
import net.irisshaders.iris.shaderpack.loading.ProgramGroup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The incremental pass gates (Phase 11): which render-stage families exist, which the pack
 * needs, and which are actually implemented.
 *
 * <p>Order follows the plan's small gates: a known fullscreen composition first, then terrain,
 * opaque entities, shadows, translucency, deferred/composite work, and finally the final pass
 * with its HUD boundary. A gate that is required but not implemented is reported, never
 * silently skipped — a pack is not "supported" when its expected families never ran.</p>
 */
public final class PassGates {

    /** Render-stage families in plan gate order. */
    public enum Gate {
        FULLSCREEN_COMPOSITION,
        TERRAIN,
        OPAQUE_ENTITIES,
        SHADOW,
        TRANSLUCENT,
        DEFERRED_COMPOSITE,
        FINAL_HUD
    }

    /** Implementation state of one gate. */
    public enum Status {
        OPEN,
        PARTIAL,
        CLOSED
    }

    /** One gate with its implementation state and the reason. */
    public record GateStatus(Gate gate, Status status, String note) {
    }

    private PassGates() {
    }

    /**
     * @return the honest implementation state of every gate. The controlled composition quad
     * exists behind an explicit toggle; the composite/final post chain executes when bound;
     * the terrain gate is evaluated live from bridge evidence (linked terrain pipeline plus a
     * valid descriptor contract opens it, anything else stays closed/partial with the exact
     * missing condition); remaining world-stage families have no execution path yet; the HUD
     * boundary is enforced by never intercepting GUI rendering.
     */
    public static Map<Gate, GateStatus> implemented() {
        Map<Gate, GateStatus> table = new EnumMap<>(Gate.class);
        table.put(Gate.FULLSCREEN_COMPOSITION, new GateStatus(Gate.FULLSCREEN_COMPOSITION,
                Status.OPEN, "controlled quad behind an explicit debug toggle"));
        table.put(Gate.TERRAIN, terrainGateStatus());
        table.put(Gate.OPAQUE_ENTITIES, new GateStatus(Gate.OPAQUE_ENTITIES,
                Status.CLOSED, "no entity execution path yet"));
        table.put(Gate.SHADOW, new GateStatus(Gate.SHADOW,
                Status.CLOSED, "shadow config exists but no shadow execution path yet"));
        table.put(Gate.TRANSLUCENT, new GateStatus(Gate.TRANSLUCENT,
                Status.CLOSED, "no translucent execution path yet"));
        table.put(Gate.DEFERRED_COMPOSITE, new GateStatus(Gate.DEFERRED_COMPOSITE,
                Status.PARTIAL, "composite/final chain executes when bound; deferred not executed"));
        table.put(Gate.FINAL_HUD, new GateStatus(Gate.FINAL_HUD,
                Status.PARTIAL, "final programs run inside the post chain; GUI stays uninterrupted"));
        return Collections.unmodifiableMap(table);
    }

    /**
     * Device-free terrain gate verdict from explicit bridge evidence. The gate opens only once a
     * terrain link is registered (a live linked virtual program id) <em>and</em> the pack
     * descriptor plan validates against the provided bindings. Every other outcome names the
     * exact missing condition instead of implying terrain works: unlinked stays CLOSED (the
     * runClient {@code no terrain execution path yet} case, now naming the program and the
     * zero virtual id), linked-but-unproven stays PARTIAL with the first descriptor problem.
     * Callers keep the honest fallback (vanilla path plus the recorded reason); a pass is never
     * silently claimed as pack-rendered.
     *
     * @param programName pack terrain program the verdict is for (blank means
     *        {@code gbuffers_terrain})
     * @param linkedVirtualProgramId linked VulkanMod virtual program id, or {@code <= 0} when none
     * @param reflection reflected pack shader metadata (may be {@code null})
     * @param provided bindings the descriptor layer actually provides (may be {@code null})
     * @param stageFlags shader stage bits consuming the resources
     * @return the terrain gate status carrying the verdict and its exact reason
     */
    public static GateStatus terrainStatusFor(
            String programName,
            int linkedVirtualProgramId,
            ShaderReflection reflection,
            List<DescriptorContractValidator.BindingSpec> provided,
            long stageFlags) {
        String program = (programName == null || programName.isBlank())
                ? "gbuffers_terrain" : programName;
        if (linkedVirtualProgramId <= 0) {
            return new GateStatus(Gate.TERRAIN, Status.CLOSED,
                    "no terrain execution path yet: program '" + program + "' has no linked"
                            + " VulkanMod pipeline (virtual id " + linkedVirtualProgramId
                            + "); using vanilla fallback");
        }
        List<String> problems = TerrainPipelineDoor.checkDescriptorSwap(
                reflection, stageFlags, provided, program);
        if (problems.isEmpty()) {
            return new GateStatus(Gate.TERRAIN, Status.OPEN,
                    "terrain execution path live: program '" + program + "' linked (virtual id "
                            + linkedVirtualProgramId + ") with valid descriptors");
        }
        return new GateStatus(Gate.TERRAIN, Status.PARTIAL,
                "terrain program '" + program + "' linked (virtual id " + linkedVirtualProgramId
                        + ") but descriptor contract unmet: " + problems.get(0)
                        + "; using vanilla fallback");
    }

    /**
     * Live terrain gate wiring: reads the registered terrain link plus its descriptor evidence
     * from the bridge (production registration lands in
     * {@code VulkanModProgramBridge.noteTerrainProgram} right after a successful link) and
     * reduces it through {@link #terrainStatusFor}. Never throws into readiness evaluation: an
     * unevaluated gate reports CLOSED with the cause instead of opening blind.
     */
    private static GateStatus terrainGateStatus() {
        try {
            String program = TerrainPipelineDoor.programNameFor(TerrainPipelineDoor.TerrainPass.TERRAIN);
            int linked = VulkanModProgramBridge.terrainLinkedId(program);
            // Lazy link: if no terrain pipeline is linked yet but the device is ready, try to
            // link any registered (but unlinked) terrain program now. Registration happens during
            // pack discovery, which can run before the Vulkan device is live.
            if (linked <= 0) {
                try {
                    if (com.vulkairis.backend.vulkanmod.VulkanBackendAdapter.isLive(
                            com.vulkairis.backend.vulkanmod.VulkanBackendAdapter.getDevice())) {
                        com.vulkairis.translation.VulkairisProgram candidate =
                                com.vulkairis.translation.VulkanShaderRegistry.findProgramByName(program);
                        if (candidate != null) {
                            boolean ok = com.vulkairis.bridge.VulkanModProgramBridge.link(candidate);
                            if (ok) {
                                com.vulkairis.bridge.VulkanModProgramBridge.noteTerrainProgram(program,
                                        candidate.getVirtualProgramId());
                                linked = com.vulkairis.bridge.VulkanModProgramBridge.terrainLinkedId(program);
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            if (linked <= 0) {
                return terrainStatusFor(program, linked, null, null,
                        VulkanModProgramBridge.TERRAIN_STAGE_FLAGS);
            }
            if (!VulkanModTerrainHook.isBound()) {
                return new GateStatus(Gate.TERRAIN, Status.CLOSED,
                        "no terrain execution path yet: VulkanModTerrainHook not bound; using vanilla fallback");
            }
            ShaderReflection reflection = VulkanModProgramBridge.terrainReflectionFor(program);
            List<DescriptorContractValidator.BindingSpec> provided =
                    VulkanModProgramBridge.terrainProvidedFor(
                            linked, reflection, VulkanModProgramBridge.TERRAIN_STAGE_FLAGS);
            return terrainStatusFor(program, linked, reflection, provided,
                    VulkanModProgramBridge.TERRAIN_STAGE_FLAGS);
        } catch (Throwable t) {
            return new GateStatus(Gate.TERRAIN, Status.CLOSED,
                    "no terrain execution path yet: terrain gate unevaluated ("
                            + String.valueOf(t) + "); using vanilla fallback");
        }
    }


    /**
     * Maps a discovered catalog to the gates it needs, from Iris's own program groups and
     * program names (never Vitrail seam names).
     */
    public static Set<Gate> requiredFor(ShaderPackProgramCatalog catalog) {
        if (catalog == null || catalog.isEmpty()) {
            return Collections.emptySet();
        }
        Set<Gate> required = EnumSet.noneOf(Gate.class);
        for (ShaderPackProgram program : catalog.getPrograms()) {
            if (program.isCompute()) {
                continue;
            }
            // DH degrade pass: skipped distant programs require nothing.
            if (!com.vulkairis.compatibility.DistantHorizonsSupport.isAvailable()
                    && com.vulkairis.compatibility.DistantHorizonsSupport.isDistantProgram(program)) {
                continue;
            }
            ProgramGroup group = program.group();
            switch (group) {
                case Shadow, ShadowComposite -> required.add(Gate.SHADOW);
                case Gbuffers -> {
                    required.add(Gate.TERRAIN);
                    required.add(Gate.OPAQUE_ENTITIES);
                    String name = program.name().toLowerCase();
                    if (name.contains("water") || name.contains("translucent")) {
                        required.add(Gate.TRANSLUCENT);
                    }
                    if (name.contains("hand")) {
                        required.add(Gate.FINAL_HUD);
                    }
                }
                case Deferred, Composite -> required.add(Gate.DEFERRED_COMPOSITE);
                case Final -> {
                    required.add(Gate.DEFERRED_COMPOSITE);
                    required.add(Gate.FINAL_HUD);
                }
                case Setup, Begin, Prepare, Dh -> required.add(Gate.TERRAIN);
            }
        }
        return Collections.unmodifiableSet(required);
    }

    /** @return the required gates with their implementation states, in gate order */
    public static List<GateStatus> evaluate(ShaderPackProgramCatalog catalog) {
        Map<Gate, GateStatus> table = implemented();
        Set<Gate> required = requiredFor(catalog);
        List<GateStatus> result = new ArrayList<>();
        for (Gate gate : Gate.values()) {
            if (required.contains(gate)) {
                result.add(table.get(gate));
            }
        }
        return Collections.unmodifiableList(result);
    }
}
