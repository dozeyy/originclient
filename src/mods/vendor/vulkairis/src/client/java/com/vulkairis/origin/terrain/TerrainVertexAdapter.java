package com.vulkairis.origin.terrain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Origin (step 5): lets a pack's translated gbuffers vertex shader read VulkanMod's chunk buffers.
 *
 * <p>VulkanMod stores terrain in its own compressed vertex format ({@code CustomVertexFormat.COMPRESSED_TERRAIN},
 * see its {@code basic/terrain/terrain.vsh}): {@code ivec4 Position} (xyz = position * 2048 relative to the
 * draw's {@code ModelOffset} push constant plus a section offset packed into {@code gl_InstanceIndex};
 * w = the packed lightmap coordinate), {@code uvec2 UV0} (texture coordinate * 32768) and
 * {@code uint PackedColor} (RGBA8). There is no normal slot, block id or mid-texture coordinate in it; the
 * quad normal is carried in the colour alpha while a pack is active ({@link TerrainNormals}).</p>
 *
 * <p>A pack vertex shader instead declares the attributes Iris feeds ({@code gl_Vertex} and friends, already
 * renamed to {@code vulkairis_*} by {@code LegacyGlsl}, plus {@code mc_Entity}, {@code mc_midTexCoord},
 * {@code at_tangent}, ...). This adapter turns every one of those {@code in} declarations into a plain
 * global, declares VulkanMod's three real inputs, renames the pack's {@code main} and adds a new
 * {@code main} that decodes the compressed vertex into those globals, runs the pack, then moves
 * {@code gl_Position.z} from OpenGL's -w..w clip range to Vulkan's 0..w (the pack builds its position
 * from Iris's OpenGL-convention projection). Attributes the format cannot supply get Iris's own
 * "unknown" values: normal straight up for a chunk meshed without one, {@code mc_Entity} -1 (no block id), mid-texture coordinate =
 * the vertex's own UV, tangent +X.</p>
 *
 * <p>Pure string logic, unit-tested ({@code TerrainVertexAdapterTest}).</p>
 */
public final class TerrainVertexAdapter {
    /** Location layout of {@code COMPRESSED_TERRAIN}: element order = attribute location. */
    static final String INPUTS = ""
            + "layout(location = 0) in ivec4 vulkairis_vmPosition;\n"
            + "layout(location = 1) in uvec2 vulkairis_vmUV0;\n"
            + "layout(location = 2) in uint vulkairis_vmColor;\n"
            // Same push constant VulkanMod's terrain pipeline declares (PipelineConfigs.TERRAIN_PC: vec3).
            + "layout(push_constant) uniform VulkairisTerrainPush { vec3 vulkairis_ModelOffset; };\n";

    private static final Pattern VERTEX_INPUT = Pattern.compile(
            "(?m)^([ \\t]*)(?:layout\\s*\\([^)]*\\)\\s*)?"
                    + "((?:(?:flat|smooth|noperspective|centroid|invariant|highp|mediump|lowp)\\s+)*)"
                    + "in\\s+([A-Za-z_]\\w*)\\s+"
                    + "([A-Za-z_]\\w*(?:\\s*\\[[^\\]]*\\])?(?:\\s*,\\s*[A-Za-z_]\\w*(?:\\s*\\[[^\\]]*\\])?)*)\\s*;");
    private static final Pattern MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*(?:void\\s*)?\\)");

    /** One rewritten attribute: its GLSL type and name. */
    record Attribute(String type, String name) {
    }

    private TerrainVertexAdapter() {
    }

    /** {@code null} when the source has no {@code main} (nothing to wrap). */
    public static String adapt(String vertexSource) {
        if (vertexSource == null) {
            return null;
        }
        Matcher main = MAIN.matcher(vertexSource);
        if (!main.find()) {
            return null;
        }
        List<Attribute> attributes = new ArrayList<>();
        Matcher m = VERTEX_INPUT.matcher(vertexSource);
        StringBuilder out = new StringBuilder(vertexSource.length() + 2048);
        int last = 0;
        while (m.find()) {
            String type = m.group(3);
            String declarators = m.group(4);
            for (String d : declarators.split(",")) {
                String name = d.trim();
                int bracket = name.indexOf('[');
                attributes.add(new Attribute(type, (bracket < 0 ? name : name.substring(0, bracket)).trim()));
            }
            out.append(vertexSource, last, m.start());
            out.append(m.group(1)).append(type).append(' ').append(declarators.trim()).append(';');
            last = m.end();
        }
        out.append(vertexSource, last, vertexSource.length());
        String body = MAIN.matcher(out).replaceFirst("void vulkairis_packMain()");

        StringBuilder tail = new StringBuilder("\n// Origin: VulkanMod compressed terrain -> pack attributes.\n");
        tail.append(INPUTS);
        tail.append("void main() {\n")
                .append("    vec3 vulkairis_section = vec3(bitfieldExtract(ivec3(gl_InstanceIndex) >> ivec3(0, 16, 8), 0, 8));\n")
                .append("    vec3 vulkairis_tPos = fma(vec3(vulkairis_vmPosition.xyz), vec3(1.0 / 2048.0),"
                        + " vulkairis_ModelOffset + vulkairis_section);\n")
                .append("    vec2 vulkairis_tUv = vec2(vulkairis_vmUV0) * (1.0 / 32768.0);\n")
                .append("    uint vulkairis_tLm = uint(vulkairis_vmPosition.w) & 0xFFFFu;\n")
                .append("    vec2 vulkairis_tLight = vec2(float(vulkairis_tLm & 0xFFu), float(vulkairis_tLm >> 8));\n")
                .append("    vec4 vulkairis_tColor = unpackUnorm4x8(vulkairis_vmColor);\n")
                // Origin (2026-11-17): the quad normal rides in the colour alpha (TerrainNormals).
                .append("    uint vulkairis_nb = vulkairis_vmColor >> 24u;\n")
                .append("    vec3 vulkairis_tNormal = vec3(0.0, 1.0, 0.0);\n")
                .append("    if (vulkairis_nb != 255u) {\n")
                .append("        vec2 vulkairis_np = vec2(float(vulkairis_nb & 15u), float(vulkairis_nb >> 4u))"
                        + " * (2.0 / 14.0) - 1.0;\n")
                .append("        vec3 vulkairis_n = vec3(vulkairis_np, 1.0 - abs(vulkairis_np.x) - abs(vulkairis_np.y));\n")
                .append("        float vulkairis_nt = max(-vulkairis_n.z, 0.0);\n")
                .append("        vulkairis_n.xy += vec2(vulkairis_n.x >= 0.0 ? -vulkairis_nt : vulkairis_nt,"
                        + " vulkairis_n.y >= 0.0 ? -vulkairis_nt : vulkairis_nt);\n")
                .append("        vulkairis_tNormal = normalize(vulkairis_n);\n")
                .append("    }\n")
                .append("    vulkairis_tColor.a = 1.0;\n");
        for (Attribute a : attributes) {
            tail.append("    ").append(a.name()).append(" = ").append(cast(a.type(), valueFor(a.name()))).append(";\n");
        }
        tail.append("    vulkairis_packMain();\n")
                .append("    gl_Position.z = (gl_Position.z + gl_Position.w) * 0.5;\n")
                .append("}\n");
        return body + tail;
    }

    /** The vec4 value an attribute gets (callers narrow it with {@link #cast}). */
    static String valueFor(String name) {
        return switch (name) {
            case "vulkairis_Vertex", "Position", "vaPosition" -> "vec4(vulkairis_tPos, 1.0)";
            case "vulkairis_Color", "Color", "vaColor" -> "vulkairis_tColor";
            case "vulkairis_MultiTexCoord0", "UV0", "vaUV0", "mc_midTexCoord" -> "vec4(vulkairis_tUv, 0.0, 1.0)";
            case "vulkairis_MultiTexCoord1", "UV2", "vaUV2" -> "vec4(vulkairis_tLight, 0.0, 1.0)";
            case "vulkairis_Normal", "Normal", "vaNormal" -> "vec4(vulkairis_tNormal, 0.0)";
            case "mc_Entity" -> "vec4(-1.0, -1.0, 0.0, 0.0)";
            case "at_tangent" -> "vec4(1.0, 0.0, 0.0, 1.0)";
            default -> "vec4(0.0)";
        };
    }

    /** {@code value} (a vec4 expression) converted to {@code type}; unknown types get their zero value. */
    static String cast(String type, String value) {
        String t = type.toLowerCase(Locale.ROOT);
        return switch (t) {
            case "vec4" -> value;
            case "vec3" -> "(" + value + ").xyz";
            case "vec2" -> "(" + value + ").xy";
            case "float" -> "(" + value + ").x";
            case "ivec4", "uvec4" -> type + "(" + value + ")";
            case "ivec3", "uvec3" -> type + "((" + value + ").xyz)";
            case "ivec2", "uvec2" -> type + "((" + value + ").xy)";
            case "int", "uint" -> type + "((" + value + ").x)";
            default -> type + "(0)";
        };
    }
}
