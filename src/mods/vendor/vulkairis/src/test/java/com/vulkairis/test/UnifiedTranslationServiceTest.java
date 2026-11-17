package com.vulkairis.test;

import com.vulkairis.bridge.VulkanModProgramBridge;
import com.vulkairis.translation.GlObjectState;
import com.vulkairis.translation.ProgramTranslator;
import com.vulkairis.translation.ShaderTranslator;
import com.vulkairis.translation.UnifiedTranslationService;
import com.vulkairis.translation.VulkanProgramLinker;
import com.vulkairis.translation.VulkanShaderRegistry;
import com.vulkairis.translation.VulkairisProgram;
import com.vulkairis.translation.VulkairisShader;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * Phase 4 test suite: verifies UnifiedTranslationService coordinates program-together
 * translation, recompilation, shader re-registration, and bridge pipeline layout compatibility.
 */
public class UnifiedTranslationServiceTest {

    private static final String TERRAIN_VSH = """
            #version 120
            attribute vec3 Position;
            attribute vec2 UV0;
            uniform mat4 projection;
            uniform vec3 sunPosition;
            uniform sampler2D occlusionTex;
            varying vec2 texCoord;
            void main() {
                texCoord = UV0;
                vec4 uvTransform = gl_TextureMatrix[1] * vec4(UV0, 0.0, 1.0);
                float occluded = texture2DLod(occlusionTex, uvTransform.xy, 0.0).r;
                vec4 worldPos = vec4(Position + sunPosition * occluded, 1.0);
                gl_Position = projection * worldPos + ftransform() * 0.0;
            }
            """;

    private static final String TERRAIN_FSH = """
            #version 120
            uniform sampler2D gtexture;
            uniform sampler2DShadow shadowtex0;
            uniform float rainStrength = 1.0;
            varying vec2 texCoord;
            void main() {
                vec4 albedo = texture2D(gtexture, texCoord);
                float shadow = shadow2D(shadowtex0, vec3(texCoord, 0.5));
                gl_FragData[0] = albedo * shadow * rainStrength;
            }
            """;

    @BeforeEach
    public void setUp() {
        UnifiedTranslationService.reset();
        ProgramTranslator.clearCacheForTests();
        VulkanShaderRegistry.cleanUp();
    }

    @Test
    public void testBufferRawSourceAndRetrieve() {
        UnifiedTranslationService.bufferRawSource(1001, "gbuffers_terrain.vsh", TERRAIN_VSH, "VERTEX");
        UnifiedTranslationService.bufferRawSource(1002, "gbuffers_terrain.fsh", TERRAIN_FSH, "FRAGMENT");

        Assertions.assertEquals(2, UnifiedTranslationService.bufferedCount());

        UnifiedTranslationService.BufferedSource vertBuf = UnifiedTranslationService.getBuffered(1001);
        Assertions.assertNotNull(vertBuf);
        Assertions.assertEquals("gbuffers_terrain.vsh", vertBuf.name());
        Assertions.assertEquals("VERTEX", vertBuf.stage());
        Assertions.assertEquals(TERRAIN_VSH, vertBuf.rawGlsl());

        UnifiedTranslationService.BufferedSource fragBuf = UnifiedTranslationService.getBuffered(1002);
        Assertions.assertNotNull(fragBuf);
        Assertions.assertEquals("gbuffers_terrain.fsh", fragBuf.name());
        Assertions.assertEquals("FRAGMENT", fragBuf.stage());
        Assertions.assertEquals(TERRAIN_FSH, fragBuf.rawGlsl());
    }

