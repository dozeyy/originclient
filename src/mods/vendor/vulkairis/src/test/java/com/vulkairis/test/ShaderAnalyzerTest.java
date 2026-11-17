package com.vulkairis.test;

import com.vulkairis.compatibility.AnalysisResult;
import com.vulkairis.compatibility.ShaderAnalyzer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class ShaderAnalyzerTest {

    @Test
    public void testValidVertexShaderAnalysis() {
        String vertSource = """
                #version 450 core
                layout (location = 0) in vec3 Position;
                layout (location = 1) in vec4 Color;
                layout (location = 0) out vec4 vertexColor;
                void main() {
                    gl_Position = vec4(Position, 1.0);
                    vertexColor = Color;
                }
                """;

        AnalysisResult result = ShaderAnalyzer.analyzeAndTest("valid_vert", vertSource, 35633);
        Assertions.assertEquals(AnalysisResult.Status.PASS, result.status(), "Valid shader should PASS analysis");
    }

    @Test
    public void testValidFragmentShaderAnalysis() {
        String fragSource = """
                #version 450 core
                layout (location = 0) in vec4 vertexColor;
                layout (location = 0) out vec4 fragColor;
                void main() {
                    fragColor = vertexColor;
                }
                """;

        AnalysisResult result = ShaderAnalyzer.analyzeAndTest("valid_frag", fragSource, 35632);
        Assertions.assertEquals(AnalysisResult.Status.PASS, result.status(), "Valid fragment shader should PASS analysis");
    }

    @Test
    public void testUnsupportedRaytracingShader() {
        String rtSource = """
                #version 450 core
                #extension GL_EXT_ray_tracing : require
                layout(location = 0) rayPayloadEXT vec3 hitValue;
                void main() {}
                """;

        AnalysisResult result = ShaderAnalyzer.analyzeAndTest("rt_shader", rtSource, 35632);
        Assertions.assertEquals(AnalysisResult.Status.UNSUPPORTED, result.status(), "Raytracing shader must be flagged UNSUPPORTED");
        Assertions.assertTrue(result.reason().contains("Ray Tracing"));
    }

    @Test
    public void testUnsupportedGeometryStage() {
        String geomSource = """
                #version 450 core
                layout (triangles) in;
                layout (triangle_strip, max_vertices = 3) out;
                void main() {}
                """;

        AnalysisResult result = ShaderAnalyzer.analyzeAndTest("geom_shader", geomSource, 36313);
        Assertions.assertEquals(AnalysisResult.Status.UNSUPPORTED, result.status(), "Geometry shaders must be flagged UNSUPPORTED");
    }

    @Test
    public void testMalformedShaderGracefulFailure() {
        String malformedSource = """
                #version 450 core
                this is completely invalid glsl code !!
                """;

        AnalysisResult result = ShaderAnalyzer.analyzeAndTest("malformed_shader", malformedSource, 35632);
        Assertions.assertEquals(AnalysisResult.Status.UNSUPPORTED, result.status(), "Malformed shader should return UNSUPPORTED without throwing");
        Assertions.assertNotNull(result.details());
    }
}
