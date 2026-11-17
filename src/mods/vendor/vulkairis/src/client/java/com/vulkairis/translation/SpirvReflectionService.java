package com.vulkairis.translation;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SPIR-V reflection for entry points, vertex inputs/outputs, descriptors, and UBO/SSBO layouts.
 * ponytail: Minimal wrapper around SpirvReflector.
 */
public class SpirvReflectionService {
    public record ReflectionData(
        String entryPoint,
        List<VertexAttribute> attributes,
        List<Descriptor> descriptors,
        int uboSize,
        int pushConstantSize
    ) {}

    public record VertexAttribute(String name, int location, int size) {}
    public record Descriptor(String name, int binding, String type) {}

    public static ReflectionData reflect(ByteBuffer spirv) {
        ShaderReflection reflection = SpirvReflector.reflect(spirv);
        if (!reflection.isComplete()) {
            return null;
        }

        // ponytail: Mapping from existing reflection to a clean record
        return new ReflectionData(
            reflection.getEntryPointName(),
            reflection.getVertexAttributes().stream().map(attr ->
                new VertexAttribute(attr.name(), attr.location(), attr.size())
            ).toList(),
            reflection.getUniforms().values().stream().map(uniform ->
                new Descriptor(uniform.name(), 0, uniform.type())
            ).toList(),
            reflection.getUboBlockSize(),
            reflection.getPushConstantSize()
        );
    }
}