    @Test
    public void testTranslateProgramUnifiesStagesAndRecompiles() {
        // 1. Initial stage registration (simulating GlShaderMixin per-stage intercepts)
        int vertId = VulkanShaderRegistry.registerShader("gbuffers_terrain.vsh", 35633, null, TERRAIN_VSH);
        int fragId = VulkanShaderRegistry.registerShader("gbuffers_terrain.fsh", 35632, null, TERRAIN_FSH);

        UnifiedTranslationService.bufferRawSource(vertId, "gbuffers_terrain.vsh", TERRAIN_VSH, "VERTEX");
        UnifiedTranslationService.bufferRawSource(fragId, "gbuffers_terrain.fsh", TERRAIN_FSH, "FRAGMENT");

        // 2. Program-together translation triggered by ProgramCreatorMixin
        UnifiedTranslationService.TranslationOutcome outcome =
                UnifiedTranslationService.translateProgram("gbuffers_terrain", List.of(vertId, fragId));

        Assertions.assertTrue(outcome.succeeded(), "Translation and recompilation must succeed: " + outcome.errors());
        Assertions.assertNotNull(outcome.program());
        Assertions.assertTrue(outcome.recompiled());
        Assertions.assertTrue(outcome.errors().isEmpty());

        // 3. Verify shaders in VulkanShaderRegistry now hold updated unified GLSL, SPIR-V and reflection
        VulkairisShader vertShader = VulkanShaderRegistry.getShader(vertId);
        Assertions.assertNotNull(vertShader);
        Assertions.assertNotNull(vertShader.getSpirv(), "Vertex shader must have compiled SPIR-V");
        Assertions.assertTrue(vertShader.getSpirv().remaining() > 0);
        Assertions.assertNotNull(vertShader.glslSource());
        Assertions.assertTrue(vertShader.glslSource().contains("VulkairisUniformBlock"),
                "Vertex shader must contain unified uniform block");
        Assertions.assertFalse(vertShader.glslSource().contains("gl_TextureMatrix"),
                "Legacy gl_TextureMatrix must be translated away");

        VulkairisShader fragShader = VulkanShaderRegistry.getShader(fragId);
        Assertions.assertNotNull(fragShader);
        Assertions.assertNotNull(fragShader.getSpirv(), "Fragment shader must have compiled SPIR-V");
        Assertions.assertTrue(fragShader.getSpirv().remaining() > 0);
        Assertions.assertNotNull(fragShader.glslSource());
        Assertions.assertTrue(fragShader.glslSource().contains("VulkairisUniformBlock"),
                "Fragment shader must contain unified uniform block");
        Assertions.assertFalse(fragShader.glslSource().contains("gl_FragData"),
                "Legacy gl_FragData must be translated away");
        Assertions.assertTrue(fragShader.glslSource().contains("vulkairisFragData0"),
                "Fragment output must use canonical indexed name");

        // Cross-stage uniform union check: rainStrength (from frag) must be in vertex block too
        Assertions.assertTrue(vertShader.glslSource().contains("float rainStrength;"));
        Assertions.assertTrue(fragShader.glslSource().contains("float rainStrength;"));
    }

    @Test
    public void testTranslateProgramIdempotentCache() {
        int vertId = VulkanShaderRegistry.registerShader("gbuffers_terrain.vsh", 35633, null, TERRAIN_VSH);
        int fragId = VulkanShaderRegistry.registerShader("gbuffers_terrain.fsh", 35632, null, TERRAIN_FSH);

        UnifiedTranslationService.bufferRawSource(vertId, "gbuffers_terrain.vsh", TERRAIN_VSH, "VERTEX");
        UnifiedTranslationService.bufferRawSource(fragId, "gbuffers_terrain.fsh", TERRAIN_FSH, "FRAGMENT");

        UnifiedTranslationService.TranslationOutcome first =
                UnifiedTranslationService.translateProgram("gbuffers_terrain", List.of(vertId, fragId));
        UnifiedTranslationService.TranslationOutcome second =
                UnifiedTranslationService.translateProgram("gbuffers_terrain", List.of(vertId, fragId));

        Assertions.assertSame(first, second, "Subsequent calls with identical inputs must return cached outcome");
    }

