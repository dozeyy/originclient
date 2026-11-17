package com.vulkairis.origin.shadow;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShadowSamplersTest {
    @Test
    void findsShadowSamplersAcrossStages() {
        String vert = "#version 450\nlayout(binding = 3) uniform sampler2DShadow shadowtex0;\n";
        String frag = "layout(binding=4) uniform highp sampler2DShadow shadowtex1;\nuniform sampler2D shadowcolor0;\n"
                + "uniform sampler2DShadow shadowtex0;\n";
        Set<String> names = ShadowSamplers.compareSamplerNames(vert, frag);
        assertEquals(List.of("shadowtex0", "shadowtex1"), List.copyOf(names));
    }

    @Test
    void plainSamplersAndNullsAreIgnored() {
        assertTrue(ShadowSamplers.compareSamplerNames(null, "uniform sampler2D shadowtex0;\n").isEmpty());
        assertTrue(ShadowSamplers.compareSamplerNames("uniform sampler2DShadowX foo;").isEmpty());
    }
}
