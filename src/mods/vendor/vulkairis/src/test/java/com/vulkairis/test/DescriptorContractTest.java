package com.vulkairis.test;

import com.vulkairis.vulkan.descriptor.DescriptorContractValidator;
import com.vulkairis.vulkan.descriptor.DescriptorContractValidator.BindingSpec;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Device-free tests for the authoritative descriptor contract validator (phase 11). These run
 * without a live Vulkan device and fail if the validator stops distinguishing missing bindings,
 * descriptor-type mismatches, and descriptor-count mismatches, or stops naming the pass/binding
 * and types in its diagnostics.
 */
public class DescriptorContractTest {

    // Standard Vulkan numeric values, used directly so the test never initialises LWJGL natives.
    private static final int COMBINED_IMAGE_SAMPLER = 1;
    private static final int UNIFORM_BUFFER = 6;
    private static final int STORAGE_BUFFER = 7;
    private static final long STAGE_FRAGMENT = 0x10L;
    private static final long STAGE_VERTEX = 0x1L;

    private static BindingSpec spec(int binding, int type, int count) {
        return new BindingSpec(binding, type, count, STAGE_FRAGMENT);
    }

    @Test
    public void testCompatibleSpecsProduceNoProblems() {
        List<BindingSpec> required = List.of(
                new BindingSpec(0, COMBINED_IMAGE_SAMPLER, 1, STAGE_FRAGMENT),
                new BindingSpec(2, UNIFORM_BUFFER, 1, STAGE_VERTEX));

        List<BindingSpec> provided = List.of(
                new BindingSpec(0, COMBINED_IMAGE_SAMPLER, 1, STAGE_FRAGMENT),
                new BindingSpec(2, UNIFORM_BUFFER, 1, STAGE_VERTEX));

        List<String> problems = DescriptorContractValidator.validate(required, provided);
        Assertions.assertTrue(problems.isEmpty(),
                "Compatible descriptor contracts must validate cleanly, got: " + problems);
    }

    @Test
    public void testLargerProvidedCountCoversRequiredCount() {
        List<BindingSpec> required = List.of(spec(0, COMBINED_IMAGE_SAMPLER, 2));
        List<BindingSpec> provided = List.of(spec(0, COMBINED_IMAGE_SAMPLER, 4));

        Assertions.assertTrue(DescriptorContractValidator.validate(required, provided).isEmpty(),
                "A provided array must cover a smaller required count");
    }

    @Test
    public void testMissingBindingIsReported() {
        List<BindingSpec> required = List.of(spec(2, COMBINED_IMAGE_SAMPLER, 1));
        List<BindingSpec> provided = List.of(spec(0, UNIFORM_BUFFER, 1));

        List<String> problems = DescriptorContractValidator.validate(required, provided, "Water pass");

        Assertions.assertFalse(problems.isEmpty(), "A missing binding must be reported");
        Assertions.assertTrue(problems.stream().anyMatch(p -> p.contains("Water pass")),
                "Problem must name the pass: " + problems);
        Assertions.assertTrue(problems.stream().anyMatch(p -> p.contains("binding 2")),
                "Problem must name the missing binding: " + problems);
    }

    @Test
    public void testTypeMismatchIsReported() {
        List<BindingSpec> required = List.of(spec(1, UNIFORM_BUFFER, 1));
        List<BindingSpec> provided = List.of(spec(1, COMBINED_IMAGE_SAMPLER, 1));

        List<String> problems = DescriptorContractValidator.validate(required, provided, "Shadow pass");

        Assertions.assertFalse(problems.isEmpty(), "A descriptor-type mismatch must be reported");
        String problem = problems.get(0);
        Assertions.assertTrue(problem.contains("Shadow pass"), "Problem must name the pass: " + problem);
        Assertions.assertTrue(problem.contains("binding 1"), "Problem must name the binding: " + problem);
        Assertions.assertTrue(problem.contains("VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER"),
                "Problem must name the expected type: " + problem);
        Assertions.assertTrue(problem.contains("VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER"),
                "Problem must name the actual type: " + problem);
    }

    @Test
    public void testCountMismatchIsReported() {
        List<BindingSpec> required = List.of(spec(0, STORAGE_BUFFER, 4));
        List<BindingSpec> provided = List.of(spec(0, STORAGE_BUFFER, 2));

        List<String> problems = DescriptorContractValidator.validate(required, provided, "Compute pass");

        Assertions.assertFalse(problems.isEmpty(), "A short descriptor array must be reported");
        String problem = problems.get(0);
        Assertions.assertTrue(problem.contains("binding 0"), "Problem must name the binding: " + problem);
        Assertions.assertTrue(problem.contains("x4"), "Problem must name the required count: " + problem);
        Assertions.assertTrue(problem.contains("x2"), "Problem must name the provided count: " + problem);
    }

    @Test
    public void testDescribeFailureIncludesPassBindingAndType() {
        String message = DescriptorContractValidator.describeFailure(
                "Hand pass", 3, "VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER",
                "VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER");

        Assertions.assertTrue(message.contains("Hand pass"), "Message must include the pass name: " + message);
        Assertions.assertTrue(message.contains("3"), "Message must include the binding number: " + message);
        Assertions.assertTrue(message.contains("VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER"),
                "Message must include the expected type: " + message);
        Assertions.assertTrue(message.contains("VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER"),
                "Message must include the actual type: " + message);
    }
}
