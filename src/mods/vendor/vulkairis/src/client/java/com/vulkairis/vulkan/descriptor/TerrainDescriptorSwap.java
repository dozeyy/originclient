package com.vulkairis.vulkan.descriptor;

import com.vulkairis.translation.ShaderReflection;
import com.vulkairis.vulkan.descriptor.DescriptorContractValidator.BindingSpec;

import java.util.List;

/**
 * Phase D render-path swap: validates that the handed terrain pass can be replaced by the
 * pack descriptor before any descriptor write or draw.
 *
 * <p>Semantics: build the pack plan from SPIR-V reflection via
 * {@link DescriptorSetLayoutBuilder} (uniform blocks in the FRAME set, samplers in the PASS
 * set), then validate it against what the descriptor layer provides. The GPU bind itself
 * stays behind {@code VulkanModProgramBridge} at runtime; this class only answers the
 * device-free question "is the swap safe, and if not, why".</p>
 */
public final class TerrainDescriptorSwap {

    /** Swap outcome: safe to bind the pack descriptor, or keep the handed pass with a reason. */
    public record SwapDecision(boolean usePackDescriptor, List<String> problems, String fallbackReason) {
        public static SwapDecision pack(List<String> problems) {
            return new SwapDecision(true, List.copyOf(problems), "");
        }

        public static SwapDecision keep(String reason, List<String> problems) {
            return new SwapDecision(false, List.copyOf(problems),
                    reason == null || reason.isBlank() ? "pack descriptor not satisfied" : reason);
        }
    }

    private TerrainDescriptorSwap() {
    }

    /**
     * Plans the pack descriptor from reflection and validates it against the provided bindings.
     *
     * @param reflection reflected pack shader metadata (may be {@code null})
     * @param stageFlags stage bits consuming the resources
     * @param provided bindings the descriptor layer provides (may be {@code null})
     * @param passName pass label for diagnostics
     * @return swap decision; {@code usePackDescriptor} only when validation passes, or when the
     *         plan is empty (a pack program with no descriptors needs no swap)
     */
    public static SwapDecision decide(ShaderReflection reflection, long stageFlags,
                                      List<BindingSpec> provided, String passName) {
        String pass = (passName == null || passName.isBlank()) ? "terrain" : passName;
        if (reflection == null) {
            return SwapDecision.keep("[" + pass + "] pack descriptor swap refused:"
                    + " no reflection (terrain program not reflected)",
                    List.of("[" + pass + "] binding 0: expected pack descriptor, actual missing"));
        }
        List<DescriptorSetLayoutBuilder.DescriptorBinding> plan =
                DescriptorSetLayoutBuilder.forGraphics(reflection, stageFlags);
        List<BindingSpec> required = DescriptorSetLayoutBuilder.toSpecs(plan);
        if (required.isEmpty()) {
            return SwapDecision.pack(List.of());
        }
        List<String> problems = DescriptorSetLayoutBuilder.validate(plan, provided, pass);
        if (problems.isEmpty()) {
            return SwapDecision.pack(problems);
        }
        return SwapDecision.keep(problems.get(0), problems);
    }

    /**
     * Groups the pack plan by set for the runtime bind (FRAME set first, then PASS).
     *
     * @param reflection reflected pack metadata
     * @param stageFlags stage bits consuming the resources
     * @return plan grouped by set number; empty when reflection is {@code null}
     */
    public static java.util.Map<Integer, List<DescriptorSetLayoutBuilder.DescriptorBinding>> groupedPlan(
            ShaderReflection reflection, long stageFlags) {
        if (reflection == null) {
            return java.util.Map.of();
        }
        return DescriptorSetLayoutBuilder.groupBySet(
                DescriptorSetLayoutBuilder.forGraphics(reflection, stageFlags));
    }
}
