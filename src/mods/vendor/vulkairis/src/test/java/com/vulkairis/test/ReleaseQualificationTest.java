package com.vulkairis.test;

import com.vulkairis.iris.SourceProvenance;
import com.vulkairis.resource.RenderTargetRegistry;
import com.vulkairis.translation.GlObjectState;
import com.vulkairis.translation.ShaderCompileCache;
import com.vulkairis.translation.SpirvCompiler;
import com.vulkairis.translation.ShaderCompileResult;
import com.vulkairis.translation.TranslationCache;
import com.vulkairis.translation.VirtualGlFacade;
import com.vulkairis.translation.VulkanShaderRegistry;
import com.vulkairis.translation.VulkairisShader;
import net.irisshaders.iris.gl.shader.ShaderType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.lwjgl.util.shaderc.Shaderc;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * Phase 13: release qualification proxies that run without a GPU.
 *
 * <p>Covers the repeatability sequence the plan's matrix requires headlessly: identical reloads
 * hit caches with identical bytes, generation changes invalidate deterministically, deleted ids
 * fail predictably, resize preserves the target set, and compile/load timings are recorded.
 * Game/GPU-gated matrix rows (clean-launch rendering, reference screenshots, frame-time lows)
 * stay tracked as pending evidence in the feature table and regression specs — this suite
 * proves everything provable here.</p>
 */
public class ReleaseQualificationTest {

    private static final String VERT = """
            #version 150 core
            in vec3 iris_Position;
            uniform mat4 projection;
            void main() {
                gl_Position = projection * vec4(iris_Position, 1.0);
            }
            """;

    private static String translated(String source, boolean vertex) {
        return com.vulkairis.translation.ShaderTranslator.preprocessGlsl(source, vertex);
    }

    @Test
    public void testIdenticalReloadHitsCachesWithIdenticalBytes() {
        TranslationCache.clearForTests();
        ShaderCompileCache.clearForTests();

        String firstTranslated = translated(VERT, true);
        ShaderCompileResult first = SpirvCompiler.compileGlslToSpirvDetailed(
                "qual_vert", firstTranslated, Shaderc.shaderc_glsl_vertex_shader);
        Assertions.assertEquals(ShaderCompileResult.Status.SUCCESS, first.status());

        // Identical reload: same translation, same bytes, served from cache.
        String secondTranslated = translated(VERT, true);
        Assertions.assertEquals(firstTranslated, secondTranslated);
        ShaderCompileResult second = SpirvCompiler.compileGlslToSpirvDetailed(
                "qual_vert", secondTranslated, Shaderc.shaderc_glsl_vertex_shader);
        Assertions.assertEquals(ShaderCompileResult.Status.SUCCESS, second.status());
        Assertions.assertTrue(TranslationCache.hits() >= 1, "translation must hit on reload");
        Assertions.assertTrue(ShaderCompileCache.hits() >= 1, "compilation must hit on reload");
        Assertions.assertEquals(first.spirv().remaining(), second.spirv().remaining());
        ByteBuffer a = first.spirv().duplicate();
        ByteBuffer b = second.spirv().duplicate();
        while (a.hasRemaining()) {
            Assertions.assertEquals(a.get(), b.get(), "cached bytes must be identical");
        }
    }

    @Test
    public void testReloadTimingsRecorded() {
        TranslationCache.clearForTests();
        ShaderCompileCache.clearForTests();

        long coldStart = System.nanoTime();
        String coldTranslated = translated(VERT, true);
        ShaderCompileResult cold = SpirvCompiler.compileGlslToSpirvDetailed(
                "qual_bench", coldTranslated, Shaderc.shaderc_glsl_vertex_shader);
        long coldMs = (System.nanoTime() - coldStart) / 1_000_000L;

        long warmStart = System.nanoTime();
        String warmTranslated = translated(VERT, true);
        ShaderCompileResult warm = SpirvCompiler.compileGlslToSpirvDetailed(
                "qual_bench", warmTranslated, Shaderc.shaderc_glsl_vertex_shader);
        long warmMs = (System.nanoTime() - warmStart) / 1_000_000L;

        Assertions.assertEquals(ShaderCompileResult.Status.SUCCESS, cold.status());
        Assertions.assertEquals(ShaderCompileResult.Status.SUCCESS, warm.status());
        Assertions.assertTrue(cold.durationMs() >= 0 && warm.durationMs() >= 0);
        System.out.println("[Qualification] cold load " + coldMs + "ms (compile "
                + cold.durationMs() + "ms), warm load " + warmMs + "ms (compile "
                + warm.durationMs() + "ms), translation hits=" + TranslationCache.hits()
                + ", spirv hits=" + ShaderCompileCache.hits());
    }