    @Test
    public void testTranslateProgramWithRegistryFallback() {
        // Register shaders directly without calling bufferRawSource
        int vertId = VulkanShaderRegistry.registerShader("fallback.vsh", 35633, null, TERRAIN_VSH);
        int fragId = VulkanShaderRegistry.registerShader("fallback.fsh", 35632, null, TERRAIN_FSH);

        UnifiedTranslationService.TranslationOutcome outcome =
                UnifiedTranslationService.translateProgram("fallback_prog", List.of(vertId, fragId));

        Assertions.assertTrue(outcome.succeeded(), "Registry fallback must succeed: " + outcome.errors());
        Assertions.assertNotNull(VulkanShaderRegistry.getShader(vertId).getSpirv());
        Assertions.assertNotNull(VulkanShaderRegistry.getShader(fragId).getSpirv());
    }

    @Test
    public void testTranslateProgramHandlesInvalidSource() {
        String invalidGlsl = "#version 450 core\nvoid main() { syntax error here !! }";
        int vertId = VulkanShaderRegistry.registerShader("bad.vsh", 35633, null, invalidGlsl);
        UnifiedTranslationService.bufferRawSource(vertId, "bad.vsh", invalidGlsl, "VERTEX");

        UnifiedTranslationService.TranslationOutcome outcome =
                UnifiedTranslationService.translateProgram("bad_prog", List.of(vertId));

        Assertions.assertFalse(outcome.succeeded());
        Assertions.assertFalse(outcome.errors().isEmpty());
    }

    @Test
    public void testResetClearsBuffersAndOutcomes() {
        UnifiedTranslationService.bufferRawSource(1001, "test.vsh", TERRAIN_VSH, "VERTEX");
        Assertions.assertEquals(1, UnifiedTranslationService.bufferedCount());

        UnifiedTranslationService.reset();
        Assertions.assertEquals(0, UnifiedTranslationService.bufferedCount());
        Assertions.assertEquals(0, UnifiedTranslationService.outcomeCount());
        Assertions.assertNull(UnifiedTranslationService.getBuffered(1001));
    }

    @Test
    public void testUnifiedProgramPassesLinkValidationAndDescriptorRebind() {
        int vertId = VulkanShaderRegistry.registerShader("gbuffers_terrain.vsh", 35633, null, TERRAIN_VSH);
        int fragId = VulkanShaderRegistry.registerShader("gbuffers_terrain.fsh", 35632, null, TERRAIN_FSH);

        UnifiedTranslationService.bufferRawSource(vertId, "gbuffers_terrain.vsh", TERRAIN_VSH, "VERTEX");
        UnifiedTranslationService.bufferRawSource(fragId, "gbuffers_terrain.fsh", TERRAIN_FSH, "FRAGMENT");

        UnifiedTranslationService.translateProgram("gbuffers_terrain", List.of(vertId, fragId));

        int progId = VulkanShaderRegistry.registerProgram("gbuffers_terrain", List.of(vertId, fragId));
        VulkairisProgram program = VulkanShaderRegistry.getProgram(progId);
        Assertions.assertNotNull(program);

        // Validation against the unified translated program
        VulkanProgramLinker.LinkResult linkResult = VulkanProgramLinker.validate(program);
        Assertions.assertTrue(linkResult.valid(), "Link validation must succeed: " + linkResult.log());

        // Verify descriptor names include VulkairisUniformBlock
        VulkairisShader vert = program.getVertexShader();
        List<String> descriptors = ShaderTranslator.collectDescriptorNames(vert.glslSource());
        Assertions.assertTrue(descriptors.contains(ShaderTranslator.UNIFORM_BLOCK_NAME),
                "collectDescriptorNames must include VulkairisUniformBlock even without prior binding");

        // Verify dense remapping assigns binding 0 to VulkairisUniformBlock
        ShaderTranslator.BindingRemap remap = ShaderTranslator.remapDescriptorBindings(vert.glslSource());
        Assertions.assertEquals(Integer.valueOf(0), remap.bindings().get(ShaderTranslator.UNIFORM_BLOCK_NAME));
        Assertions.assertTrue(remap.source().contains("layout(std140, binding = 0) uniform VulkairisUniformBlock"));
    }
}
