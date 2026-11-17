package com.vulkairis.rendering.state;

import com.vulkairis.translation.ShaderReflection;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Writes uniform-block contents into a tightly packed buffer using the reflected member offsets.
 *
 * <p>Reflection already reports each member's offset and byte size; packing against those offsets is
 * what makes uploaded frame/pass values actually reach the shader in the layout it compiled against.
 * Missing members are reported rather than silently left as garbage.</p>
 */
public final class UniformBlockPacker {

    /**
     * The result of packing a block.
     *
     * @param data    the packed buffer, positioned at 0
     * @param written member names that were written
     * @param missing reflected members for which no value was supplied
     */
    public record PackResult(ByteBuffer data, List<String> written, List<String> missing) {

        /** @return whether every reflected member received a value */
        public boolean isComplete() {
            return missing.isEmpty();
        }
    }

    private UniformBlockPacker() {
    }

    /**
     * Packs {@code values} (member name to float components) into a buffer matching the reflected
     * block layout.
     *
     * @param block  the reflected uniform block
     * @param values member values; a matrix is 16 floats, a vec4 is 4, and so on
     * @return the packed buffer plus which members were written and which were missing
     */
    public static PackResult pack(ShaderReflection.UniformBlock block, Map<String, float[]> values) {
        Objects.requireNonNull(block, "block");
        Objects.requireNonNull(values, "values");

        int size = Math.max(block.size(), minRequiredSize(block));
        ByteBuffer buffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());

        List<String> written = new ArrayList<>();
        List<String> missing = new ArrayList<>();

        for (ShaderReflection.UniformInfo member : block.members()) {
            float[] provided = values.get(member.name());
            if (provided == null) {
                missing.add(member.name());
                continue;
            }
            int componentBytes = Math.max(4, member.size());
            int components = componentBytes / 4;
            buffer.position(member.offset());
            for (int i = 0; i < components && i < provided.length; i++) {
                buffer.putFloat(provided[i]);
            }
            written.add(member.name());
        }

        buffer.position(0);
        return new PackResult(buffer, List.copyOf(written), List.copyOf(missing));
    }

    /**
     * Phase 10: packs pre-placed float values at absolute buffer offsets into a zero-filled
     * buffer sized from the reflected block. Placement (padding included) is the planner's job
     * via {@code Std140}; this method only bounds-checks. Members with no placed values are
     * reported missing and keep their zero fill instead of garbage.
     *
     * @param block  the reflected uniform block (sizes the buffer)
     * @param placed absolute byte-offset to float value
     * @param memberOf maps an absolute offset back to its member name for the missing report;
     *                 offsets unknown here are still written when inside the buffer
     */
    public static PackResult packPlaced(ShaderReflection.UniformBlock block,
                                        Map<Integer, Float> placed,
                                        java.util.function.IntFunction<String> memberOf) {
        Objects.requireNonNull(block, "block");
        Objects.requireNonNull(placed, "placed");
        Objects.requireNonNull(memberOf, "memberOf");

        int size = Math.max(block.size(), minRequiredSize(block));
        ByteBuffer buffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());

        List<String> written = new ArrayList<>();
        for (Map.Entry<Integer, Float> entry : placed.entrySet()) {
            int offset = entry.getKey();
            if (offset < 0 || offset + 4 > size || entry.getValue() == null) {
                continue;
            }
            buffer.putFloat(offset, entry.getValue());
            String member = memberOf.apply(offset);
            if (member != null && !written.contains(member)) {
                written.add(member);
            }
        }

        List<String> missing = new ArrayList<>();
        for (ShaderReflection.UniformInfo member : block.members()) {
            if (!written.contains(member.name())) {
                missing.add(member.name());
            }
        }
        buffer.position(0);
        return new PackResult(buffer, List.copyOf(written), List.copyOf(missing));
    }

    private static int minRequiredSize(ShaderReflection.UniformBlock block) {
        int end = 0;
        for (ShaderReflection.UniformInfo member : block.members()) {
            end = Math.max(end, member.offset() + Math.max(4, member.size()));
        }
        return end;
    }
}
