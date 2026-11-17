package com.vulkairis.test;

import com.vulkairis.diagnostics.DiagnosticManager;
import com.vulkairis.iris.SourceProvenance;
import com.vulkairis.translation.ShaderCompileCache;
import com.vulkairis.translation.ShaderCompileResult;
import com.vulkairis.translation.ShaderTranslator;
import com.vulkairis.translation.ShaderWarmup;
import com.vulkairis.translation.SpirvCompiler;
import com.vulkairis.translation.TranslationCache;
import com.vulkairis.translation.VulkairisShader;
import net.irisshaders.iris.gl.shader.ShaderType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.lwjgl.util.shaderc.Shaderc;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Phase C: compile-path hardening — bounded stack/slot discipline, oversized-source
 * rejection, versioned module cache with bounded disk persistence, translation keys over
 * expanded text + program + defines, worker warmup before draw, and continued
 * tessellation/geometry/compute rejection with explicit reasons.
 */
public class CompileHardeningTest {

    private static final String TINY_VERT = """
            #version 450 core
            layout(location = 0) in vec3 pos;
            void main() {
                gl_Position = vec4(pos, 1.0);
            }
            """;

    private static final String TINY_FRAG = """
            #version 450 core
            layout(location = 0) out vec4 fragColor;
            void main() {
                fragColor = vec4(1.0, 0.5, 0.0, 1.0);
            }
            """;

    private static String distinctFrag(int index) {
        return "#version 450 core\n"
                + "layout(location = 0) out vec4 fragColor;\n"
                + "void main() { fragColor = vec4(" + index + ".0 / 255.0, 0.0, 0.0, 1.0); }\n";
    }

    @Test
    public void testOversizedSourceRejectedWithReason() {
        StringBuilder huge = new StringBuilder("#version 450 core\n");
        huge.append("layout(location = 0) out vec4 fragColor;\n");
        while (huge.length() <= SpirvCompiler.MAX_SOURCE_CHARS + 1024) {
            huge.append("void filler").append(huge.length()).append("() {}\n");
        }
        String oversized = huge.toString();
        Assertions.assertTrue(oversized.length() > SpirvCompiler.MAX_SOURCE_CHARS);

        ShaderCompileResult result = Assertions.assertDoesNotThrow(
                () -> SpirvCompiler.compileGlslToSpirvDetailed(
                        "oversized.fsh", oversized, Shaderc.shaderc_glsl_fragment_shader),
                "oversized sources must never throw out of the compiler");
        Assertions.assertEquals(ShaderCompileResult.Status.COMPILE_ERROR, result.status());
        Assertions.assertFalse(result.succeeded());
        Assertions.assertNull(result.spirv(), "rejection must never yield bytecode");
        Assertions.assertTrue(result.compilerLog().contains(String.valueOf(SpirvCompiler.MAX_SOURCE_CHARS)),
                "rejection must name the limit: " + result.compilerLog());
        Assertions.assertFalse(result.fatal(), "oversize is expected, not fatal");

        VulkairisShader contained = Assertions.assertDoesNotThrow(
                () -> SpirvCompiler.compileShader("oversized_handle", oversized,
                        ShaderType.FRAGMENT),
                "the handle path must also contain oversize without throwing");
        Assertions.assertNotNull(contained);
        Assertions.assertFalse(contained.isValid(), "oversize must not look valid");
    }

