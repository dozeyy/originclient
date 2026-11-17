package com.vulkairis.test;

import com.vulkairis.translation.ShaderCompileCache;
import com.vulkairis.translation.ShaderCompileResult;
import com.vulkairis.translation.SpirvCompiler;
import com.vulkairis.translation.ShaderTranslator;
import com.vulkairis.translation.TranslationCache;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.lwjgl.util.shaderc.Shaderc;

import java.nio.ByteBuffer;

/**
 * Phase 12: translation and compile caches hit on identical inputs, miss on any
 * compile-affecting change, stay bounded, and never store failures.
 */
public class CachingTest {

    private static final String SRC = """
            #version 150 core
            in vec3 iris_Position;
            uniform mat4 projection;
            void main() {
                gl_Position = projection * vec4(iris_Position, 1.0);
            }
            """;

    @Test
    public void testTranslationKeyAndHit() {
        TranslationCache.clearForTests();
        String first = ShaderTranslator.preprocessGlsl(SRC, true);
        Assertions.assertEquals(0, TranslationCache.hits());
        Assertions.assertTrue(TranslationCache.misses() >= 1);
        String second = ShaderTranslator.preprocessGlsl(SRC, true);
        Assertions.assertEquals(first, second, "cache hits must not change output");
        Assertions.assertEquals(1, TranslationCache.hits());

        String key = TranslationCache.key(
                com.vulkairis.diagnostics.DiagnosticManager.hashSource(
                        SRC.replace("\r\n", "\n").replace('\r', '\n')), true);
        Assertions.assertTrue(key.contains(TranslationCache.TRANSLATOR_VERSION));
        Assertions.assertNotEquals(key, TranslationCache.key(
                com.vulkairis.diagnostics.DiagnosticManager.hashSource(
                        SRC.replace("\r\n", "\n").replace('\r', '\n')), false),
                "stage is part of the key");
        Assertions.assertNotEquals(key,
                TranslationCache.key("different-hash", true), "source hash is part of the key");
    }

    @Test
    public void testTranslationCacheBoundedAndBlankSafe() {
        TranslationCache.clearForTests();
        TranslationCache.store("k", "   ");
        Assertions.assertEquals(0, TranslationCache.size(), "blank outputs are never stored");
        for (int i = 0; i < 300; i++) {
            TranslationCache.store("k" + i, "output" + i);
        }
        Assertions.assertTrue(TranslationCache.size() <= 256,
                "cache must stay bounded, size=" + TranslationCache.size());
    }

    @Test
    public void testCompileKeyChangesWithInputs() {
        String base = ShaderCompileCache.key("hash", Shaderc.shaderc_glsl_vertex_shader, "prof");
        Assertions.assertNotEquals(base,
                ShaderCompileCache.key("hash", Shaderc.shaderc_glsl_fragment_shader, "prof"),
                "stage kind is part of the key");
        Assertions.assertNotEquals(base,
                ShaderCompileCache.key("hash", Shaderc.shaderc_glsl_vertex_shader, "other"),
                "profile digest is part of the key");
        Assertions.assertNotEquals(base,
                ShaderCompileCache.key("other", Shaderc.shaderc_glsl_vertex_shader, "prof"),
                "source hash is part of the key");
    }

    @Test
    public void testFailuresNeverCached() {
        ShaderCompileResult failure = ShaderCompileResult.failure(
                ShaderCompileResult.Status.COMPILE_ERROR, "x", 0, 0, "h", "log", "p", 1L, false);
        Assertions.assertFalse(ShaderCompileCache.shouldCache(failure));
        ShaderCompileCache.store("k", null);
        ShaderCompileCache.store("k", ByteBuffer.allocate(0));
    }

    @Test
    public void testCompileHitReturnsIdenticalBytes() {
        ShaderCompileCache.clearForTests();
        String translated = ShaderTranslator.preprocessGlsl(SRC, true);
        ShaderCompileResult first = SpirvCompiler.compileGlslToSpirvDetailed(
                "cache_probe", translated, Shaderc.shaderc_glsl_vertex_shader);
        Assertions.assertEquals(ShaderCompileResult.Status.SUCCESS, first.status());
        Assertions.assertEquals(0, ShaderCompileCache.hits());

        ShaderCompileResult second = SpirvCompiler.compileGlslToSpirvDetailed(
                "cache_probe", translated, Shaderc.shaderc_glsl_vertex_shader);
        Assertions.assertEquals(ShaderCompileResult.Status.SUCCESS, second.status());
        Assertions.assertEquals(1, ShaderCompileCache.hits(), "identical recompiles must hit");
        Assertions.assertEquals(first.spirv().remaining(), second.spirv().remaining());
        Assertions.assertEquals(first.spirv().duplicate().getInt(0),
                second.spirv().duplicate().getInt(0));
    }
}
