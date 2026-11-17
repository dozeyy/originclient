package com.vulkairis.origin.foreign;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForeignGlslTest {
    @Test
    void everyDescriptorMovesToTheSet() {
        String src = """
                layout(std140, binding = 0) uniform VulkairisUniformBlock { mat4 m; };
                layout(binding = 3) uniform sampler2D gtexture;
                layout(binding=4) uniform sampler2DShadow shadowtex0;
                layout(set = 0, binding = 5) uniform sampler2D colortex1;
                """;
        String out = ForeignGlsl.moveToSet(src, 1);
        assertTrue(out.contains("layout(set = 1, std140, binding = 0) uniform VulkairisUniformBlock"), out);
        assertTrue(out.contains("layout(set = 1, binding = 3) uniform sampler2D gtexture;"), out);
        assertTrue(out.contains("layout(set = 1, binding=4) uniform sampler2DShadow shadowtex0;"), out);
        assertTrue(out.contains("layout(set = 1, binding = 5) uniform sampler2D colortex1;"), out);
    }

    @Test
    void locationsAndPushConstantsStay() {
        String src = """
                layout(location = 2) out vec2 texcoord;
                layout(location = 0) in vec3 pos;
                layout(push_constant) uniform P { vec3 o; };
                """;
        assertEquals(src, ForeignGlsl.moveToSet(src, 1));
        assertNull(ForeignGlsl.moveToSet(null, 1));
    }
}
