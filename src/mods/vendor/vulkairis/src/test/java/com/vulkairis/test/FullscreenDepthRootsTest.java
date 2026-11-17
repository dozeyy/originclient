package com.vulkairis.test;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.vulkairis.bridge.TerrainTargets;
import com.vulkairis.bridge.VulkanModProgramBridge;
import com.vulkairis.rendering.TransformLedger;
import com.vulkairis.translation.LegacyGlsl;
import com.vulkairis.translation.ProgramTranslator;
import com.vulkairis.translation.ShaderCompileResult;
import com.vulkairis.translation.ShaderTranslator;
import com.vulkairis.translation.SpirvCompiler;
import com.vulkairis.translation.TranslationCache;
import com.vulkairis.vulkan.pipeline.VulkanPipelineFactory;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.vulkan.VK10;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase B gaps B-6/B-7: forward-Z depth contract and fullscreen/entity/sky vertex roots.
 *
 * <p>Depth (B-6): the VulkanMod driver path is forward-Z throughout (range {@code [0, 1]},
 * clear {@code 1.0}, compare {@code LESS_OR_EQUAL}); the {@code [-1, 1]}&#8594;{@code [0, 1]}
 * correction is applied exactly once on matrix upload ({@code UniformBridge}), never in the
 * shader source. Translation therefore performs no reversed-Z rewrite: {@code gl_FragCoord}
 * and {@code gl_FragDepth} pass through untouched and shadow clear semantics are never
 * altered here. These tests pin that contract: classifier, pass-through, SPIR-V compiles,
 * and cross-layer agreement on clear depth and compare op.</p>
 *
 * <p>Vertex roots (B-7): the fullscreen/composite pair below reuses the exact
 * {@code testpack/ShaderTestPacks.fullscreenVertex} harness shape ({@code iris_Position} +
 * {@code iris_UV} composite root) and proves it translates, selects {@code POSITION_TEX},
 * and compiles end to end on the link path. Entity/sky roots are covered by format-selection
 * assertions over the inputs the terrain/entity/cloud/sky goldens declare.</p>
 *
 * <p>Inverse matrices ({@code gl_ModelViewMatrixInverse} and kin) stay deliberately
 * compiler-reported: common in BSL-family packs, expected to fail translation with a named
 * undeclared identifier until a future phase supplies them from
 * {@code RenderMatrices#getInverseView}/{@code getInverseProjection}.</p>
 */
public class FullscreenDepthRootsTest {

    // ------------------------------------------------------------------
    // Fixtures: the harness fullscreen shape + a depth-writing composite.
    // ------------------------------------------------------------------

    /** Exact {@code ShaderTestPacks.fullscreenVertex} shape: Iris composite root. */
    static String fullscreenVertex() {
        return """
                #version 150 core
                in vec3 iris_Position;
                in vec2 iris_UV;
                uniform mat4 projection;
                out vec2 vUv;
                void main() {
                    vUv = iris_UV;
                    gl_Position = projection * vec4(iris_Position, 1.0);
                }
                """;
    }

    static String compositeCopyFragment() {
        return """
                #version 150 core
                uniform sampler2D colortex0;
                in vec2 vUv;
                out vec4 fragColor;
                void main() {
                    fragColor = texture(colortex0, vUv);
                }
                """;
    }

    static String depthWriteFragment() {
        return """
                #version 150 core
                uniform sampler2D depthtex0;
                in vec2 vUv;
                out vec4 fragColor;
                void main() {
                    float d = texture(depthtex0, vUv).r;
                    float w = gl_FragCoord.z;
                    gl_FragDepth = d * w;
                    fragColor = vec4(vec3(d), 1.0);
                }
                """;
    }

    private static void clearCaches() {
        TranslationCache.clearForTests();
        ProgramTranslator.clearCacheForTests();
    }

    private static ProgramTranslator.TranslatedProgram translatePair(
            String program, String vertex, String fragment) {
        clearCaches();
        return ProgramTranslator.translate(program, List.of(
                new ProgramTranslator.StageUnit("VERTEX", vertex, program + ".vsh"),
                new ProgramTranslator.StageUnit("FRAGMENT", fragment, program + ".fsh")),
                "golden-pack");
    }

    private static void assertCompiles(String name, String glsl, int shadercKind) {
        ShaderCompileResult result =
                SpirvCompiler.compileGlslToSpirvDetailed(name, glsl, shadercKind);
        Assertions.assertTrue(result.succeeded(),
                "Translated stage must compile: " + name + ": " + result.compilerLog());
        Assertions.assertNotNull(result.spirv());
        Assertions.assertTrue(result.spirv().remaining() > 0);
    }

    /**
     * Shared varying locations over translated sources: every vertex {@code out} in
     * declaration order, then uncovered fragment {@code in}s (mirrors the bridge's
     * {@code buildVaryingLocations} slot discipline without touching {@code link()}).
     */
    private static Map<String, Integer> varyingLocations(String vertSrc, String fragSrc) {
        Map<String, Integer> varyings = new LinkedHashMap<>();
        int nextLocation = 0;
        for (Map.Entry<String, Integer> entry
                : ShaderTranslator.collectInterfaceSlots(vertSrc, "out").entrySet()) {
            varyings.put(entry.getKey(), nextLocation);
            nextLocation += entry.getValue();
        }
        for (Map.Entry<String, Integer> entry
                : ShaderTranslator.collectInterfaceSlots(fragSrc, "in").entrySet()) {
            if (!varyings.containsKey(entry.getKey())) {
                varyings.put(entry.getKey(), nextLocation);
                nextLocation += entry.getValue();
            }
        }
        return varyings;
    }

    /**
     * Runs the device-free link-path selection steps (dense bindings, format cover,
     * location injection) over union-translated sources and compiles both final stages.
     */
    private static void assertLinkPathCompiles(String program, String vert, String frag) {
        Map<String, Integer> bindings = ShaderTranslator.buildUnifiedBindingMap(vert, frag);
        String vertRemapped = ShaderTranslator.ensureDescriptorBindings(
                ShaderTranslator.remapDescriptorBindings(vert, bindings), bindings);
        String fragRemapped = ShaderTranslator.ensureDescriptorBindings(
                ShaderTranslator.remapDescriptorBindings(frag, bindings), bindings);

        List<String> required = ShaderTranslator.collectVertexInputNames(vertRemapped);
        VertexFormat format = VulkanModProgramBridge.chooseVertexFormat(required);
        Assertions.assertNotNull(format,
                "Translated vertex inputs must select a real format: " + required);
        Map<String, Integer> vertexLocations =
                VulkanModProgramBridge.buildVertexInputLocations(format);
        for (String name : required) {
            Assertions.assertTrue(vertexLocations.containsKey(name),
                    "Every vertex input needs a location, missing: " + name);
        }

        Map<String, Integer> varyings = varyingLocations(vertRemapped, fragRemapped);
        String vertFinal = ShaderTranslator.injectLayoutLocations(
                vertRemapped, true, vertexLocations, varyings);
        String fragFinal = ShaderTranslator.injectLayoutLocations(
                fragRemapped, false, vertexLocations, varyings);

        assertCompiles(program + ".vsh", vertFinal, Shaderc.shaderc_glsl_vertex_shader);
        assertCompiles(program + ".fsh", fragFinal, Shaderc.shaderc_glsl_fragment_shader);
    }

    // ------------------------------------------------------------------
    // B-6: forward-Z depth contract.
    // ------------------------------------------------------------------

    @Test
    public void testForwardZDepthBuiltinsPassThrough() {
        // Classifier: the two core depth builtins pass through; everything
        // depth-adjacent outside them stays compiler-reported.
        Assertions.assertTrue(LegacyGlsl.isDepthPassthroughBuiltin("gl_FragDepth"));
        Assertions.assertTrue(LegacyGlsl.isDepthPassthroughBuiltin("gl_FragCoord"));
        Assertions.assertFalse(LegacyGlsl.isDepthPassthroughBuiltin("gl_DepthRange"));
        Assertions.assertFalse(LegacyGlsl.isDepthPassthroughBuiltin("gl_ModelViewMatrixInverse"));
        Assertions.assertFalse(LegacyGlsl.isDepthPassthroughBuiltin(null));
        Assertions.assertFalse(LegacyGlsl.isDepthPassthroughBuiltin("gl_FragDepthExtra"));

        String source = """
                #version 450 core
                void main() {
                    float d = gl_FragCoord.z;
                    gl_FragDepth = d;
                }
                """;
        LegacyGlsl.LegacyResult result = LegacyGlsl.translateLegacyBuiltins(source, false);
        Assertions.assertTrue(result.source().contains("gl_FragCoord"), result.source());
        Assertions.assertTrue(result.source().contains("gl_FragDepth"), result.source());

        // The depth-writing composite pair keeps both builtins through the union
        // translation with no inversion, and both stages compile.
        ProgramTranslator.TranslatedProgram program =
                translatePair("composite_depth", fullscreenVertex(), depthWriteFragment());
        String vert = program.stages().get("VERTEX").glsl();
        String frag = program.stages().get("FRAGMENT").glsl();
        Assertions.assertTrue(frag.contains("gl_FragCoord"), frag);
        Assertions.assertTrue(frag.contains("gl_FragDepth"), frag);
        Assertions.assertFalse(frag.contains("1.0 -") || frag.contains("1 - "),
                "Translation must never invert depth:\n" + frag);
        assertCompiles("composite_depth.vsh", vert, Shaderc.shaderc_glsl_vertex_shader);
        assertCompiles("composite_depth.fsh", frag, Shaderc.shaderc_glsl_fragment_shader);
        assertLinkPathCompiles("composite_depth", vert, frag);
    }

    @Test
    public void testForwardZClearAndCompareAgreement() {
        // Translation, targets and pipelines agree: forward-Z clear 1.0, LESS_OR_EQUAL.
        // Shadow clear semantics live in the target layer and are asserted unchanged here.
        Assertions.assertEquals(1.0f, TransformLedger.VULKAN_CLEAR_DEPTH, 1e-6f);
        Assertions.assertEquals(1.0f, TerrainTargets.SHADOW_CLEAR_DEPTH, 1e-6f);
        Assertions.assertEquals(TransformLedger.VULKAN_CLEAR_DEPTH,
                TerrainTargets.SHADOW_CLEAR_DEPTH, 1e-6f,
                "Scene and shadow clears share the forward-Z far plane");
        Assertions.assertEquals(VK10.VK_COMPARE_OP_LESS_OR_EQUAL,
                VulkanPipelineFactory.DEFAULT_DEPTH_COMPARE_OP,
                "Forward-Z compares LESS_OR_EQUAL; a reversed-Z GREATER path must not appear");
        Assertions.assertEquals(0.0f, TransformLedger.ndcDepthToVulkan(-1.0f), 1e-6f);
        Assertions.assertEquals(1.0f, TransformLedger.ndcDepthToVulkan(1.0f), 1e-6f);
    }

    // ------------------------------------------------------------------
    // B-7: fullscreen/composite pair golden + entity/sky roots.
    // ------------------------------------------------------------------

    @Test
    public void testFullscreenCompositePairTranslatesAndLinks() {
        ProgramTranslator.TranslatedProgram program =
                translatePair("composite_copy", fullscreenVertex(), compositeCopyFragment());

        String vert = program.stages().get("VERTEX").glsl();
        String frag = program.stages().get("FRAGMENT").glsl();

        // The Iris composite root normalizes onto the vanilla fullscreen inputs.
        Assertions.assertTrue(vert.contains("Position"), vert);
        Assertions.assertTrue(vert.contains("UV0"), vert);
        Assertions.assertFalse(vert.contains("iris_"), vert);
        Assertions.assertFalse(frag.contains("iris_"), frag);
        Assertions.assertTrue(vert.contains("mat4 projection;"), vert);
        Assertions.assertTrue(frag.contains("mat4 projection;"), frag);
        Assertions.assertEquals("both", program.unionVaryings().get("vUv"), program.toString());

        // Binding policy: no bindings, no vertex locations, one fragment output location.
        Assertions.assertFalse(vert.contains("layout(binding"), vert);
        Assertions.assertFalse(frag.contains("layout(binding"), frag);
        Assertions.assertFalse(vert.contains("layout(location"), vert);
        Assertions.assertEquals(Map.of("fragColor", 0), program.fragmentOutputLocations());

        assertCompiles("composite_copy.vsh", vert, Shaderc.shaderc_glsl_vertex_shader);
        assertCompiles("composite_copy.fsh", frag, Shaderc.shaderc_glsl_fragment_shader);

        // Format selection: the composite root is exactly Position + UV0.
        List<String> required = ShaderTranslator.collectVertexInputNames(vert);
        Assertions.assertEquals(List.of("Position", "UV0"), required,
                "Fullscreen root must declare exactly the composite inputs:\n" + vert);
        Assertions.assertSame(DefaultVertexFormat.POSITION_TEX,
                VulkanModProgramBridge.chooseVertexFormat(required));

        assertLinkPathCompiles("composite_copy", vert, frag);
    }

    @Test
    public void testProceduralFullscreenInputsSelectSmallestFormat() {
        Assertions.assertTrue(VulkanModProgramBridge.isProceduralFullscreenInputs(List.of()));
        Assertions.assertTrue(VulkanModProgramBridge.isProceduralFullscreenInputs(null));
        Assertions.assertFalse(VulkanModProgramBridge.isProceduralFullscreenInputs(
                List.of("Position")));

        // An attribute-less fullscreen triangle still links against a real format:
        // the smallest candidate, whose attributes the shader simply ignores.
        Assertions.assertSame(DefaultVertexFormat.POSITION,
                VulkanModProgramBridge.chooseVertexFormat(List.of()));
        Assertions.assertSame(DefaultVertexFormat.POSITION,
                VulkanModProgramBridge.chooseVertexFormat(null));
    }

    @Test
    public void testEntityAndSkyRootsSelectCoveringFormats() {
        // Sky root: position only.
        Assertions.assertSame(DefaultVertexFormat.POSITION,
                VulkanModProgramBridge.chooseVertexFormat(List.of("Position")));

        // Entity root as declared by the entity golden (Position/UV0 plus the
        // gl_Color/gl_Normal legacy attribs through the alias map).
        List<String> entityInputs =
                List.of("Position", "UV0", "vulkairis_Color", "vulkairis_Normal");
        VertexFormat entityFormat = VulkanModProgramBridge.chooseVertexFormat(entityInputs);
        Assertions.assertNotNull(entityFormat, "Entity root must select a covering format");
        Assertions.assertSame(DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL, entityFormat);
        Map<String, Integer> entityLocations =
                VulkanModProgramBridge.buildVertexInputLocations(entityFormat);
        for (String name : entityInputs) {
            Assertions.assertTrue(entityLocations.containsKey(name),
                    "Entity input needs a location, missing: " + name);
        }

        // Full overlay data resolves onto NEW_ENTITY.
        List<String> overlayInputs =
                List.of("Position", "Color", "UV0", "UV1", "UV2", "Normal");
        Assertions.assertSame(DefaultVertexFormat.NEW_ENTITY,
                VulkanModProgramBridge.chooseVertexFormat(overlayInputs));
    }

    // ------------------------------------------------------------------
    // Documented gap: inverse matrices stay compiler-reported (BSL family).
    // ------------------------------------------------------------------

    @Test
    public void testInverseMatricesStayCompilerReported() {
        String source = """
                #version 450 core
                uniform mat4 projection;
                void main() {
                    vec4 view = gl_ModelViewMatrixInverse * vec4(1.0);
                    vec4 clip = gl_ProjectionMatrixInverse * view;
                    gl_Position = projection * clip;
                }
                """;

        LegacyGlsl.LegacyResult result = LegacyGlsl.translateLegacyBuiltins(source, true);

        // Left untouched for the compiler to name: inventing inverse values in
        // translation would silently corrupt depth and lighting.
        Assertions.assertTrue(result.source().contains("gl_ModelViewMatrixInverse"),
                result.source());
        Assertions.assertTrue(result.source().contains("gl_ProjectionMatrixInverse"),
                result.source());
        Assertions.assertFalse(result.needsModelViewProjection() || result.needsModelView()
                || result.needsProjection() || result.needsNormalMatrix(),
                "Inverse-only sources must not claim forward-matrix requirements");
        Assertions.assertFalse(LegacyGlsl.isDepthPassthroughBuiltin("gl_ModelViewMatrixInverse"));
    }
}
