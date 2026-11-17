package com.vulkairis.origin.uniforms;

import java.nio.ByteBuffer;

/**
 * Origin (step 3): writes one uniform value into a std140 uniform block member, given the member's
 * reflected GLSL type and byte offset (SpirvReflector's {@code UniformInfo}). GPU-free, unit-tested.
 *
 * <p>Values arrive as {@code double[]} so ints (worldTime, frameCounter) stay exact. Layout rules:
 * scalars and vectors are packed; every array element and every matrix column starts on a 16-byte
 * stride (std140). Matrices come in as column-major {@code mat4} (16 values) or tightly packed
 * {@code C*R} values; a {@code mat3} member takes the upper-left 3x3 of a {@code mat4} source.
 * Integer and bool members (the translator rewrites block bools to int) are written as int32.</p>
 */
public final class Std140Writer {
    private Std140Writer() {
    }

    /** Parsed member type: {@code columns} (1 unless a matrix) x {@code rows} components, {@code count} elements. */
    record Shape(int columns, int rows, int count, boolean integer) {
        int elementStride() {
            return columns * 16;
        }
    }

    /** Parses a reflected type name ({@code float}, {@code ivec3}, {@code mat4x4}, {@code vec4[8]}); null when unsupported. */
    static Shape parse(String type) {
        if (type == null) {
            return null;
        }
        String base = type.trim();
        int count = 1;
        int bracket = base.indexOf('[');
        if (bracket >= 0) {
            int close = base.indexOf(']', bracket);
            if (close < 0) {
                return null;
            }
            String n = base.substring(bracket + 1, close).trim();
            if (n.isEmpty()) {
                return null; // runtime-sized arrays cannot be filled from a fixed value
            }
            try {
                count = Integer.parseInt(n);
            } catch (NumberFormatException e) {
                return null;
            }
            if (count <= 0) {
                return null;
            }
            base = base.substring(0, bracket).trim();
        }
        switch (base) {
            case "float":
                return new Shape(1, 1, count, false);
            case "int":
            case "uint":
            case "bool":
                return new Shape(1, 1, count, true);
            default:
                break;
        }
        if (base.startsWith("mat")) {
            String dims = base.substring(3);
            int x = dims.indexOf('x');
            try {
                int c = Integer.parseInt(x < 0 ? dims : dims.substring(0, x));
                int r = x < 0 ? c : Integer.parseInt(dims.substring(x + 1));
                if (c < 2 || c > 4 || r < 2 || r > 4) {
                    return null;
                }
                return new Shape(c, r, count, false);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        int vec = base.indexOf("vec");
        if (vec >= 0 && base.length() == vec + 4) {
            String prefix = base.substring(0, vec);
            int n = base.charAt(vec + 3) - '0';
            if (n < 2 || n > 4) {
                return null;
            }
            return switch (prefix) {
                case "" -> new Shape(1, n, count, false);
                case "i", "u", "b" -> new Shape(1, n, count, true);
                default -> null; // dvec: doubles are not uploaded
            };
        }
        return null;
    }

    /**
     * Writes {@code value} into {@code buf} for a member of {@code type} at {@code offset}. Nothing is
     * written at or past {@code limit}. Missing components are left as they are (the caller zero-fills).
     *
     * @return false when the type is unsupported (the member stays zero)
     */
    public static boolean write(ByteBuffer buf, int offset, int limit, String type, double[] value) {
        Shape shape = parse(type);
        if (shape == null || value == null || offset < 0) {
            return false;
        }
        boolean matrix = shape.columns() > 1;
        // Source layout: matrices as mat4 columns (stride 4, 16 per element) when the source is big
        // enough for that, else tightly packed; vectors/scalars tightly packed per element.
        boolean mat4Source = matrix && value.length >= shape.count() * 16;
        int srcColumnStride = mat4Source ? 4 : shape.rows();
        int srcElementStride = matrix ? (mat4Source ? 16 : shape.columns() * shape.rows()) : shape.rows();
        boolean strided = matrix || shape.count() > 1;

        for (int e = 0; e < shape.count(); e++) {
            int elementOffset = offset + (strided ? e * shape.elementStride() : 0);
            for (int c = 0; c < shape.columns(); c++) {
                int columnOffset = elementOffset + c * 16;
                for (int r = 0; r < shape.rows(); r++) {
                    int src = e * srcElementStride + c * srcColumnStride + r;
                    if (src >= value.length) {
                        return true;
                    }
                    int at = (matrix ? columnOffset : elementOffset) + r * 4;
                    if (at + 4 > limit) {
                        return true;
                    }
                    if (shape.integer()) {
                        buf.putInt(at, (int) Math.round(value[src]));
                    } else {
                        buf.putFloat(at, (float) value[src]);
                    }
                }
            }
        }
        return true;
    }
}
