package com.vulkairis.test;

import com.vulkairis.translation.ShaderReflection;
import com.vulkairis.vulkan.descriptor.DescriptorContractValidator;
import com.vulkairis.vulkan.descriptor.DescriptorSetConvention;
import com.vulkairis.vulkan.descriptor.DescriptorSetLayoutBuilder;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_FRAGMENT_BIT;

/**
 * Device-free verification of the Phase 10 descriptor-set layout generation: reflection-to-plan
 * mapping with set conventions, grouping, and validation against the provided bindings.
 */
public class DescriptorSetLayoutTest {

    private static ShaderReflection reflection() {
        ShaderReflection reflection = new ShaderReflection();
        reflection.addUniformBlock("VulkairisUniformBlock", 0, 100, 0, 80, List.of(
                new ShaderReflection.UniformInfo("projection", "mat4", 0, 64),
                new ShaderReflection.UniformInfo("fogColor", "vec4", 64, 16)));
        reflection.addSampler("colortex0", 0, 0);
        reflection.addSampler("shadowtex0", 1, 1);
        reflection.addSampler("tex", 4, 4, 4); // arrayed sampler
        return reflection;
    }

    @Test
    public void testPlanMapsBlocksToFrameSetAndSamplersToPassSet() {
        List<DescriptorSetLayoutBuilder.DescriptorBinding> plan =
                DescriptorSetLayoutBuilder.forGraphics(reflection(), VK_SHADER_STAGE_FRAGMENT_BIT);

        Assertions.assertEquals(4, plan.size());

        DescriptorSetLayoutBuilder.DescriptorBinding block = plan.stream()
                .filter(b -> b.name().equals("VulkairisUniformBlock")).findFirst().orElseThrow();
        Assertions.assertEquals(DescriptorSetConvention.FRAME.set(), block.set());
        Assertions.assertEquals(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, block.descriptorType());

        DescriptorSetLayoutBuilder.DescriptorBinding sampler = plan.stream()
                .filter(b -> b.name().equals("colortex0")).findFirst().orElseThrow();
        Assertions.assertEquals(DescriptorSetConvention.PASS.set(), sampler.set());
        Assertions.assertEquals(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, sampler.descriptorType());
    }

    @Test
    public void testArrayedSamplerKeepsCount() {
        List<DescriptorSetLayoutBuilder.DescriptorBinding> plan =
                DescriptorSetLayoutBuilder.forGraphics(reflection(), VK_SHADER_STAGE_FRAGMENT_BIT);
        DescriptorSetLayoutBuilder.DescriptorBinding arrayed = plan.stream()
                .filter(b -> b.name().equals("tex")).findFirst().orElseThrow();
        Assertions.assertEquals(4, arrayed.descriptorCount());
    }

    @Test
    public void testGroupingBySet() {
        List<DescriptorSetLayoutBuilder.DescriptorBinding> plan =
                DescriptorSetLayoutBuilder.forGraphics(reflection(), VK_SHADER_STAGE_FRAGMENT_BIT);
        Map<Integer, List<DescriptorSetLayoutBuilder.DescriptorBinding>> grouped =
                DescriptorSetLayoutBuilder.groupBySet(plan);

        Assertions.assertEquals(2, grouped.size());
        Assertions.assertEquals(1, grouped.get(DescriptorSetConvention.FRAME.set()).size());
        Assertions.assertEquals(3, grouped.get(DescriptorSetConvention.PASS.set()).size());
    }

    @Test
    public void testPlanValidatesAgainstItself() {
        List<DescriptorSetLayoutBuilder.DescriptorBinding> plan =
                DescriptorSetLayoutBuilder.forGraphics(reflection(), VK_SHADER_STAGE_FRAGMENT_BIT);
        List<String> problems = DescriptorSetLayoutBuilder.validate(
                plan, DescriptorSetLayoutBuilder.toSpecs(plan), "composite");
        Assertions.assertTrue(problems.isEmpty(), problems.toString());
    }

    @Test
    public void testPlanRejectsWrongDescriptorType() {
        List<DescriptorSetLayoutBuilder.DescriptorBinding> plan =
                DescriptorSetLayoutBuilder.forGraphics(reflection(), VK_SHADER_STAGE_FRAGMENT_BIT);
        List<DescriptorContractValidator.BindingSpec> wrong = List.of(
                new DescriptorContractValidator.BindingSpec(
                        0, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1, VK_SHADER_STAGE_FRAGMENT_BIT));

        List<String> problems = DescriptorSetLayoutBuilder.validate(plan, wrong, "composite");
        Assertions.assertFalse(problems.isEmpty(), "a sampler binding provided as a UBO must fail");
        Assertions.assertTrue(problems.stream().anyMatch(p -> p.contains("composite")), problems.toString());
    }
}
