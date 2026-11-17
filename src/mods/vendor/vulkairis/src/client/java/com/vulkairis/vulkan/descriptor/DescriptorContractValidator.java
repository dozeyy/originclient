package com.vulkairis.vulkan.descriptor;

import java.util.ArrayList;
import java.util.List;

/**
 * Authoritative, device-free validation of Vulkan descriptor contracts.
 *
 * <p>Compares the bindings a shader/pass <em>requires</em> against the bindings the descriptor
 * manager actually <em>provided</em>. It performs no native calls, so it can be exercised by
 * plain JUnit tests and by the manager before descriptors are written.</p>
 *
 * <p>A pass is compatible when {@link #validate(List, List)} returns an empty list. Every problem
 * is a human-readable string produced by {@link #describeFailure(String, int, String, String)} so
 * failures always name the pass, binding number, expected type, and actual type.</p>
 */
public final class DescriptorContractValidator {

    /**
     * A single descriptor binding contract entry.
     *
     * @param binding         the binding number within the descriptor set
     * @param descriptorType  a {@code VK_DESCRIPTOR_TYPE_*} value
     * @param descriptorCount number of array elements; values {@code <= 0} mean one element
     * @param stageFlags      a bitmask of {@code VK_SHADER_STAGE_*} bits
     */
    public record BindingSpec(int binding, int descriptorType, int descriptorCount, long stageFlags) {
    }

    private DescriptorContractValidator() {
    }

    /**
     * Validates that {@code provided} satisfies every entry in {@code required}.
     *
     * @param required bindings the pass needs; may be {@code null} or empty
     * @param provided bindings the manager created; may be {@code null}
     * @return an empty list when compatible, otherwise one message per unsatisfied binding
     */
    public static List<String> validate(List<BindingSpec> required, List<BindingSpec> provided) {
        return validate(required, provided, "descriptor contract");
    }

    /**
     * Validates that {@code provided} satisfies every entry in {@code required}, attributing any
     * problem to {@code passName}.
     *
     * @param required bindings the pass needs; may be {@code null} or empty
     * @param provided bindings the manager created; may be {@code null}
     * @param passName pass/render-pass identifier included in every failure message
     * @return an empty list when compatible, otherwise one message per unsatisfied binding
     */
    public static List<String> validate(List<BindingSpec> required, List<BindingSpec> provided, String passName) {
        List<String> problems = new ArrayList<>();
        if (required == null || required.isEmpty()) {
            return problems;
        }

        List<BindingSpec> actual = provided == null ? List.of() : provided;

        for (BindingSpec req : required) {
            if (req == null) {
                continue;
            }

            boolean satisfied = false;
            BindingSpec typeMismatchAtBinding = null;
            BindingSpec sameTypeWrongBinding = null;
            BindingSpec countShort = null;

            for (BindingSpec candidate : actual) {
                if (candidate == null) {
                    continue;
                }

                if (candidate.binding() == req.binding()) {
                    if (candidate.descriptorType() == req.descriptorType()) {
                        if (normalizedCount(candidate.descriptorCount()) >= normalizedCount(req.descriptorCount())) {
                            satisfied = true;
                            break;
                        }
                        countShort = candidate;
                    } else if (typeMismatchAtBinding == null) {
                        typeMismatchAtBinding = candidate;
                    }
                } else if (candidate.descriptorType() == req.descriptorType() && sameTypeWrongBinding == null) {
                    sameTypeWrongBinding = candidate;
                }
            }

            if (satisfied) {
                continue;
            }

            if (countShort != null) {
                problems.add(describeFailure(passName, req.binding(),
                        describeType(req.descriptorType()) + " x" + normalizedCount(req.descriptorCount()),
                        describeType(countShort.descriptorType()) + " x"
                                + normalizedCount(countShort.descriptorCount())));
            } else if (typeMismatchAtBinding != null) {
                problems.add(describeFailure(passName, req.binding(),
                        describeType(req.descriptorType()),
                        describeType(typeMismatchAtBinding.descriptorType())));
            } else if (sameTypeWrongBinding != null) {
                problems.add(describeFailure(passName, req.binding(),
                        describeType(req.descriptorType()) + " at binding " + req.binding(),
                        describeType(sameTypeWrongBinding.descriptorType())
                                + " at binding " + sameTypeWrongBinding.binding()));
            } else {
                problems.add(describeFailure(passName, req.binding(),
                        describeType(req.descriptorType()), "missing"));
            }
        }

        return problems;
    }

    /**
     * Builds the standardized descriptor-contract failure message. The pass name, binding number,
     * expected type, and actual type are always present.
     *
     * @param passName pass/render-pass identifier
     * @param binding  binding number that failed
     * @param expected expected descriptor type / shape
     * @param actual   descriptor type / shape actually provided
     * @return a single-line diagnostic message
     */
    public static String describeFailure(String passName, int binding, String expected, String actual) {
        String pass = (passName == null || passName.isBlank()) ? "descriptor contract" : passName;
        return "[" + pass + "] binding " + binding
                + ": expected " + expected + ", actual " + actual;
    }

    /**
     * Human-readable name for a {@code VK_DESCRIPTOR_TYPE_*} value. Mirrors the standard Vulkan
     * numeric values without touching LWJGL, keeping this class native-free and unit-testable.
     *
     * @param descriptorType a {@code VK_DESCRIPTOR_TYPE_*} value
     * @return its conventional name, or {@code type(<value>)} when unknown
     */
    public static String describeType(int descriptorType) {
        return switch (descriptorType) {
            case 0 -> "VK_DESCRIPTOR_TYPE_SAMPLER";
            case 1 -> "VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER";
            case 2 -> "VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE";
            case 3 -> "VK_DESCRIPTOR_TYPE_STORAGE_IMAGE";
            case 4 -> "VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER";
            case 5 -> "VK_DESCRIPTOR_TYPE_STORAGE_TEXEL_BUFFER";
            case 6 -> "VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER";
            case 7 -> "VK_DESCRIPTOR_TYPE_STORAGE_BUFFER";
            case 8 -> "VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC";
            case 9 -> "VK_DESCRIPTOR_TYPE_STORAGE_BUFFER_DYNAMIC";
            case 10 -> "VK_DESCRIPTOR_TYPE_INPUT_ATTACHMENT";
            default -> "type(" + descriptorType + ")";
        };
    }

    private static int normalizedCount(int descriptorCount) {
        return descriptorCount > 0 ? descriptorCount : 1;
    }
}
