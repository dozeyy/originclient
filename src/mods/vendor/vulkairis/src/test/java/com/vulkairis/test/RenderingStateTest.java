package com.vulkairis.test;

import com.vulkairis.rendering.state.ProgramUniformStore;
import com.vulkairis.rendering.state.ResourceBindings;
import com.vulkairis.rendering.state.SynchronizationState;
import com.vulkairis.rendering.state.VulkairisRenderState;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.FloatBuffer;

import static org.lwjgl.vulkan.VK10.VK_ACCESS_SHADER_READ_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_SHADER_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;

/**
 * Device-free verification of the Phase 2 runtime state layer that replaces the previously silent
 * no-op Iris operations: uniform uploads, resource bindings, barrier translation, compute dispatch
 * validation, and shadow projection handling.
 */
public class RenderingStateTest {

    @Test
    public void testUniformUploadsAreStored() {
        ProgramUniformStore store = new ProgramUniformStore();
        store.setFloat(1, 0.5f);
        store.setVec4(2, 1, 2, 3, 4);
        store.setIVec2(3, 7, 8);

        Assertions.assertEquals(0.5f, store.get(1).orElseThrow().floats()[0], 1e-6);
        Assertions.assertArrayEquals(new float[] {1, 2, 3, 4}, store.get(2).orElseThrow().floats());
        Assertions.assertArrayEquals(new int[] {7, 8}, store.get(3).orElseThrow().ints());
        Assertions.assertEquals(3, store.size());
        Assertions.assertTrue(store.hasUniform(2));
        Assertions.assertFalse(store.hasUniform(99));
    }

    @Test
    public void testMatrixUploadReadsAbsoluteIndex() {
        ProgramUniformStore store = new ProgramUniformStore();
        FloatBuffer buffer = FloatBuffer.allocate(20);
        // Fill positions 2..17 with 0..15, leaving a non-zero position to prove absolute reads.
        for (int i = 0; i < 16; i++) {
            buffer.put(2 + i, i);
        }
        buffer.position(2);

        store.setMatrix4(10, buffer);
        float[] stored = store.get(10).orElseThrow().floats();
        Assertions.assertEquals("mat4", store.get(10).orElseThrow().kind());
        for (int i = 0; i < 16; i++) {
            Assertions.assertEquals(i, stored[i], 1e-6);
        }
    }

    @Test
    public void testTextureAndImageBindings() {
        ResourceBindings bindings = new ResourceBindings();
        bindings.bindTextureToUnit(3, 55, 3553);
        bindings.bindImage(2, 66, 0, false, 0, 0x2600, 0x822B);

        Assertions.assertEquals(55, bindings.getTextureUnit(3).orElseThrow().textureId());
        Assertions.assertEquals(3553, bindings.getTextureUnit(3).orElseThrow().target());
        Assertions.assertEquals(66, bindings.getImageUnit(2).orElseThrow().textureId());
        Assertions.assertFalse(bindings.getImageUnit(2).orElseThrow().layered());

        bindings.unbindTextureFromUnit(3);
        Assertions.assertTrue(bindings.getTextureUnit(3).isEmpty());
    }

    @Test
    public void testSamplerParametersAccumulate() {
        ResourceBindings bindings = new ResourceBindings();
        bindings.setSamplerParameters(9, 9729, 0, 0, 0);
        bindings.setSamplerParameters(9, 0, 9728, 33071, 0);

        ResourceBindings.SamplerObject sampler = bindings.getSampler(9).orElseThrow();
        Assertions.assertEquals(9729, sampler.minFilter());
        Assertions.assertEquals(9728, sampler.magFilter());
        Assertions.assertEquals(33071, sampler.wrapS());

        bindings.bindSamplerToUnit(4, 9);
        Assertions.assertEquals(9, bindings.getSamplerForUnit(4).orElseThrow());
    }

    @Test
    public void testFramebufferAttachmentAndCompleteness() {
        ResourceBindings bindings = new ResourceBindings();
        Assertions.assertFalse(bindings.isFramebufferComplete(1),
                "a framebuffer with no attachments is not complete");

        bindings.attachFramebufferTexture(1, 36064, 12, 0);
        bindings.attachFramebufferTexture(1, 36065, 13, 0);
        Assertions.assertTrue(bindings.isFramebufferComplete(1));
        Assertions.assertEquals(2, bindings.getAttachments(1).size());
        Assertions.assertEquals(13, bindings.getAttachment(1, 36065).orElseThrow().textureId());
    }

