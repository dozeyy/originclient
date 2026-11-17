package com.vulkairis.vulkan.pipeline;

import com.vulkairis.translation.ShaderReflection;
import com.vulkairis.vulkan.descriptor.DescriptorContractValidator;
import com.vulkairis.vulkan.descriptor.DescriptorSetLayoutBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Phase D terrain door: device-free decisions around the frozen Sodium programs path.
 *
 * <p>Semantics: the fixed {@code SodiumPrograms} signatures stay untouched; narrow
 * {@code @Inject} behavior around them asks this door (a) which pack program a handed
 * terrain pass maps to, (b) whether the pack descriptor plan is satisfied, and (c) which
 * honest fallback reason to record when it is not. No GL calls, no native calls, no
 * swapchain access — the actual GPU bind stays behind {@code VulkanModProgramBridge} and
 * {@code VulkanPipelineFactory} at runtime.</p>
 *
 * <p>Per-frame pack pass/target evidence is recorded on {@code VulkairisController}
 * ({@code activePass}/{@code currentTarget}) plus {@code StageDiagnostics} lines, and
 * mirrored into {@code RenderStateTracker} (diagnostics mirror for the plan's §6.5/§8
 * acceptance wording).</p>
 */
public final class TerrainPipelineDoor {

    /** Terrain passes mirroring the Iris Sodium programs pass set (device-free). */
    public enum TerrainPass {
        TERRAIN,
        TERRAIN_CUTOUT,
        TRANSLUCENT,
        SHADOW,
        SHADOW_CUTOUT,
        SHADOW_TRANS
    }

    /** Door outcome: use the pack pipeline, or fall back with an exact reason. */
    public record DoorDecision(boolean usePackPipeline, String passLabel, String targetLabel,
                               String fallbackReason) {
        public static DoorDecision pack(String passLabel, String targetLabel) {
            return new DoorDecision(true, passLabel, targetLabel, "");
        }

        public static DoorDecision fallback(String passLabel, String targetLabel, String reason) {
            return new DoorDecision(false, passLabel, targetLabel,
                    reason == null || reason.isBlank() ? "unknown terrain door reason" : reason);
        }
    }

    private TerrainPipelineDoor() {
    }

