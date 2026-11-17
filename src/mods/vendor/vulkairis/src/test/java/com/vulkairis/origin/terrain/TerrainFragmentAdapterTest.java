package com.vulkairis.origin.terrain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerrainFragmentAdapterTest {
    private static final String FRAG = "#version 450\n"
            + "layout(location = 0) out vec4 vulkairisFragData0;\n"
            + "layout(location = 1) out vec4 vulkairisFragData1;\n"
            + "void main() {\n    vulkairisFragData0 = vec4(1.0);\n}\n";

    @Test
    void wrapsMainAndTestsLocationZeroAlpha() {
        String out = TerrainFragmentAdapter.alphaTest(FRAG, 0.1f);
        assertTrue(out.contains("void vulkairis_packMain() {"));
        assertTrue(out.contains("if (!(vulkairisFragData0.a > 0.1))"));
        assertEquals(out.indexOf("void main()"), out.lastIndexOf("void main()"));
    }

    @Test
    void leavesSourceWithoutOutputZeroUnchanged() {
        String noOut = "#version 450\nvoid main() {}\n";
        assertEquals(noOut, TerrainFragmentAdapter.alphaTest(noOut, 0.1f));
    }

    @Test
    void translucentProgramsAreNotTested() {
        assertFalse(TerrainFragmentAdapter.alphaTested("gbuffers_water"));
        assertFalse(TerrainFragmentAdapter.alphaTested("shadow_translucent"));
        assertTrue(TerrainFragmentAdapter.alphaTested("gbuffers_terrain"));
        assertTrue(TerrainFragmentAdapter.alphaTested("shadow"));
    }
}
