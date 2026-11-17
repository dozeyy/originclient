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
        return wrap(vertexSource, INPUTS, VULKANMOD_DECODE);
    }

    /**
     * Origin (step 9): the same wrap for a vertex stream Vulkairis does not own (Flywheel's instanced
     * meshes under {@code flywheel-vulkan}). Instead of decoding VulkanMod's chunk format, the new
     * {@code main} calls {@link #FEED_SIGNATURE}, which the caller defines ahead of this source:
     * camera-relative position, texture coordinate, raw lightmap coordinate (0..255, as chunks carry
     * it), RGBA colour and world-space normal. The pack then runs exactly as it does for terrain.
     *
     * @return {@code null} when the source has no {@code main}
     */
    public static String adaptForeign(String vertexSource) {
        return wrap(vertexSource, "void " + FEED_SIGNATURE + ";\n", FEED_DECODE);
    }

    /** What a foreign caller defines (see {@link #adaptForeign}). */
    public static final String FEED_SIGNATURE = "vulkairis_feed(out vec3 pos, out vec2 uv, out vec2 light,"
            + " out vec4 color, out vec3 normal)";

    /** VulkanMod's compressed terrain vertex into the five values every attribute is built from. */
    private static final String VULKANMOD_DECODE = ""
            + "    vec3 vulkairis_section = vec3(bitfieldExtract(ivec3(gl_InstanceIndex) >> ivec3(0, 16, 8), 0, 8));\n"
            + "    vec3 vulkairis_tPos = fma(vec3(vulkairis_vmPosition.xyz), vec3(1.0 / 2048.0),"
            + " vulkairis_ModelOffset + vulkairis_section);\n"
            + "    vec2 vulkairis_tUv = vec2(vulkairis_vmUV0) * (1.0 / 32768.0);\n"
            + "    uint vulkairis_tLm = uint(vulkairis_vmPosition.w) & 0xFFFFu;\n"
            + "    vec2 vulkairis_tLight = vec2(float(vulkairis_tLm & 0xFFu), float(vulkairis_tLm >> 8));\n"
            + "    vec4 vulkairis_tColor = unpackUnorm4x8(vulkairis_vmColor);\n"
            // Origin (2026-11-17): the quad normal rides in the colour alpha (TerrainNormals).
            + "    uint vulkairis_nb = vulkairis_vmColor >> 24u;\n"
            + "    vec3 vulkairis_tNormal = vec3(0.0, 1.0, 0.0);\n"
            + "    if (vulkairis_nb != 255u) {\n"
            + "        vec2 vulkairis_np = vec2(float(vulkairis_nb & 15u), float(vulkairis_nb >> 4u))"
            + " * (2.0 / 14.0) - 1.0;\n"
            + "        vec3 vulkairis_n = vec3(vulkairis_np, 1.0 - abs(vulkairis_np.x) - abs(vulkairis_np.y));\n"
            + "        float vulkairis_nt = max(-vulkairis_n.z, 0.0);\n"
            + "        vulkairis_n.xy += vec2(vulkairis_n.x >= 0.0 ? -vulkairis_nt : vulkairis_nt,"
            + " vulkairis_n.y >= 0.0 ? -vulkairis_nt : vulkairis_nt);\n"
            + "        vulkairis_tNormal = normalize(vulkairis_n);\n"
            + "    }\n"
            + "    vulkairis_tColor.a = 1.0;\n";

    /** A foreign stream: the caller's feed fills the same five values. */
    private static final String FEED_DECODE = ""
            + "    vec3 vulkairis_tPos;\n"
            + "    vec2 vulkairis_tUv;\n"
            + "    vec2 vulkairis_tLight;\n"
            + "    vec4 vulkairis_tColor;\n"
            + "    vec3 vulkairis_tNormal;\n"
            + "    vulkairis_feed(vulkairis_tPos, vulkairis_tUv, vulkairis_tLight, vulkairis_tColor, vulkairis_tNormal);\n";

    /**
     * Turns every pack {@code in} into a global, renames the pack's {@code main}, then appends
     * {@code header} and a new {@code main} that runs {@code decode} (which defines the five
     * {@code vulkairis_t*} values), fills the globals, runs the pack and fixes the depth range.
     */
    private static String wrap(String vertexSource, String header, String decode) {
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

        StringBuilder tail = new StringBuilder("\n// Origin: vertex stream -> pack attributes.\n");
        tail.append(header);
        tail.append("void main() {\n").append(decode);
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
