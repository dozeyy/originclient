package com.vulkairis.origin.terrain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerrainVertexAdapterTest {
    private static final String PACK = """
            #version 450 core
            precision highp float;
            in vec4 vulkairis_Vertex;
            in vec4 vulkairis_Color;
            in vec4 vulkairis_MultiTexCoord0, vulkairis_MultiTexCoord1;
            in vec3 vulkairis_Normal;
            in vec4 mc_Entity;
            in vec2 mc_midTexCoord;
            flat in ivec2 blockEntityId;
            layout(location = 2) out vec2 texcoord;
            out vec4 tint;
            float helper(in vec3 x, in float y) { return x.x + y; }
            void main() {
                texcoord = vulkairis_MultiTexCoord0.xy + mc_midTexCoord;
                tint = vulkairis_Color * helper(vulkairis_Normal, mc_Entity.x);
                gl_Position = vulkairis_Vertex;
            }
            """;

    @Test
    void everyPackInputBecomesADecodedGlobal() {
        String out = TerrainVertexAdapter.adapt(PACK);
        // No pack attribute stays a vertex input: only VulkanMod's three real ones.
        assertFalse(out.contains("in vec4 vulkairis_Vertex"), out);
        assertFalse(out.contains("in vec4 mc_Entity"), out);
        assertFalse(out.contains("in ivec2 blockEntityId"), out);
        assertEquals(3, count(out, ") in "), out);
        assertTrue(out.contains("vec4 vulkairis_MultiTexCoord0, vulkairis_MultiTexCoord1;"), out);
        // Varyings and function parameters are untouched.
        assertTrue(out.contains("layout(location = 2) out vec2 texcoord;"), out);
        assertTrue(out.contains("float helper(in vec3 x, in float y)"), out);
        // Each global is filled from the compressed vertex, narrowed to its declared type.
        assertTrue(out.contains("vulkairis_Vertex = vec4(vulkairis_tPos, 1.0);"), out);
        assertTrue(out.contains("vulkairis_MultiTexCoord1 = vec4(vulkairis_tLight, 0.0, 1.0);"), out);
        assertTrue(out.contains("vulkairis_Normal = (vec4(vulkairis_tNormal, 0.0)).xyz;"), out);
        assertTrue(out.contains("mc_midTexCoord = (vec4(vulkairis_tUv, 0.0, 1.0)).xy;"), out);
        assertTrue(out.contains("blockEntityId = ivec2((vec4(0.0)).xy);"), out);
    }

    @Test
    void packMainRunsBetweenDecodeAndDepthFix() {
        String out = TerrainVertexAdapter.adapt(PACK);
        assertEquals(1, count(out, "void main()"), out);
        int decode = out.indexOf("mc_Entity = ");
        int call = out.indexOf("vulkairis_packMain();");
        int depth = out.indexOf("gl_Position.z = (gl_Position.z + gl_Position.w) * 0.5;");
        assertTrue(out.contains("void vulkairis_packMain()"), out);
        assertTrue(decode > 0 && decode < call && call < depth, out);
    }

    @Test
    void sourceWithoutMainIsRefused() {
        assertNull(TerrainVertexAdapter.adapt("#version 450 core\nin vec4 a;\n"));
    }

    private static int count(String s, String needle) {
        int n = 0;
        for (int i = s.indexOf(needle); i >= 0; i = s.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }
}
