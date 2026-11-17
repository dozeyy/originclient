package com.vulkairis.vulkan.descriptor;

import java.util.List;
import java.util.Map;

/**
 * Generates a pass's descriptor bindings from its SPIR-V reflection plus the set conventions.
 *
 * <p>Reflection already reports which samplers and uniform blocks a shader actually uses; this maps
 * them into the {@link DescriptorSetConvention} sets and produces binding specs that can be validated
 * against what the descriptor manager created, before any descriptor write or draw.</p>
 */
public final class DescriptorSetLayoutBuilder {

    /**
     * A planned descriptor binding.
     *
     * @param set             descriptor set number
     * @param binding         binding number within the set
     * @param descriptorType  a {@code VK_DESCRIPTOR_TYPE_*} value
     * @param descriptorCount array element count (&gt;= 1)
     * @param stageFlags      a bitmask of {@code VK_SHADER_STAGE_*} bits
     * @param name            the reflected resource name, for diagnostics
     */
    public record DescriptorBinding(
            int set, int binding, int descriptorType, int descriptorCount, long stageFlags, String name) {

        /** @return the validator-facing spec for this binding */
        public DescriptorContractValidator.BindingSpec toSpec() {
            return new DescriptorContractValidator.BindingSpec(
                    binding, descriptorType, descriptorCount, stageFlags);
        }

        @Override
        public String toString() {
            return "set " + set + " binding " + binding + " " + name
                    + " x" + descriptorCount;
        }
    }

    private DescriptorSetLayoutBuilder() {
    }

    /**
     * Builds the descriptor plan for a graphics pass from its reflection.
     *
     * @param reflection   the pass's reflected shader metadata
     * @param stageFlags   shader stage bits that sample/consume the resources
     * @return the planned bindings (uniform blocks in the FRAME set, samplers in the PASS set)
     */
    public static List<DescriptorBinding> forGraphics(com.vulkairis.translation.ShaderReflection reflection,
                                                      long stageFlags) {
        java.util.List<DescriptorBinding> bindings = new java.util.ArrayList<>();

        reflection.getUniformBlocks().forEach(block -> bindings.add(new DescriptorBinding(
                DescriptorSetConvention.FRAME.set(),
                block.binding(),
                DescriptorSetConvention.uniformBufferType(),
                1,
                stageFlags,
                block.name())));

        reflection.getSamplers().values().forEach(sampler -> bindings.add(new DescriptorBinding(
                DescriptorSetConvention.PASS.set(),
                sampler.binding(),
                DescriptorSetConvention.combinedImageSamplerType(),
                Math.max(1, sampler.descriptorCount()),
                stageFlags,
                sampler.name())));

        return List.copyOf(bindings);
    }

    /**
     * Groups a plan by descriptor set number.
     *
     * @param bindings the flat plan
     * @return bindings grouped by set, preserving order
     */
    public static Map<Integer, List<DescriptorBinding>> groupBySet(List<DescriptorBinding> bindings) {
        Map<Integer, List<DescriptorBinding>> grouped = new java.util.LinkedHashMap<>();
        for (DescriptorBinding binding : bindings) {
            grouped.computeIfAbsent(binding.set(), ignored -> new java.util.ArrayList<>()).add(binding);
        }
        return grouped;
    }

    /**
     * Converts a plan to validator specs.
     *
     * @param bindings the plan
     * @return one validator spec per binding
     */
    public static List<DescriptorContractValidator.BindingSpec> toSpecs(List<DescriptorBinding> bindings) {
        java.util.List<DescriptorContractValidator.BindingSpec> specs = new java.util.ArrayList<>();
        for (DescriptorBinding binding : bindings) {
            specs.add(binding.toSpec());
        }
        return specs;
    }

    /**
     * Validates a plan against the bindings actually provided by the descriptor manager.
     *
     * @param required the planned bindings
     * @param provided the bindings the manager created
     * @param passName pass name for the failure message
     * @return an empty list when satisfied, otherwise one message per problem
     */
    public static List<String> validate(
            List<DescriptorBinding> required,
            List<DescriptorContractValidator.BindingSpec> provided,
            String passName) {
        return DescriptorContractValidator.validate(toSpecs(required), provided, passName);
    }
}
