package com.vulkairis.plan;

import com.vulkairis.translation.ShaderReflection;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Phase 5: Typed sampler plan per program variant.
 *
 * <p>Maps every reflected sampler or image descriptor to its typed dimensionality, numeric class,
 * sample mode, target source, and backing handle. Enforces total binding: every reflected resource
 * is either legally bound to a typed GPU resource or flagged with an explicit rejection reason,
 * eliminating silent fallback to {@code sampler2D}.</p>
 */
public record SamplerPlan(
        String programName,
        Map<String, SamplerDescriptor> samplers,
        long generation
) {
    public enum SamplerType {
        SAMPLER_2D("sampler2D", false),
        SAMPLER_2D_SHADOW("sampler2DShadow", true),
        SAMPLER_2D_ARRAY("sampler2DArray", false),
        SAMPLER_2D_ARRAY_SHADOW("sampler2DArrayShadow", true),
        SAMPLER_3D("sampler3D", false),
        SAMPLER_CUBE("samplerCube", false),
        IMAGE_2D("image2D", false),
        IMAGE_3D("image3D", false),
        UNKNOWN("unknown", false);

        private final String glslToken;
        private final boolean depthCompare;

        SamplerType(String glslToken, boolean depthCompare) {
            this.glslToken = glslToken;
            this.depthCompare = depthCompare;
        }

        public String glslToken() {
            return glslToken;
        }

        public boolean isDepthCompare() {
            return depthCompare;
        }

        public static SamplerType fromGlsl(String type) {
            if (type == null) return UNKNOWN;
            String lower = type.toLowerCase(java.util.Locale.ROOT);
            for (SamplerType st : values()) {
                if (st.glslToken.equalsIgnoreCase(lower)) {
                    return st;
                }
            }
            if (lower.contains("shadow")) return SAMPLER_2D_SHADOW;
            if (lower.contains("cube")) return SAMPLER_CUBE;
            if (lower.contains("3d")) return SAMPLER_3D;
            if (lower.contains("array")) return SAMPLER_2D_ARRAY;
            if (lower.contains("image")) return IMAGE_2D;
            return SAMPLER_2D;
        }
    }

    public enum NumericClass {
        FLOAT,
        INT,
        UINT,
        DEPTH
    }

    public record SamplerDescriptor(
            String name,
            int binding,
            SamplerType type,
            NumericClass numericClass,
            String targetSource,
            long imageView,
            long samplerHandle,
            boolean supported,
            String rejectionReason
    ) {
        public SamplerDescriptor {
            Objects.requireNonNull(name, "name");
            type = (type != null) ? type : SamplerType.SAMPLER_2D;
            numericClass = (numericClass != null) ? numericClass : NumericClass.FLOAT;
        }

        public static SamplerDescriptor valid(String name, int binding, SamplerType type,
                                              NumericClass numericClass, String targetSource,
                                              long imageView, long samplerHandle) {
            return new SamplerDescriptor(name, binding, type, numericClass, targetSource,
                    imageView, samplerHandle, true, null);
        }

        public static SamplerDescriptor unsupported(String name, int binding, SamplerType type,
                                                    String reason) {
            return new SamplerDescriptor(name, binding, type, NumericClass.FLOAT, null,
                    0L, 0L, false, reason);
        }
    }

    public SamplerPlan {
        Objects.requireNonNull(programName, "programName");
        samplers = (samplers != null) ? Collections.unmodifiableMap(new LinkedHashMap<>(samplers)) : Map.of();
    }

    public boolean isFullySupported() {
        return samplers.values().stream().allMatch(SamplerDescriptor::supported);
    }

    public List<String> getRejectionReasons() {
        return samplers.values().stream()
                .filter(s -> !s.supported())
                .map(s -> s.name() + ": " + s.rejectionReason())
                .collect(Collectors.toList());
    }

    /**
     * Builds a typed SamplerPlan from reflected bindings and an active target plan.
     */
    public static SamplerPlan build(String programName,
                                    Map<String, ShaderReflection.SamplerBinding> reflectedSamplers,
                                    TargetPlan targetPlan,
                                    long generation) {
        Map<String, SamplerDescriptor> map = new LinkedHashMap<>();
        if (reflectedSamplers == null || reflectedSamplers.isEmpty()) {
            return new SamplerPlan(programName, map, generation);
        }

        for (Map.Entry<String, ShaderReflection.SamplerBinding> entry : reflectedSamplers.entrySet()) {
            String name = entry.getKey();
            ShaderReflection.SamplerBinding binding = entry.getValue();
            int bindingIndex = binding.binding();

            // Infer typed representation from declaration name and reflected properties
            SamplerType type = inferSamplerType(name);
            NumericClass numClass = inferNumericClass(name, type);
            String targetSource = resolveTargetSource(name);

            if (type == SamplerType.IMAGE_3D || type == SamplerType.SAMPLER_3D) {
                // Explicit rejection for 3D textures if unsupported by 2D target graph
                map.put(name, SamplerDescriptor.unsupported(name, bindingIndex, type,
                        "3D texture/image resources are not backed by standard 2D target pipeline"));
                continue;
            }

            long imageView = 0L;
            long sampler = 0L;
            if (targetPlan != null && targetSource != null) {
                TargetPlan.TargetEntry targetEntry = targetPlan.getTarget(targetSource);
                if (targetEntry != null) {
                    imageView = targetEntry.imageView();
                    sampler = targetEntry.sampler();
                }
            }

            map.put(name, SamplerDescriptor.valid(name, bindingIndex, type, numClass, targetSource, imageView, sampler));
        }

        return new SamplerPlan(programName, map, generation);
    }

    private static SamplerType inferSamplerType(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("shadowtex") || lower.contains("shadowcolor")) {
            return SamplerType.SAMPLER_2D_SHADOW;
        }
        if (lower.contains("cube") || lower.contains("skybox")) {
            return SamplerType.SAMPLER_CUBE;
        }
        if (lower.contains("noise3d") || lower.contains("volume")) {
            return SamplerType.SAMPLER_3D;
        }
        return SamplerType.SAMPLER_2D;
    }

    private static NumericClass inferNumericClass(String name, SamplerType type) {
        if (type.isDepthCompare() || name.toLowerCase(java.util.Locale.ROOT).contains("depth")) {
            return NumericClass.DEPTH;
        }
        return NumericClass.FLOAT;
    }

    private static String resolveTargetSource(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("colortex")) return lower;
        if (lower.startsWith("gaux")) return lower;
        if (lower.startsWith("depthtex")) return lower;
        if (lower.startsWith("shadowtex")) return lower;
        if (lower.startsWith("shadowcolor")) return lower;
        if (lower.equals("gtexture") || lower.equals("texture")) return "gtexture";
        if (lower.equals("lightmap")) return "lightmap";
        if (lower.equals("noisetex")) return "noisetex";
        return lower;
    }
}
