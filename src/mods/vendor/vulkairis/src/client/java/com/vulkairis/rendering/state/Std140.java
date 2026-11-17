package com.vulkairis.rendering.state;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * std140 layout rules for flat uniform-block members (Phase 10).
 *
 * <p>Reflection reports each member's byte offset and total byte size, but packing an array or
 * matrix needs the <em>element</em> placement: scalars and vectors pack tightly, matrices place
 * each column on a 16-byte stride, and arrays stride every element to a 16-byte boundary. This
 * helper computes the float slots a member value occupies so the packer honors padding instead
 * of assuming tight packing (or a fixed 128-byte buffer).</p>
 */
public final class Std140 {

    private static final Pattern ARRAY_PATTERN =
            Pattern.compile("^(.+)\\[\\s*(\\d*)\\s*\\]$");

    private Std140() {
    }

    /**
     * @return base alignment and size unit information for a member type string
     * ({@code float}, {@code vec3}, {@code mat4}, {@code int[4]}, ...)
     */
    public static MemberLayout layoutOf(String type) {
        if (type == null || type.isBlank()) {
            return new MemberLayout(4, 1, 1);
        }
        String trimmed = type.trim();
        Matcher array = ARRAY_PATTERN.matcher(trimmed);
        if (array.matches()) {
            String element = array.group(1).trim();
            int count = array.group(2).isEmpty() ? 0 : Integer.parseInt(array.group(2));
            MemberLayout elementLayout = scalarOrVector(element);
            int stride = alignUp(elementLayout.sizeBytes(), 16);
            int extent = count > 0 ? stride * count : stride;
            return new MemberLayout(extent, count, stride / 4);
        }
        if (trimmed.startsWith("mat")) {
            return matrixLayout(trimmed);
        }
        MemberLayout scalar = scalarOrVector(trimmed);
        return new MemberLayout(scalar.sizeBytes(), 1, scalar.sizeBytes() / 4);
    }

    /**
     * @return float-slot offsets (relative to the member offset) the value occupies.
     * Arrays stride each element to 16 bytes; matrices stride each column to 16 bytes;
     * scalars and vectors pack tightly. Double-precision types yield no slots: they are
     * not transported (reported, never silently narrowed).
     */
    public static List<Integer> floatSlots(String type, int floatCount) {
        String trimmed = type == null ? "" : type.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("double") || trimmed.startsWith("dvec")
                || trimmed.startsWith("dmat")) {
            return List.of();
        }
        if (trimmed.startsWith("mat")) {
            return matrixSlots(trimmed, floatCount);
        }
        Matcher array = ARRAY_PATTERN.matcher(trimmed);
        if (array.matches()) {
            return arraySlots(array.group(1).trim(), array.group(2), floatCount);
        }
        MemberLayout scalar = scalarOrVector(trimmed);
        int tight = scalar.sizeBytes() / 4;
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < Math.min(floatCount, tight); i++) {
            slots.add(i);
        }
        return slots;
    }

    private static List<Integer> arraySlots(String elementType, String countText, int floatCount) {
        MemberLayout element = scalarOrVector(elementType);
        int tight = Math.max(1, element.sizeBytes() / 4);
        int stride = Math.max(tight, alignUp(element.sizeBytes(), 16) / 4);
        int count = countText.isEmpty() ? 0 : Integer.parseInt(countText);
        if (count <= 0) {
            count = floatCount / tight;
        }
        List<Integer> slots = new ArrayList<>();
        int index = 0;
        for (int e = 0; e < count && index < floatCount; e++) {
            for (int i = 0; i < tight && index < floatCount; i++) {
                slots.add(e * stride + i);
                index++;
            }
        }
        return slots;
    }

    private static List<Integer> matrixSlots(String type, int floatCount) {
        // matCxR: C columns, each starting on a 16-byte (4-float) stride, R tightly packed.
        String dims = type.trim().substring(3);
        int columns = 4;
        int rows = 4;
        try {
            if (dims.contains("x")) {
                String[] parts = dims.split("x");
                columns = Integer.parseInt(parts[0]);
                rows = Integer.parseInt(parts[1]);
            } else if (!dims.isEmpty()) {
                columns = Integer.parseInt(dims);
                rows = columns;
            }
        } catch (NumberFormatException ignored) {
        }
        List<Integer> slots = new ArrayList<>();
        int index = 0;
        for (int c = 0; c < columns && index < floatCount; c++) {
            for (int r = 0; r < rows && index < floatCount; r++) {
                slots.add(c * 4 + r);
                index++;
            }
        }
        return slots;
    }

    private static MemberLayout scalarOrVector(String type) {
        return switch (type) {
            case "float", "int", "uint", "bool" -> new MemberLayout(4, 1, 1);
            case "vec2", "ivec2", "uvec2", "bvec2" -> new MemberLayout(8, 1, 2);
            case "vec3", "ivec3", "uvec3", "bvec3" -> new MemberLayout(12, 1, 3);
            case "vec4", "ivec4", "uvec4", "bvec4" -> new MemberLayout(16, 1, 4);
            case "double" -> new MemberLayout(8, 1, 2);
            case "dvec2" -> new MemberLayout(16, 1, 4);
            case "dvec3" -> new MemberLayout(24, 1, 6);
            case "dvec4" -> new MemberLayout(32, 1, 8);
            default -> new MemberLayout(4, 1, 1);
        };
    }

    private static MemberLayout matrixLayout(String type) {
        String dims = type.trim().substring(3);
        int columns = 4;
        int rows = 4;
        try {
            if (dims.contains("x")) {
                String[] parts = dims.split("x");
                columns = Integer.parseInt(parts[0]);
                rows = Integer.parseInt(parts[1]);
            } else if (!dims.isEmpty()) {
                columns = Integer.parseInt(dims);
                rows = columns;
            }
        } catch (NumberFormatException ignored) {
        }
        int sizeBytes = columns * 16;
        return new MemberLayout(sizeBytes, columns, 4);
    }

    private static int alignUp(int value, int alignment) {
        if (alignment <= 1) {
            return value;
        }
        return ((value + alignment - 1) / alignment) * alignment;
    }

    /**
     * @param sizeBytes    padded member extent in bytes
     * @param count        array element count (1 for non-arrays, 0 for unsized)
     * @param strideFloats float stride between consecutive elements/columns
     */
    public record MemberLayout(int sizeBytes, int count, int strideFloats) {
    }

    /** @return member type names to float-slot placement, for planner diagnostics */
    public static Map<String, List<Integer>> describePlacements(Map<String, String> memberTypes,
                                                                Map<String, Integer> floatCounts) {
        Map<String, List<Integer>> placements = new TreeMap<>();
        for (Map.Entry<String, String> entry : memberTypes.entrySet()) {
            placements.put(entry.getKey(),
                    floatSlots(entry.getValue(), floatCounts.getOrDefault(entry.getKey(), 0)));
        }
        return placements;
    }
}
