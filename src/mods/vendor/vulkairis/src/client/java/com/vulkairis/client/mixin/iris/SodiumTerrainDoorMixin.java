package com.vulkairis.client.mixin.iris;

import com.vulkairis.bridge.VulkanModProgramBridge;
import com.vulkairis.core.VulkairisController;
import com.vulkairis.diagnostics.StageDiagnostics;
import com.vulkairis.iris.IrisBridge;
import com.vulkairis.rendering.RenderStateTracker;
import com.vulkairis.translation.ShaderReflection;
import com.vulkairis.vulkan.descriptor.DescriptorContractValidator;
import com.vulkairis.vulkan.pipeline.TerrainPipelineDoor;
import net.caffeinemc.mods.sodium.client.gl.shader.GlProgram;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkShaderInterface;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.pipeline.programs.SodiumPrograms;
import net.irisshaders.iris.shadows.ShadowRenderingState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Phase D terrain door around the frozen {@code SodiumPrograms} path.
 *
 * <p>Semantics (narrow wraps only, no {@code @Overwrite} here): the frozen
 * {@code SodiumProgramsMixin} signatures stay untouched. This mixin adds
 * {@code @Inject}-based behavior around them:</p>
 *
 * <ul>
 *   <li>{@code getProgram} HEAD (cancellable): resolves the handed pass through
 *       {@code TerrainPipelineDoor} and {@code VulkanModProgramBridge}. When a pack
 *       pipeline is linked, the decision is recorded (pack pass/target) and the GL path
 *       is left to return {@code null} (Vulkan owns drawing via the bridge). When no
 *       pipeline is linked and a pack is active, an exact fallback reason is recorded
 *       instead of silently drawing vanilla. The decision runs the descriptor gate
 *       ({@code decideRenderLenient} over the merged pack reflection and the linked
 *       pipeline's descriptors): descriptors refuse the pack path with an exact reason
 *       when they disagree, and the skip is recorded when gate inputs are unknown.</li>
 *   <li>{@code getFramebuffer} RETURN: swaps the handed pass label for the pack target
 *       label on the controller so diagnostics show the effective pack target. The same
 *       gated decision runs here so a descriptor refusal is recorded (silently, this is
 *       a frame-rate path) instead of relabeling an unvalidated target.</li>
 * </ul>
 *
 * <p>Per-frame pack pass/target evidence lives on {@code VulkairisController}
 * ({@code activePass}/{@code currentTarget}) plus the {@code StageDiagnostics} lines,
 * and is mirrored into {@code RenderStateTracker} (see the plan's §6.5/§8 acceptance
 * wording).</p>
 *
 * <p>Shadow draw stays one frame late by design (see {@code ShadowFrameLag}): nothing here
 * attempts same-frame shadow publication.</p>
 */
@Mixin(value = SodiumPrograms.class, remap = false)
public abstract class SodiumTerrainDoorMixin {

    @Unique
    private static volatile String vulkairis$lastDoorReason = "";

    /**
     * Head-of-compile terrain decision. Cancellable so a future pack-owned path can return
     * early; today it records the decision and lets the (null-returning) GL path proceed,
     * with the real draw owned by the Vulkan bridge.
     */
    @Inject(method = "getProgram", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void vulkairis$terrainCompileHead(TerrainRenderPass pass,
                                              CallbackInfoReturnable<GlProgram<ChunkShaderInterface>> cir) {
        try {
            String irisName = pass == null ? "TERRAIN" : pass.toString();
            TerrainPipelineDoor.TerrainPass doorPass =
                    TerrainPipelineDoor.forPassName(simplePassName(pass));
            com.vulkairis.rendering.backend.vulkanmod.v1211.VulkanModRenderStage stage =
                    switch (doorPass) {
                        case TRANSLUCENT, SHADOW_TRANS ->
                                com.vulkairis.rendering.backend.vulkanmod.v1211.VulkanModRenderStage.TRANSLUCENT_TERRAIN;
                        case TERRAIN_CUTOUT, SHADOW_CUTOUT ->
                                com.vulkairis.rendering.backend.vulkanmod.v1211.VulkanModRenderStage.CUTOUT_TERRAIN;
                        case SHADOW ->
                                com.vulkairis.rendering.backend.vulkanmod.v1211.VulkanModRenderStage.SHADOW_PASS;
                        case TERRAIN ->
                                com.vulkairis.rendering.backend.vulkanmod.v1211.VulkanModRenderStage.OPAQUE_TERRAIN;
                    };
            com.vulkairis.rendering.backend.vulkanmod.v1211.VulkanModLifecycleAdapter.getInstance()
                    .onStageObserved(stage, "SodiumPrograms.getProgram: " + irisName);

            String program = TerrainPipelineDoor.programNameFor(doorPass);
            TerrainPipelineDoor.DoorDecision decision = gatedDecision(doorPass, program);

            VulkairisController controller = VulkairisController.getInstance();
            controller.setActivePass(decision.passLabel());
            controller.setCurrentTarget(decision.targetLabel());
            RenderStateTracker.getInstance()
                    .observePackPass(decision.passLabel(), decision.targetLabel());
            vulkairis$lastDoorReason = decision.fallbackReason();

            if (decision.usePackPipeline()) {
                StageDiagnostics.pass("Terrain door",
                        "pack program '" + program + "' bound for pass " + irisName
                                + gateNote(program));
                // The Vulkan bridge owns the draw; the GL program stays null (frozen overwrite).
                return;
            }
            if (isPackActive()) {
                StageDiagnostics.warn("Terrain door", decision.fallbackReason());
                controller.setLastLinkError("terrain:" + program + ": " + decision.fallbackReason());
            }
        } catch (Throwable t) {
            StageDiagnostics.warn("Terrain door",
                    "door decision failed: " + (t.getMessage() != null ? t.getMessage() : t.toString()));
        }
    }

    /**
     * Render-path label swap: report the effective pack target for the handed pass.
     * Runs the same gated decision so a descriptor refusal behind the handed pass is
     * recorded instead of relabeling an unvalidated target. Frame-rate path: records
     * controller/tracker state and the last-link error, but never logs per call.
     */
    @Inject(method = "getFramebuffer", at = @At("RETURN"), remap = false, require = 0)
    private void vulkairis$terrainFramebufferReturn(TerrainRenderPass pass,
                                                    CallbackInfoReturnable<GlFramebuffer> cir) {
        try {
            TerrainPipelineDoor.TerrainPass doorPass =
                    TerrainPipelineDoor.forPassName(simplePassName(pass));
            String program = TerrainPipelineDoor.programNameFor(doorPass);
            TerrainPipelineDoor.DoorDecision decision = gatedDecision(doorPass, program);
            VulkairisController controller = VulkairisController.getInstance();
            controller.setCurrentTarget(decision.targetLabel());
            RenderStateTracker.getInstance()
                    .observePackPass(decision.passLabel(), decision.targetLabel());
            vulkairis$lastDoorReason = decision.fallbackReason();
            if (!decision.usePackPipeline() && isPackActive()) {
                controller.setLastLinkError("terrain:" + program + ": " + decision.fallbackReason());
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Gated render-path decision for one handed pass: compile linkage first, then the
     * descriptor gate over the merged pack reflection and the linked pipeline's
     * descriptors. Either unknown gate input keeps the compile verdict (the skip is
     * reported by {@link #gateNote(String)} at the logging site).
     */
    @Unique
    private static TerrainPipelineDoor.DoorDecision gatedDecision(
            TerrainPipelineDoor.TerrainPass doorPass, String program) {
        int linked = VulkanModProgramBridge.terrainLinkedId(program);
        ShaderReflection reflection = VulkanModProgramBridge.terrainReflectionFor(program);
        java.util.List<DescriptorContractValidator.BindingSpec> provided =
                VulkanModProgramBridge.terrainProvidedFor(
                        linked, reflection, VulkanModProgramBridge.TERRAIN_STAGE_FLAGS);
        return TerrainPipelineDoor.decideRenderLenient(doorPass, linked, reflection,
                VulkanModProgramBridge.TERRAIN_STAGE_FLAGS, provided);
    }

    /**
     * Appends the descriptor-gate outcome for a pack decision: validated when the gate
     * ran, skipped with the exact skip reason when its inputs were unknown.
     */
    @Unique
    private static String gateNote(String program) {
        try {
            ShaderReflection reflection = VulkanModProgramBridge.terrainReflectionFor(program);
            int linked = VulkanModProgramBridge.terrainLinkedId(program);
            java.util.List<DescriptorContractValidator.BindingSpec> provided =
                    VulkanModProgramBridge.terrainProvidedFor(
                            linked, reflection, VulkanModProgramBridge.TERRAIN_STAGE_FLAGS);
            String skip = TerrainPipelineDoor.descriptorGateSkipReason(reflection, provided);
            return skip.isEmpty() ? " (descriptor swap validated)" : " (" + skip + ")";
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * Resolves the handed pass to a door pass name. Mirrors Iris
     * {@code SodiumPrograms.mapTerrainRenderPass} behaviorally (not copied): identity
     * against the {@code DefaultTerrainRenderPasses} singletons selects
     * solid/cutout/translucent, and the shadow bit comes from
     * {@code ShadowRenderingState}. Unknown or custom passes fall back to the
     * behavior flags ({@code isTranslucent}, then {@code supportsFragmentDiscard}),
     * so every door pass — including CUTOUT and all three SHADOW variants — is
     * reachable. Never throws.
     */
    @Unique
    private static String simplePassName(TerrainRenderPass pass) {
        boolean shadow = isShadowRendering();
        if (pass != null) {
            try {
                if (pass == DefaultTerrainRenderPasses.SOLID) {
                    return shadow ? "SHADOW" : "TERRAIN";
                }
                if (pass == DefaultTerrainRenderPasses.CUTOUT) {
                    return shadow ? "SHADOW_CUTOUT" : "TERRAIN_CUTOUT";
                }
                if (pass == DefaultTerrainRenderPasses.TRANSLUCENT) {
                    return shadow ? "SHADOW_TRANS" : "TRANSLUCENT";
                }
            } catch (Throwable ignored) {
            }
            try {
                boolean translucent = pass.isTranslucent();
                boolean fragmentDiscard = false;
                try {
                    fragmentDiscard = pass.supportsFragmentDiscard();
                } catch (Throwable ignored) {
                }
                return TerrainPipelineDoor
                        .resolveHandedPass(translucent, fragmentDiscard, shadow).name();
            } catch (Throwable ignored) {
            }
        }
        return shadow ? "SHADOW" : "TERRAIN";
    }

    @Unique
    private static boolean isShadowRendering() {
        try {
            return ShadowRenderingState.areShadowsCurrentlyBeingRendered();
        } catch (Throwable t) {
            return false;
        }
    }

    @Unique
    private static boolean isPackActive() {
        try {
            String pack = IrisBridge.getCurrentPackName();
            return pack != null && !pack.isBlank()
                    && !pack.equals("None") && !pack.startsWith("None (");
        } catch (Throwable t) {
            return false;
        }
    }
}