    @Test
    public void testConcurrentCompilesStayCapped() throws Exception {
        ShaderCompileCache.clearForTests();
        SpirvCompiler.resetConcurrencyStatsForTests();
        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch gate = new CountDownLatch(1);
            List<Future<ShaderCompileResult>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                final int index = i;
                futures.add(pool.submit(() -> {
                    gate.await(30, TimeUnit.SECONDS);
                    return SpirvCompiler.compileGlslToSpirvDetailed(
                            "concurrent-" + index, distinctFrag(index),
                            Shaderc.shaderc_glsl_fragment_shader);
                }));
            }
            gate.countDown();
            for (Future<ShaderCompileResult> future : futures) {
                ShaderCompileResult result = future.get(120, TimeUnit.SECONDS);
                Assertions.assertEquals(ShaderCompileResult.Status.SUCCESS, result.status(),
                        "every concurrent compile must succeed: " + result.compilerLog());
                Assertions.assertTrue(result.succeeded());
            }
        } finally {
            pool.shutdownNow();
        }
        int observed = SpirvCompiler.maxObservedConcurrency();
        Assertions.assertTrue(observed >= 1, "concurrency must have been observed");
        Assertions.assertTrue(observed <= SpirvCompiler.maxConcurrentCompiles(),
                "native compiles must stay capped: observed=" + observed
                        + " cap=" + SpirvCompiler.maxConcurrentCompiles());
    }

    @Test
    public void testCacheColdThenWarm() {
        ShaderCompileCache.clearForTests();
        String translated = ShaderTranslator.preprocessGlsl(TINY_FRAG, false);

        ShaderCompileResult cold = SpirvCompiler.compileGlslToSpirvDetailed(
                "cold_warm", translated, Shaderc.shaderc_glsl_fragment_shader);
        Assertions.assertEquals(ShaderCompileResult.Status.SUCCESS, cold.status());
        Assertions.assertEquals(0, ShaderCompileCache.hits(), "first compile must miss");
        Assertions.assertTrue(ShaderCompileCache.misses() >= 1);

        ShaderCompileResult warm = SpirvCompiler.compileGlslToSpirvDetailed(
                "cold_warm", translated, Shaderc.shaderc_glsl_fragment_shader);
        Assertions.assertEquals(ShaderCompileResult.Status.SUCCESS, warm.status());
        Assertions.assertEquals(1, ShaderCompileCache.hits(), "identical recompile must hit");
        Assertions.assertEquals(cold.spirv().remaining(), warm.spirv().remaining());
        Assertions.assertEquals(cold.spirv().duplicate().getInt(0),
                warm.spirv().duplicate().getInt(0), "cache hits must not change bytes");
    }

    @Test
    public void testVersionedKeySeparatesStamps() {
        String withA = ShaderCompileCache.key("hash", Shaderc.shaderc_glsl_vertex_shader,
                "nodefines", "prof", "mod=1|mc=1.21.1|loader=1|lwjgl=1");
        String withB = ShaderCompileCache.key("hash", Shaderc.shaderc_glsl_vertex_shader,
                "nodefines", "prof", "mod=2|mc=1.21.1|loader=1|lwjgl=1");
        Assertions.assertNotEquals(withA, withB, "a version change must invalidate the key");
        Assertions.assertEquals(withA, ShaderCompileCache.key("hash",
                Shaderc.shaderc_glsl_vertex_shader, "nodefines", "prof",
                "mod=1|mc=1.21.1|loader=1|lwjgl=1"), "identical inputs must key identically");
        Assertions.assertNotEquals(withA, ShaderCompileCache.key("hash",
                Shaderc.shaderc_glsl_vertex_shader, "defines-abc", "prof",
                "mod=1|mc=1.21.1|loader=1|lwjgl=1"), "defines are part of the key");

        String stamp = ShaderCompileCache.versionStamp();
        Assertions.assertNotNull(stamp);
        Assertions.assertTrue(stamp.contains("mc="), "stamp must carry the game version: " + stamp);
        Assertions.assertEquals(stamp, ShaderCompileCache.versionStamp(), "stamp must be stable");

        Assertions.assertEquals("nodefines", ShaderCompileCache.definesDigest(null));
        Assertions.assertEquals("nodefines", ShaderCompileCache.definesDigest(Map.of()));
        String digest = ShaderCompileCache.definesDigest(Map.of("QUALITY", "1"));
        Assertions.assertNotEquals("nodefines", digest);
        Assertions.assertEquals(digest,
                ShaderCompileCache.definesDigest(Map.of("QUALITY", "1")),
                "defines digest must be deterministic");
        Assertions.assertNotEquals(digest,
                ShaderCompileCache.definesDigest(Map.of("QUALITY", "2")),
                "a define value change must alter the digest");
    }

    @Test
    public void testDefinesChangeCompilationKey() {
        ShaderCompileCache.clearForTests();
        Map<String, String> defines = Map.of("WARMUP_PROBE", "1");
        ShaderCompileResult plain = SpirvCompiler.compileGlslToSpirvDetailed(
                "defines_probe", TINY_FRAG, Shaderc.shaderc_glsl_fragment_shader, null);
        Assertions.assertEquals(ShaderCompileResult.Status.SUCCESS, plain.status());
        ShaderCompileResult defined = SpirvCompiler.compileGlslToSpirvDetailed(
                "defines_probe", TINY_FRAG, Shaderc.shaderc_glsl_fragment_shader, defines);
        Assertions.assertEquals(ShaderCompileResult.Status.SUCCESS, defined.status());
        long hitsAfterFirstDefined = ShaderCompileCache.hits();
        ShaderCompileResult definedAgain = SpirvCompiler.compileGlslToSpirvDetailed(
                "defines_probe", TINY_FRAG, Shaderc.shaderc_glsl_fragment_shader, defines);
        Assertions.assertEquals(ShaderCompileResult.Status.SUCCESS, definedAgain.status());
        Assertions.assertEquals(hitsAfterFirstDefined + 1, ShaderCompileCache.hits(),
                "identical defines must hit the same module entry");
    }

    @Test
    public void testDiskPersistenceRoundTripAndOptOut() throws Exception {
        Path tempDir = Files.createTempDirectory("vulkairis-compile-cache-test");
        boolean previousEnabled = ShaderCompileCache.isPersistenceEnabled();
        try {
            ShaderCompileCache.setPersistenceDirForTests(tempDir);
            ShaderCompileCache.setPersistenceEnabled(true);
            ShaderCompileCache.purgeDiskForTests();
            ShaderCompileCache.clearForTests();

            ShaderCompileResult first = SpirvCompiler.compileGlslToSpirvDetailed(
                    "disk_probe", TINY_FRAG, Shaderc.shaderc_glsl_fragment_shader);
            Assertions.assertEquals(ShaderCompileResult.Status.SUCCESS, first.status());
            Assertions.assertTrue(ShaderCompileCache.diskWrites() >= 1,
                    "a fresh compile must persist its SPIR-V words");

            ShaderCompileCache.evictMemoryForTests();
            long diskHitsBefore = ShaderCompileCache.diskHits();
            ShaderCompileResult coldMemory = SpirvCompiler.compileGlslToSpirvDetailed(
                    "disk_probe", TINY_FRAG, Shaderc.shaderc_glsl_fragment_shader);
            Assertions.assertEquals(ShaderCompileResult.Status.SUCCESS, coldMemory.status());
            Assertions.assertEquals(diskHitsBefore + 1, ShaderCompileCache.diskHits(),
                    "cold memory with warm disk must re-admit without recompiling");
            Assertions.assertEquals(first.spirv().remaining(), coldMemory.spirv().remaining());

            ShaderCompileCache.setPersistenceEnabled(false);
            ShaderCompileCache.evictMemoryForTests();
            long diskHitsOptOut = ShaderCompileCache.diskHits();
            long missesBefore = ShaderCompileCache.misses();
            ShaderCompileResult recompiled = SpirvCompiler.compileGlslToSpirvDetailed(
                    "disk_probe", TINY_FRAG, Shaderc.shaderc_glsl_fragment_shader);
            Assertions.assertEquals(ShaderCompileResult.Status.SUCCESS, recompiled.status());
            Assertions.assertEquals(diskHitsOptOut, ShaderCompileCache.diskHits(),
                    "opt-out must skip disk reads");
            Assertions.assertTrue(ShaderCompileCache.misses() > missesBefore,
                    "opt-out with cold memory must miss and recompile");
        } finally {
            ShaderCompileCache.setPersistenceEnabled(previousEnabled);
            ShaderCompileCache.setPersistenceDirForTests(null);
            ShaderCompileCache.purgeDiskForTests();
            ShaderCompileCache.clearForTests();
            try (var stream = Files.list(tempDir)) {
                for (Path file : stream.toList()) {
                    Files.deleteIfExists(file);
                }
            } catch (Throwable ignored) {
            }
            try {
                Files.deleteIfExists(tempDir);
            } catch (Throwable ignored) {
            }
        }
    }

    @Test
    public void testTranslationKeyCoversProgramAndDefines() {
        String hash = DiagnosticManager.hashSource(TINY_FRAG);
        String base = TranslationCache.key(hash, "gbuffers_terrain", "nodefines", false);
        Assertions.assertTrue(base.contains(TranslationCache.TRANSLATOR_VERSION));
        Assertions.assertNotEquals(base,
                TranslationCache.key(hash, "gbuffers_water", "nodefines", false),
                "program is part of the key");
        Assertions.assertNotEquals(base,
                TranslationCache.key(hash, "gbuffers_terrain", "defines-abc", false),
                "defines are part of the key");
        Assertions.assertNotEquals(base,
                TranslationCache.key(hash, "gbuffers_terrain", "nodefines", true),
                "stage is part of the key");
        Assertions.assertNotEquals(base,
                TranslationCache.key("other-hash", "gbuffers_terrain", "nodefines", false),
                "expanded source hash is part of the key");
        String legacy = TranslationCache.key(hash, false);
        Assertions.assertTrue(legacy.contains(TranslationCache.TRANSLATOR_VERSION),
                "the legacy key form must keep working");
    }

    @Test
    public void testWarmupBeforeDraw() {
        ShaderWarmup.resetForTests();
        ShaderCompileCache.clearForTests();
        long generation = SourceProvenance.currentGeneration();
        List<ShaderWarmup.WarmupEntry> entries = List.of(
                new ShaderWarmup.WarmupEntry("warm_vert", TINY_VERT,
                        Shaderc.shaderc_glsl_vertex_shader),
                new ShaderWarmup.WarmupEntry("warm_frag", TINY_FRAG,
                        Shaderc.shaderc_glsl_fragment_shader));
        CopyOnWriteArrayList<ShaderWarmup.Progress> seen = new CopyOnWriteArrayList<>();
        ShaderWarmup.Listener listener = seen::add;
        ShaderWarmup.addListener(listener);
        try {
            long started = System.currentTimeMillis();
            boolean accepted = ShaderWarmup.requestWarmup(entries, generation);
            long queueMs = System.currentTimeMillis() - started;
            Assertions.assertTrue(accepted, "a compilable set must be accepted");
            Assertions.assertTrue(queueMs < 10_000,
                    "requestWarmup must never block world load, took " + queueMs + "ms");

            Assertions.assertTrue(ShaderWarmup.awaitIdle(120_000),
                    "the worker must drain");
            ShaderWarmup.Progress progress = ShaderWarmup.progress();
            Assertions.assertEquals(2, progress.total());
            Assertions.assertEquals(2, progress.completed());
            Assertions.assertEquals(2, progress.succeeded(),
                    "valid stages must pre-compile cleanly");
            Assertions.assertEquals(0, progress.failed());
            Assertions.assertFalse(progress.running());
            Assertions.assertFalse(seen.isEmpty(), "progress must be visible to listeners");

            long hitsBefore = ShaderCompileCache.hits();
            String translatedVert = ShaderTranslator.preprocessGlsl(TINY_VERT, true);
            ShaderCompileResult drawVert = SpirvCompiler.compileGlslToSpirvDetailed(
                    "warm_vert", translatedVert, Shaderc.shaderc_glsl_vertex_shader);
            String translatedFrag = ShaderTranslator.preprocessGlsl(TINY_FRAG, false);
            ShaderCompileResult drawFrag = SpirvCompiler.compileGlslToSpirvDetailed(
                    "warm_frag", translatedFrag, Shaderc.shaderc_glsl_fragment_shader);
            Assertions.assertTrue(drawVert.succeeded() && drawFrag.succeeded());
            Assertions.assertEquals(hitsBefore + 2, ShaderCompileCache.hits(),
                    "first draw after warmup must be cache-hot");
        } finally {
            ShaderWarmup.removeListener(listener);
            ShaderWarmup.resetForTests();
        }
    }

    @Test
    public void testStageRejectionCoversAllUnsupported() {
        for (ShaderType type : new ShaderType[]{
                ShaderType.GEOMETRY, ShaderType.COMPUTE,
                ShaderType.TESSELATION_CONTROL, ShaderType.TESSELATION_EVAL}) {
            String reason = SpirvCompiler.rejectionReason(type);
            Assertions.assertNotNull(reason, "rejection must explain itself for " + type);
            Assertions.assertTrue(reason.contains("pipeline configuration")
                            || reason.contains("capability check")
                            || reason.contains("not implemented")
                            || reason.contains("not supported"),
                    "rejection must name the missing work for " + type + ": " + reason);
            ShaderCompileResult result = SpirvCompiler.compileGlslToSpirvDetailed(
                    "rejected_" + type.name(), TINY_FRAG,
                    SpirvCompiler.mapShaderTypeToShaderc(type));
            Assertions.assertEquals(ShaderCompileResult.Status.STAGE_REJECTED, result.status(),
                    type + " must stay rejected");
            Assertions.assertFalse(result.succeeded());
            Assertions.assertNull(result.spirv(), "rejection must never yield bytecode");
        }
        for (int kind : new int[]{
                Shaderc.shaderc_glsl_geometry_shader,
                Shaderc.shaderc_glsl_tess_control_shader,
                Shaderc.shaderc_glsl_tess_evaluation_shader,
                Shaderc.shaderc_glsl_compute_shader}) {
            Assertions.assertFalse(SpirvCompiler.isSupportedShadercKind(kind),
                    "shaderc kind " + kind + " must stay rejected (no new stages)");
        }
        Assertions.assertNull(SpirvCompiler.rejectionReason(ShaderType.VERTEX));
        Assertions.assertNull(SpirvCompiler.rejectionReason(ShaderType.FRAGMENT));
    }

    @Test
    public void testCachedViewsAreIndependentDuplicates() {
        ShaderCompileCache.clearForTests();
        ByteBuffer bytes = ByteBuffer.allocateDirect(8);
        bytes.putInt(0x07230203);
        bytes.putInt(0x00010300);
        bytes.flip();
        ShaderCompileCache.store("dup-key", bytes);
        ByteBuffer first = ShaderCompileCache.lookup("dup-key");
        ByteBuffer second = ShaderCompileCache.lookup("dup-key");
        Assertions.assertNotNull(first);
        Assertions.assertNotNull(second);
        Assertions.assertEquals(0, first.position(), "views start positioned at 0");
        Assertions.assertEquals(0, second.position(), "views start positioned at 0");
        first.position(4);
        Assertions.assertEquals(0, second.position(),
                "advancing one view's cursor must not disturb the other");
        Assertions.assertEquals(first.getInt(0), second.getInt(0),
                "both views must expose identical bytes");
    }
}
