package com.vulkairis.plan;

import com.vulkairis.rendering.state.Std140;
import com.vulkairis.translation.ShaderReflection;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Phase 5: Uniform plan for std140 UBO layout.
 *
 * <p>Describes member offsets, alignments, and element strides for a program's uniform block,
 * ensuring values are packed according to Vulkan std140 layout rules rather than raw float dumps.</p>
 */
public record UniformPlan(
        String programName,
        int totalSizeBytes,
        Map<String, MemberSpec> members,
        long generation
) {
    public record MemberSpec(
            String name,
            String type,
            int offsetBytes,
            int sizeBytes,
            int strideFloats,
            List<Integer> floatSlots
    ) {
        public MemberSpec {
            Objects.requireNonNull(name, "name");
            type = (type != null) ? type : "float";
        }
    }

    public UniformPlan {
        Objects.requireNonNull(programName, "programName");
        members = (members != null) ? Collections.unmodifiableMap(new LinkedHashMap<>(members)) : Map.of();
    }

    /**
     * Builds a UniformPlan from reflected uniform block members.
     */
    public static UniformPlan build(String programName, ShaderReflection.UniformBlock block, long generation) {
        if (block == null || block.members() == null || block.members().isEmpty()) {
            return new UniformPlan(programName, Math.max(16, block != null ? block.size() : 16), Map.of(), generation);
        }

        Map<String, MemberSpec> map = new LinkedHashMap<>();
        int currentOffset = 0;

        for (ShaderReflection.UniformInfo m : block.members()) {
            String name = m.name();
            String type = m.type() != null ? m.type() : "float";
            int memberOffset = m.offset() >= 0 ? m.offset() : currentOffset;
            int size = m.size() > 0 ? m.size() : Std140.layoutOf(type).sizeBytes();

            int floatCount = Math.max(1, size / 4);
            List<Integer> slots = Std140.floatSlots(type, floatCount);
            int stride = Std140.layoutOf(type).strideFloats();

            map.put(name, new MemberSpec(name, type, memberOffset, size, stride, slots));
            currentOffset = memberOffset + size;
        }

        int totalSize = Math.max(block.size(), currentOffset);
        // std140 block size is aligned to 16 bytes
        totalSize = (totalSize + 15) & ~15;

        return new UniformPlan(programName, totalSize, map, generation);
    }

    public boolean hasMember(String name) {
        return members.containsKey(name);
    }

    public MemberSpec getMember(String name) {
        return members.get(name);
    }
}
