package com.vulkairis.test;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.vulkairis.bridge.VulkanModProgramBridge;
import com.vulkairis.translation.ShaderTranslator;
import com.vulkairis.translation.SpirvCompiler;
import com.vulkairis.translation.ShaderCompileResult;
import com.vulkairis.translation.TranslationCache;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.util.shaderc.Shaderc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Phase B gap B-3 link-path fixture: a fixed-function vertex shader whose legacy
 * builtins ({@code ftransform()}, {@code gl_Vertex}, {@code gl_Normal},
 * {@code gl_Color}, {@code gl_MultiTexCoord0/1}) become {@code vulkairis_*} inputs
 * must select a real {@code DefaultVertexFormat} and receive locations instead of
 * hitting the guaranteed {@code failLink}.
 *
 * <p>Device-free: exercises the exact link-path steps
 * ({@code preprocessGlsl} &rarr; {@code collectVertexInputNames} &rarr;
 * {@code chooseVertexFormat} &rarr; {@code buildVertexInputLocations} &rarr;
 * {@code injectLayoutLocations} &rarr; Shaderc compile) without touching the Vulkan
 * device. Before the alias fix {@code chooseVertexFormat} returned {@code null} for
 * every such pack.</p>
 */
public class LegacyAttribLinkPathTest {

    private static final String LEGACY_VERTEX = """
            #version 120
            void main() {
                vec4 p = gl_Vertex;
                vec3 n = gl_Normal;
                vec4 c = gl_Color;
                vec2 t0 = gl_MultiTexCoord0.xy;
                vec2 t1 = gl_MultiTexCoord1.xy;
                gl_Position = ftransform() + vec4(p.xyz + n, 1.0) + c + vec4(t0 + t1, 0.0, 0.0);
            }
            """;

    private static final String MINIMAL_FRAGMENT = """
            #version 120
            void main() {
                gl_FragColor = vec4(1.0);
            }
            """;

    @BeforeEach
    public void clearCaches() {
        TranslationCache.clearForTests();
    }

    @Test
    public void testLegacyAttribsSelectARealVertexFormat() {
        String vertSrc = ShaderTranslator.preprocessGlsl(LEGACY_VERTEX, true, "linkpath.vsh");

        List<String> required = ShaderTranslator.collectVertexInputNames(vertSrc);
        Assertions.assertFalse(required.isEmpty(), "The legacy vertex must declare inputs");
        Assertions.assertTrue(required.stream().anyMatch(n -> n.startsWith("vulkairis_")),
                "Expected vulkairis_* legacy inputs, got: " + required);

        VertexFormat format = VulkanModProgramBridge.chooseVertexFormat(required);
        Assertions.assertNotNull(format,
                "Legacy vulkairis_* inputs must resolve onto a real format via aliases, got: " + required);

        Map<String, Integer> locations = VulkanModProgramBridge.buildVertexInputLocations(format);
        List<String> uncovered = new ArrayList<>();
        for (String name : required) {
            if (!locations.containsKey(name)) {
                uncovered.add(name);
            }
        }
        Assertions.assertTrue(uncovered.isEmpty(),
                "Every legacy input needs a location, uncovered: " + uncovered + " in " + locations);
    }

    @Test
    public void testLegacyLinkPathInjectsAndCompiles() {
        String vertSrc = ShaderTranslator.preprocessGlsl(LEGACY_VERTEX, true, "linkpath.vsh");
        String fragSrc = ShaderTranslator.preprocessGlsl(MINIMAL_FRAGMENT, false, "linkpath.fsh");

        List<String> required = ShaderTranslator.collectVertexInputNames(vertSrc);
        VertexFormat format = VulkanModProgramBridge.chooseVertexFormat(required);
        Assertions.assertNotNull(format);

        Map<String, Integer> vertexLocations =
                VulkanModProgramBridge.buildVertexInputLocations(format);
        Map<String, Integer> varyings = Map.of();

        String vertFinal = ShaderTranslator.injectLayoutLocations(
                vertSrc, true, vertexLocations, varyings);
        String fragFinal = ShaderTranslator.injectLayoutLocations(
                fragSrc, false, vertexLocations, varyings);

        for (String name : required) {
            Assertions.assertTrue(vertFinal.contains(name),
                    "Injected vertex must still declare " + name);
        }

        ShaderCompileResult vert = SpirvCompiler.compileGlslToSpirvDetailed(
                "linkpath.vsh", vertFinal, Shaderc.shaderc_glsl_vertex_shader);
        Assertions.assertTrue(vert.succeeded(),
                "Legacy link-path vertex must compile: " + vert.compilerLog());

        ShaderCompileResult frag = SpirvCompiler.compileGlslToSpirvDetailed(
                "linkpath.fsh", fragFinal, Shaderc.shaderc_glsl_fragment_shader);
        Assertions.assertTrue(frag.succeeded(),
                "Legacy link-path fragment must compile: " + frag.compilerLog());
    }

    @Test
    public void testLegacyAliasMapCoversAllFixedFunctionAttribs() {
        Map<String, String> aliases = VulkanModProgramBridge.LEGACY_ATTRIB_ALIASES;

        for (String legacy : List.of("vulkairis_Vertex", "vulkairis_Normal", "vulkairis_Color",
                "vulkairis_MultiTexCoord0", "vulkairis_MultiTexCoord1")) {
            Assertions.assertTrue(aliases.containsKey(legacy), "Missing alias for " + legacy);
            VertexFormat format = VulkanModProgramBridge.chooseVertexFormat(List.of(legacy));
            Assertions.assertNotNull(format, "Single legacy input must resolve: " + legacy);
            Assertions.assertTrue(
                    VulkanModProgramBridge.buildVertexInputLocations(format).containsKey(legacy));
        }
    }
}