    /**
     * Maps a handed Iris pass name to a door pass. Never throws: unknown names map to
     * {@code TERRAIN} with the original name preserved by the caller.
     *
     * @param irisPassName e.g. {@code "TERRAIN"}, {@code "SHADOW_CUTOUT"} (case-insensitive)
     * @return the matching door pass, or {@code TERRAIN} when unknown
     */
    public static TerrainPass forPassName(String irisPassName) {
        if (irisPassName == null) {
            return TerrainPass.TERRAIN;
        }
        try {
            return TerrainPass.valueOf(irisPassName.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return TerrainPass.TERRAIN;
        }
    }

    /**
     * Behavioral equivalent of Iris {@code SodiumPrograms.mapTerrainRenderPass} for
     * non-shadow rendering: translucent passes stay translucent, alpha-tested
     * (fragment-discard) passes are cutout, everything else is solid terrain.
     *
     * <p>Mirrors the identity mapping (SOLID/CUTOUT/TRANSLUCENT singletons) without
     * calling into Iris: callers pass the observed behavior flags.</p>
     *
     * @param translucent whether the handed pass blends (translucent family)
     * @param fragmentDiscard whether the handed pass alpha-tests (cutout family)
     * @return the matching non-shadow door pass
     */
    public static TerrainPass resolveHandedPass(boolean translucent, boolean fragmentDiscard) {
        if (translucent) {
            return TerrainPass.TRANSLUCENT;
        }
        if (fragmentDiscard) {
            return TerrainPass.TERRAIN_CUTOUT;
        }
        return TerrainPass.TERRAIN;
    }

    /**
     * Applies the shadow bit to a resolved pass, mirroring the shadow branch of Iris
     * {@code SodiumPrograms.mapTerrainRenderPass} ({@code ShadowRenderingState}).
     *
     * @param base the non-shadow pass (may be {@code null}, treated as {@code TERRAIN})
     * @param shadowActive whether shadows are currently being rendered
     * @return the shadow variant when active, otherwise the base pass
     */
    public static TerrainPass withShadow(TerrainPass base, boolean shadowActive) {
        TerrainPass safe = base == null ? TerrainPass.TERRAIN : base;
        if (!shadowActive) {
            return safe;
        }
        return switch (safe) {
            case TERRAIN -> TerrainPass.SHADOW;
            case TERRAIN_CUTOUT -> TerrainPass.SHADOW_CUTOUT;
            case TRANSLUCENT -> TerrainPass.SHADOW_TRANS;
            default -> safe;
        };
    }

    /**
     * Full handed-pass resolution: behavioral mapping plus the shadow bit.
     *
     * @param translucent whether the handed pass blends
     * @param fragmentDiscard whether the handed pass alpha-tests
     * @param shadowActive whether shadows are currently being rendered
     * @return the reachable door pass (all six variants are reachable)
     */
    public static TerrainPass resolveHandedPass(boolean translucent, boolean fragmentDiscard,
                                                boolean shadowActive) {
        return withShadow(resolveHandedPass(translucent, fragmentDiscard), shadowActive);
    }

    /** @return whether the pass is a cutout (alpha-tested) variant */
    public static boolean isCutout(TerrainPass pass) {
        return pass == TerrainPass.TERRAIN_CUTOUT || pass == TerrainPass.SHADOW_CUTOUT;
    }

    /** @return whether the pass is a shadow-map pass */
    public static boolean isShadow(TerrainPass pass) {
        return pass == TerrainPass.SHADOW || pass == TerrainPass.SHADOW_CUTOUT
                || pass == TerrainPass.SHADOW_TRANS;
    }

    /** @return whether the pass blends (translucent family) */
    public static boolean requiresBlend(TerrainPass pass) {
        return pass == TerrainPass.TRANSLUCENT || pass == TerrainPass.SHADOW_TRANS;
    }

    /** @return whether the pass writes depth (shadow and opaque terrain do; translucent does not) */
    public static boolean requiresDepthWrite(TerrainPass pass) {
        return pass != TerrainPass.TRANSLUCENT;
    }

    /**
     * Pack program label for a door pass (the gbuffers/shadow program identity the pipeline
     * factory keys on).
     */
    public static String programNameFor(TerrainPass pass) {
        if (pass == null) {
            return "gbuffers_terrain";
        }
        return switch (pass) {
            case TERRAIN -> "gbuffers_terrain";
            case TERRAIN_CUTOUT -> "gbuffers_terrain_cutout";
            case TRANSLUCENT -> "gbuffers_translucent";
            case SHADOW -> "shadow";
            case SHADOW_CUTOUT -> "shadow_cutout";
            case SHADOW_TRANS -> "shadow_translucent";
        };
    }

    /**
     * Render-target label the pass draws into (scene color for terrain, shadow depth for
     * shadow passes).
     */
    public static String targetFor(TerrainPass pass) {
        return isShadow(pass == null ? TerrainPass.TERRAIN : pass) ? "shadowtex0" : "colortex0";
    }

    /**
     * Head-of-compile decision: a cancellable {@code @Inject} asks here first. The pack path
     * owns the pass only when a virtual program id is already linked; otherwise the caller
     * records the returned fallback reason instead of silently drawing vanilla.
     *
     * @param pass the handed terrain pass
     * @param linkedVirtualProgramId linked VulkanMod virtual program id, or {@code <= 0} when none
     * @return pack decision when linked, fallback decision naming the missing pipeline otherwise
     */
    public static DoorDecision decideCompile(TerrainPass pass, int linkedVirtualProgramId) {
        TerrainPass safe = pass == null ? TerrainPass.TERRAIN : pass;
        String program = programNameFor(safe);
        String target = targetFor(safe);
        if (linkedVirtualProgramId <= 0) {
            return DoorDecision.fallback(program, target,
                    "terrain program '" + program + "' has no linked VulkanMod pipeline"
                            + " (virtual id " + linkedVirtualProgramId + "); using vanilla fallback");
        }
        return DoorDecision.pack(program, target);
    }

    /**
     * Render-path swap check: builds the pack descriptor plan from reflection and validates it
     * against the provided bindings. A non-empty problem list means the handed pass must stay
     * (with the first problem as the fallback reason); an empty list means the pack descriptor
     * swap is safe.
     *
     * @param reflection reflected pack shader metadata (may be {@code null})
     * @param stageFlags shader stage bits consuming the resources
     * @param provided bindings the descriptor layer actually provides (may be {@code null})
     * @param passName pass label for diagnostics
     * @return problems found; empty when the swap is safe
     */
    public static List<String> checkDescriptorSwap(ShaderReflection reflection, long stageFlags,
                                                   List<DescriptorContractValidator.BindingSpec> provided,
                                                   String passName) {
        if (reflection == null) {
            return List.of("[" + displayPass(passName) + "] pack descriptor swap refused:"
                    + " no reflection (terrain program not reflected)");
        }
        List<DescriptorSetLayoutBuilder.DescriptorBinding> plan =
                DescriptorSetLayoutBuilder.forGraphics(reflection, stageFlags);
        List<DescriptorContractValidator.BindingSpec> specs =
                DescriptorSetLayoutBuilder.toSpecs(plan);
        List<String> problems = new ArrayList<>(
                DescriptorContractValidator.validate(specs, provided, displayPass(passName)));
        return List.copyOf(problems);
    }

    /**
     * Full render-path decision combining compile linkage and descriptor validation.
     *
     * @param pass the handed terrain pass
     * @param linkedVirtualProgramId linked virtual program id, or {@code <= 0} when none
     * @param reflection reflected pack metadata (may be {@code null})
     * @param stageFlags stage bits consuming the resources
     * @param provided provided descriptor bindings (may be {@code null})
     * @return pack decision only when linked <em>and</em> the descriptor plan validates
     */
    public static DoorDecision decideRender(TerrainPass pass, int linkedVirtualProgramId,
                                            ShaderReflection reflection, long stageFlags,
                                            List<DescriptorContractValidator.BindingSpec> provided) {
        DoorDecision compile = decideCompile(pass, linkedVirtualProgramId);
        if (!compile.usePackPipeline()) {
            return compile;
        }
        List<String> problems = checkDescriptorSwap(reflection, stageFlags, provided,
                compile.passLabel());
        if (!problems.isEmpty()) {
            return DoorDecision.fallback(compile.passLabel(), compile.targetLabel(), problems.get(0));
        }
        return compile;
    }

    /**
     * Whether the descriptor gate can render a verdict at all. The gate runs only when
     * both the pack reflection and the layer-provided bindings are known; otherwise the
     * caller keeps the compile decision and records {@link #descriptorGateSkipReason}.
     *
     * @param reflection reflected pack shader metadata (may be {@code null})
     * @param provided bindings the descriptor layer provides (may be {@code null})
     * @return {@code true} when a strict render decision can be computed
     */
    public static boolean isDescriptorGateApplicable(ShaderReflection reflection,
                                                     List<DescriptorContractValidator.BindingSpec> provided) {
        return reflection != null && provided != null;
    }

    /**
     * Explains why the descriptor gate was skipped (empty when it was not skipped).
     *
     * @param reflection reflected pack shader metadata (may be {@code null})
     * @param provided bindings the descriptor layer provides (may be {@code null})
     * @return the skip reason, or {@code ""} when the gate is applicable
     */
    public static String descriptorGateSkipReason(ShaderReflection reflection,
                                                  List<DescriptorContractValidator.BindingSpec> provided) {
        if (reflection == null) {
            return "descriptor gate skipped: no reflection (terrain program not reflected)";
        }
        if (provided == null) {
            return "descriptor gate skipped: no provided descriptor bindings";
        }
        return "";
    }

    /**
     * Runtime render-path decision: the compile linkage dominates, then the descriptor
     * gate rules whenever it can. Unlike strict {@link #decideRender}, unknown gate
     * inputs (no reflection, no provided bindings) keep the compile verdict instead of
     * refusing the pack path: at label time the layer may simply not have reported yet,
     * and that absence is recorded via {@link #descriptorGateSkipReason}, not hidden.
     * An incomplete reflection is untrustworthy partial data and refuses the pack path
     * with an exact reason.
     *
     * @param pass the handed terrain pass
     * @param linkedVirtualProgramId linked virtual program id, or {@code <= 0} when none
     * @param reflection reflected pack metadata (may be {@code null})
     * @param stageFlags stage bits consuming the resources
     * @param provided provided descriptor bindings (may be {@code null})
     * @return pack decision only when linked, complete, and descriptor-validated
     */
    public static DoorDecision decideRenderLenient(TerrainPass pass, int linkedVirtualProgramId,
                                                   ShaderReflection reflection, long stageFlags,
                                                   List<DescriptorContractValidator.BindingSpec> provided) {
        DoorDecision compile = decideCompile(pass, linkedVirtualProgramId);
        if (!compile.usePackPipeline()) {
            return compile;
        }
        if (reflection == null || provided == null) {
            return compile;
        }
        if (!reflection.isComplete()) {
            return DoorDecision.fallback(compile.passLabel(), compile.targetLabel(),
                    "[" + displayPass(compile.passLabel()) + "] pack descriptor swap refused:"
                            + " reflection incomplete (terrain program not trustworthy)");
        }
        return decideRender(pass, linkedVirtualProgramId, reflection, stageFlags, provided);
    }

    /**
     * Formats a fallback reason naming requested vs effective backend, stage, and pack identity.
     * Used for the honest-fallback contract: every engagement records exactly why vanilla
     * rendering replaced the pack path.
     */
    public static String fallbackReason(String requestedBackend, String effectiveBackend,
                                        String stage, String packAndShaderId, String cause) {
        return "requested=" + orNone(requestedBackend)
                + " effective=" + orNone(effectiveBackend)
                + " stage=" + orNone(stage)
                + " pack+shader=" + orNone(packAndShaderId)
                + " cause=" + orNone(cause);
    }

    private static String displayPass(String passName) {
        return (passName == null || passName.isBlank()) ? "terrain" : passName;
    }

    private static String orNone(String value) {
        return (value == null || value.isBlank()) ? "none" : value;
    }

    /** Ensures the door enum covers every Iris sodium pass name (for tests). */
    public static List<String> knownPassNames() {
        List<String> names = new ArrayList<>();
        for (TerrainPass pass : TerrainPass.values()) {
            names.add(pass.name());
        }
        return List.copyOf(names);
    }

    /** @return whether a name maps to a known door pass without falling back to TERRAIN */
    public static boolean isKnownPassName(String name) {
        if (name == null) {
            return false;
        }
        try {
            TerrainPass.valueOf(name.trim().toUpperCase(java.util.Locale.ROOT));
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