    @Test
    public void testDrawAndReadBuffers() {
        ResourceBindings bindings = new ResourceBindings();
        bindings.setDrawBuffers(1, new int[] {36064, 36065});
        bindings.setReadBuffer(1, 36064);

        Assertions.assertArrayEquals(new int[] {36064, 36065}, bindings.getDrawBuffers(1));
        Assertions.assertEquals(36064, bindings.getReadBuffer(1).orElseThrow());
        // Returned arrays must be copies, not the internal storage.
        int[] first = bindings.getDrawBuffers(1);
        first[0] = -1;
        Assertions.assertEquals(36064, bindings.getDrawBuffers(1)[0]);
    }

    @Test
    public void testBufferBaseAndUniformBlockBinding() {
        ResourceBindings bindings = new ResourceBindings();
        bindings.bindBufferBase(0, 42);
        bindings.setUniformBlockBinding(7, 0, 3);

        Assertions.assertEquals(42, bindings.getBufferBase(0).orElseThrow());
        Assertions.assertEquals(3, bindings.getUniformBlockBinding(7, 0).orElseThrow());
    }

    @Test
    public void testShaderStorageBarrierTranslation() {
        SynchronizationState.Barrier barrier =
                SynchronizationState.translate(SynchronizationState.GL_SHADER_STORAGE_BARRIER_BIT);

        Assertions.assertNotEquals(0, barrier.srcStageMask() & VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
        Assertions.assertNotEquals(0, barrier.srcAccessMask() & VK_ACCESS_SHADER_WRITE_BIT);
        Assertions.assertNotEquals(0, barrier.dstAccessMask() & VK_ACCESS_SHADER_READ_BIT);
        Assertions.assertFalse(barrier.isAllCommands());
    }

    @Test
    public void testAllBarrierMapsToAllCommands() {
        SynchronizationState.Barrier barrier =
                SynchronizationState.translate(SynchronizationState.GL_ALL_BARRIER_BITS);
        Assertions.assertTrue(barrier.isAllCommands());
        Assertions.assertEquals(VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                barrier.srcStageMask() & VK_PIPELINE_STAGE_ALL_COMMANDS_BIT);
    }

    /** An unrecognized mask must degrade to a conservative barrier, never be silently discarded. */
    @Test
    public void testUnknownBarrierIsConservative() {
        SynchronizationState.Barrier barrier = SynchronizationState.translate(0);
        Assertions.assertEquals(VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, barrier.srcStageMask());
        Assertions.assertNotEquals(0, barrier.dstStageMask());
    }

    @Test
    public void testDispatchValidationAndQueue() {
        SynchronizationState sync = new SynchronizationState();
        sync.setMaxWorkGroupCounts(64, 64, 64);

        Assertions.assertTrue(sync.dispatchCompute(64, 1, 1));
        Assertions.assertFalse(sync.dispatchCompute(65, 1, 1), "over-limit dispatch must be rejected");
        Assertions.assertEquals(1, sync.getPendingDispatchCount());
        Assertions.assertTrue(sync.dispatchComputeIndirect(128));
        Assertions.assertEquals(2, sync.getPendingDispatchCount());

        Assertions.assertEquals(1, sync.drainDispatches().size());
        Assertions.assertEquals(0, sync.getPendingDispatchCount());
    }

    @Test
    public void testMemoryBarrierIsQueuedThenDrained() {
        SynchronizationState sync = new SynchronizationState();
        sync.requestMemoryBarrier(SynchronizationState.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
        Assertions.assertEquals(1, sync.getPendingBarriers().size());
        Assertions.assertEquals(1, sync.drainBarriers().size());
        Assertions.assertTrue(sync.getPendingBarriers().isEmpty());
    }

    @Test
    public void testShadowProjectionActivationAndRestore() {
        VulkairisRenderState state = VulkairisRenderState.get();
        state.setPlayerProjection(new Matrix4f().perspective(1.0f, 1.0f, 0.1f, 100.0f));
        Matrix4f playerBefore = new Matrix4f(state.getPlayerProjection());

        Matrix4f shadow = new Matrix4f().ortho(-10, 10, -10, 10, 0.1f, 50.0f);
        state.setShadowProjection(shadow);
        Assertions.assertTrue(state.isShadowProjectionActive());
        Assertions.assertEquals(shadow, state.getActiveProjection());

        state.restorePlayerProjection();
        Assertions.assertFalse(state.isShadowProjectionActive());
        Assertions.assertEquals(playerBefore, state.getActiveProjection());
    }
}