    @Test
    public void testGenerationChangeInvalidatesRepeatably() {
        VulkairisShader shader = SpirvCompiler.compileShader("qual_cycle", VERT, ShaderType.VERTEX);
        int id = shader.virtualId();
        com.vulkairis.iris.SourceUnit unit = com.vulkairis.iris.SourceProvenance.capture(
                "qual-pack", "qual_cycle", "VERTEX", VERT);
        Assertions.assertEquals(GlObjectState.COMPILED, VirtualGlFacade.shaderState(id));
        Assertions.assertFalse(com.vulkairis.iris.SourceProvenance.isStale(unit));

        SourceProvenance.nextGeneration("qualification reload");

        Assertions.assertTrue(com.vulkairis.iris.SourceProvenance.isStale(unit),
                "plan reloads invalidate provenance deterministically");
        Assertions.assertEquals(GlObjectState.COMPILED, VirtualGlFacade.shaderState(id),
                "device handles survive plan reloads; only device recreation invalidates them");
        Assertions.assertEquals(1, VirtualGlFacade.getShaderi(id, 35713));
    }

    @Test
    public void testDeletedCycleFailsPredictably() {
        int baseline = VulkanShaderRegistry.registeredShaderCount();
        VulkairisShader shader = SpirvCompiler.compileShader("qual_gone", VERT, ShaderType.VERTEX);
        int id = shader.virtualId();
        VulkanShaderRegistry.deleteShader(id);
        Assertions.assertEquals(GlObjectState.DELETED, VirtualGlFacade.shaderState(id));
        Assertions.assertEquals(0, VirtualGlFacade.getShaderi(id, 35713));
        Assertions.assertFalse(VirtualGlFacade.getShaderInfoLog(id).isBlank());
        Assertions.assertEquals(baseline, VulkanShaderRegistry.registeredShaderCount(),
                "deleted shaders must not leak live objects");
    }

    @Test
    public void testResizePreservesTargetSet() {
        RenderTargetRegistry registry = RenderTargetRegistry.standard(1920, 1080, 1024);
        List<String> before = registry.names();
        registry.resizeAll(1280, 720);
        Assertions.assertEquals(before, registry.names(), "resize must not add or drop targets");
        Assertions.assertEquals(1280, registry.getWidth());
        Assertions.assertEquals(720, registry.getHeight());
        registry.resizeAllExcept(
                java.util.Set.of(RenderTargetRegistry.SHADOW_DEPTH, RenderTargetRegistry.SHADOW_COLOR),
                800, 600);
        Assertions.assertEquals(800, registry.getWidth());
        Assertions.assertEquals(1280, registry.get(RenderTargetRegistry.SHADOW_DEPTH)
                .orElseThrow().getWidth(),
                "fixed-resolution shadow maps survive resizes untouched");
        registry.destroyAll();
    }

    @Test
    public void testNegativePathsStayClean() {
        // Unsupported stage: rejected, contained, explained.
        VulkairisShader rejected = SpirvCompiler.compileShader(
                "qual_geo", VERT, ShaderType.GEOMETRY);
        Assertions.assertFalse(rejected.isValid());
        Assertions.assertEquals(GlObjectState.FAILED, VirtualGlFacade.shaderState(rejected.virtualId()));

        // Invalid syntax: failed status plus log, no module.
        VulkairisShader broken = SpirvCompiler.compileShader("qual_broken",
                "this is not glsl", ShaderType.FRAGMENT);
        Assertions.assertFalse(broken.isValid());
        Assertions.assertFalse(VirtualGlFacade.getShaderInfoLog(broken.virtualId()).isBlank());

        // Missing attachments: incomplete framebuffer, never complete.
        int status = com.vulkairis.rendering.FramebufferValidator
                .validate(4242, java.util.Map.of()).glStatus();
        Assertions.assertEquals(
                com.vulkairis.rendering.FramebufferValidator.MISSING_ATTACHMENT, status);
    }
}
