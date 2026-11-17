package com.vulkairis.test;

import com.vulkairis.core.VulkairisLifecycle;
import com.vulkairis.resource.VulkairisResourceManager;
import com.vulkairis.translation.GlObjectState;
import com.vulkairis.translation.SpirvCompiler;
import com.vulkairis.translation.VirtualGlFacade;
import com.vulkairis.translation.VulkanProgramLinker;
import com.vulkairis.translation.VulkanShaderRegistry;
import com.vulkairis.translation.VulkairisProgram;
import com.vulkairis.translation.VulkairisShader;
import com.vulkairis.backend.vulkanmod.VulkanBackendAdapter;
import com.vulkairis.vulkan.descriptor.VulkanDescriptorManager;
import com.vulkairis.vulkan.pipeline.PipelineManager;
import com.vulkairis.vulkan.test.StandaloneVulkanTest;
import net.irisshaders.iris.gl.shader.ShaderType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Phase 7: device generations invalidate stale handles, cache blobs validate before use,
 * cleanup is idempotent, and reload cycles stay bounded without id reuse.
 */
public class DeviceOwnershipTest {

    private static final long ADDR_A = 0xA11CE11L;
    private static final long ADDR_B = 0xBEEF0000L;

    private static final String VERT = """
            #version 450 core
            layout(location = 0) in vec3 Position;
            layout(location = 0) out vec4 vColor;
            void main() {
                gl_Position = vec4(Position, 1.0);
                vColor = vec4(1.0);
            }
            """;

    private static final String FRAG = """
            #version 450 core
            layout(location = 0) in vec4 vColor;
            layout(location = 0) out vec4 fragColor;
            void main() {
                fragColor = vColor;
            }
            """;

    @Test
    public void testDeviceGenerationAnchorsAndBumps() {
        VulkanBackendAdapter.observeDevice(ADDR_A);
        long anchored = VulkanBackendAdapter.currentGeneration();
        VulkanBackendAdapter.observeDevice(ADDR_A);
        Assertions.assertEquals(anchored, VulkanBackendAdapter.currentGeneration(),
                "re-observing the same device must not advance the generation");
        VulkanBackendAdapter.observeDevice(0L);
        Assertions.assertEquals(anchored, VulkanBackendAdapter.currentGeneration(),
                "dead observations must be ignored");
        VulkanBackendAdapter.observeDevice(ADDR_B);
        long bumped = VulkanBackendAdapter.currentGeneration();
        Assertions.assertEquals(anchored + 1, bumped,
                "a changed live device must advance exactly one generation");
        Assertions.assertEquals(bumped, VulkanBackendAdapter.currentGeneration());
        Assertions.assertTrue(VulkanBackendAdapter.isStaleGeneration(anchored));
        Assertions.assertFalse(VulkanBackendAdapter.isStaleGeneration(bumped));
    }

    @Test
    public void testRecreatedDeviceInvalidatesHandles() {
        VulkairisShader vert = SpirvCompiler.compileShader("own_vert", VERT, ShaderType.VERTEX);
        VulkairisShader frag = SpirvCompiler.compileShader("own_frag", FRAG, ShaderType.FRAGMENT);
        Assertions.assertFalse(vert.isStale());
        Assertions.assertEquals(GlObjectState.COMPILED, VirtualGlFacade.shaderState(vert.virtualId()));

        int progId = VulkanShaderRegistry.registerProgram(
                "own_prog", List.of(vert.virtualId(), frag.virtualId()));
        VulkairisProgram program = VulkanShaderRegistry.getProgram(progId);
        Assertions.assertTrue(VulkanProgramLinker.validate(program).valid());
        VulkanShaderRegistry.setProgramLinkState(progId, GlObjectState.LINKED);
        Assertions.assertEquals(GlObjectState.LINKED, VirtualGlFacade.programState(progId));

        VulkanBackendAdapter.destroy();

        Assertions.assertTrue(vert.isStale(), "shader handles die with their device");
        Assertions.assertTrue(program.isStale());
        Assertions.assertEquals(GlObjectState.INVALIDATED,
                VirtualGlFacade.shaderState(vert.virtualId()));
        Assertions.assertEquals(GlObjectState.INVALIDATED,
                VirtualGlFacade.programState(progId));
        Assertions.assertEquals(0, VirtualGlFacade.getShaderi(vert.virtualId(), 35713),
                "stale shaders must not report compiled");
        Assertions.assertEquals(0, VirtualGlFacade.getProgrami(progId, 35714),
                "stale programs must not report linked");
    }

    @Test
    public void testCacheBlobValidation() {
        Assertions.assertFalse(PipelineManager.isUsableCacheBlob(null));
        Assertions.assertFalse(PipelineManager.isUsableCacheBlob(new byte[16]),
                "truncated blobs are unusable");
        Assertions.assertFalse(PipelineManager.isUsableCacheBlob(blob(0)),
                "version word zero is rejected");
        Assertions.assertFalse(PipelineManager.isUsableCacheBlob(blob(2)),
                "future versions are rejected");
        ByteBuffer torn = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
        torn.putInt(64).putInt(1);
        while (torn.hasRemaining()) {
            torn.put((byte) 0);
        }
        Assertions.assertFalse(PipelineManager.isUsableCacheBlob(torn.array()),
                "length mismatch means a torn write");
        Assertions.assertTrue(PipelineManager.isUsableCacheBlob(blob(1)),
                "a well-formed blob is accepted");
    }

    @Test
    public void testAtomicWriteRoundTrip() throws Exception {
        Path temp = Files.createTempDirectory("vulkairis-cache-test");
        try {
            Path target = temp.resolve("pipeline_cache.bin");
            byte[] bytes = blob(1);
            Assertions.assertTrue(PipelineManager.writeAtomically(target, bytes));
            Assertions.assertArrayEquals(bytes, Files.readAllBytes(target));
            try (var stream = Files.list(temp)) {
                Assertions.assertEquals(1, stream.count(),
                        "no temp files may be left behind");
            }
        } finally {
            try (var stream = Files.walk(temp)) {
                stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                    }
                });
            }
        }
    }

    @Test
    public void testReloadCyclesStayBoundedWithoutIdReuse() {
        int baseline = VulkanShaderRegistry.registeredShaderCount();
        Set<Integer> first = new HashSet<>();
        for (int i = 0; i < 3; i++) {
            first.add(SpirvCompiler.compileShader("own_cycle_a" + i, VERT, ShaderType.VERTEX)
                    .virtualId());
        }
        Assertions.assertEquals(baseline + 3, VulkanShaderRegistry.registeredShaderCount());
        for (int id : first) {
            VulkanShaderRegistry.deleteShader(id);
        }
        Assertions.assertEquals(baseline, VulkanShaderRegistry.registeredShaderCount(),
                "deletion must drop live objects, not leak them");

        Set<Integer> second = new HashSet<>();
        for (int i = 0; i < 3; i++) {
            second.add(SpirvCompiler.compileShader("own_cycle_b" + i, VERT, ShaderType.VERTEX)
                    .virtualId());
        }
        Assertions.assertEquals(baseline + 3, VulkanShaderRegistry.registeredShaderCount(),
                "recreation must not grow live objects unboundedly");
        for (int id : second) {
            Assertions.assertFalse(first.contains(id), "ids are never recycled into live handles");
            VulkanShaderRegistry.deleteShader(id);
        }
        Assertions.assertEquals(baseline, VulkanShaderRegistry.registeredShaderCount());
    }

    @Test
    public void testCleanupIsIdempotentHeadless() {
        Assertions.assertDoesNotThrow(() -> {
            StandaloneVulkanTest.invalidatePipelines();
            StandaloneVulkanTest.invalidatePipelines();
            VulkanShaderRegistry.cleanUp();
            VulkanShaderRegistry.cleanUp();
            PipelineManager.cleanUp();
            PipelineManager.cleanUp();
            VulkanDescriptorManager.cleanUp();
            VulkanDescriptorManager.cleanUp();
            VulkairisResourceManager.getInstance().clear();
            VulkairisResourceManager.getInstance().clear();
            VulkanBackendAdapter.destroy();
            VulkanBackendAdapter.destroy();
        }, "every cleanup path must tolerate repetition without a device");
        VulkairisLifecycle.onShutdown();
    }

    private static byte[] blob(int version) {
        ByteBuffer buffer = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(32);
        buffer.putInt(version);
        while (buffer.hasRemaining()) {
            buffer.put((byte) 0);
        }
        return buffer.array();
    }
}
