package com.vulkairis.client.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.vulkairis.core.RenderMatrices;
import com.vulkairis.translation.SpirvCompiler;
import com.vulkairis.translation.VulkairisShader;
import com.vulkairis.backend.vulkanmod.VulkanBackendAdapter;
import com.vulkairis.vulkan.descriptor.VulkanDescriptorManager;
import com.vulkairis.vulkan.pipeline.PipelineManager;
import com.vulkairis.vulkan.pipeline.VulkanPipelineFactory;
import com.vulkairis.vulkan.test.StandaloneVulkanTest;
import net.irisshaders.iris.gl.shader.ShaderType;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Validates end-to-end rendering by creating native Vulkan VkPipeline objects
 * directly from Vulkairis VkShaderModule handles without raw GLSL re-feed.
 *
 * @deprecated Dead experimental path that is never dispatched from the render loop; superseded by
 * {@link StandaloneVulkanTest}. Retained only because {@code VulkairisLifecycle} still calls
 * {@link #cleanUp()}. Its draw path is now gated by
 * {@link StandaloneVulkanTest#isWorldPassActive(net.vulkanmod.vulkan.Renderer)} so that if it is
 * ever re-wired it cannot leak test geometry onto the menu. Use the controlled renderer instead.
 */
@Deprecated
public class BridgeTestRenderer {
    private static final Logger LOGGER = LogManager.getLogger("Vulkairis/TestRenderer");

    /**
     * OpenGL clip space Z: [-1, 1] -&gt; Vulkan clip space Z: [0, 1] correction matrix.
     *
     * <p>Alias of {@link RenderMatrices#CLIP_SPACE_CORRECTION}, the single authoritative
     * correction. Y is intentionally not flipped here because {@code SpirvCompiler} already emits
     * {@code set_invert_y(true)}.</p>
     */
    public static final Matrix4f VULKAN_DEPTH_CORRECTION = RenderMatrices.CLIP_SPACE_CORRECTION;

    private static final String TEST_VERT_GLSL = """
            #version 450 core
            layout (location = 0) in vec3 Position;
            layout (location = 1) in vec4 Color;
            layout (location = 0) out vec4 vertexColor;
            void main() {
                gl_Position = vec4(Position, 1.0);
                vertexColor = Color;
            }
            """;

    private static final String TEST_FRAG_GLSL = """
            #version 450 core
            layout (location = 0) in vec4 vertexColor;
            layout (location = 0) out vec4 fragColor;
            void main() {
                fragColor = vertexColor;
            }
            """;

    private static VulkairisShader vertShader = null;
    private static VulkairisShader fragShader = null;
    private static long testDescriptorSetLayout = 0L;
    private static long testDescriptorSet = 0L;
    private static long testPipelineLayout = 0L;
    private static final Map<Long, Long> PIPELINES_BY_RENDER_PASS = new ConcurrentHashMap<>();
    private static boolean testDrawn = false;
    private static boolean initialized = false;

    public static synchronized void initTestShaders() {
        if (initialized) return;

        VkDevice device = VulkanBackendAdapter.getDevice();
        if (!VulkanBackendAdapter.isLive(device)) {
            LOGGER.warn("[Vulkairis] Vulkan device not ready yet for test pipeline initialization.");
            return;
        }

        try {
            LOGGER.info("[Vulkairis] Initializing Vulkan Pipeline Cache and compiling test shaders...");

            // 1. Initialize Pipeline Cache
            PipelineManager.getOrCreatePipelineCache();

            // 2. Compile shaders with in-process Shaderc to SPIR-V and allocate native VkShaderModule handles
            vertShader = SpirvCompiler.compileShader("vulkairis_test_vert", TEST_VERT_GLSL, ShaderType.VERTEX);
            fragShader = SpirvCompiler.compileShader("vulkairis_test_frag", TEST_FRAG_GLSL, ShaderType.FRAGMENT);

            if (vertShader == null || fragShader == null ||
                    vertShader.getVkShaderModule() == 0L || fragShader.getVkShaderModule() == 0L) {
                LOGGER.error("[Vulkairis] Failed to compile test shaders into native VkShaderModule handles!");
                return;
            }

            LOGGER.info("[Vulkairis] Acquired native VkShaderModule handles: vert=0x{}, frag=0x{}",
                    Long.toHexString(vertShader.getVkShaderModule()),
                    Long.toHexString(fragShader.getVkShaderModule()));

            // 3. Create descriptor set layout and allocate descriptor set
            testDescriptorSetLayout = VulkanDescriptorManager.getOrCreateDescriptorSetLayout(List.of(
                    new VulkanDescriptorManager.BindingInfo(0, VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                            VK10.VK_SHADER_STAGE_FRAGMENT_BIT, 1)
            ));

            testDescriptorSet = VulkanDescriptorManager.allocateDescriptorSet(testDescriptorSetLayout);
            if (testDescriptorSet != 0L) {
                VulkanDescriptorManager.updateTextureBinding(testDescriptorSet, 0, 0); // Binds fallback white texture
            }

            // 4. Create pipeline layout
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                        .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO);
                if (testDescriptorSetLayout != 0L) {
                    layoutInfo.pSetLayouts(stack.longs(testDescriptorSetLayout));
                }

                LongBuffer pLayout = stack.mallocLong(1);
                int res = VK10.vkCreatePipelineLayout(device, layoutInfo, null, pLayout);
                if (res != VK10.VK_SUCCESS) {
                    throw new RuntimeException("Failed to create test VkPipelineLayout, error: " + res);
                }
                testPipelineLayout = pLayout.get(0);
                LOGGER.info("[Vulkairis] Created test VkPipelineLayout 0x{}", Long.toHexString(testPipelineLayout));
            }

            initialized = true;
        } catch (Exception e) {
            LOGGER.error("[Vulkairis] Failed to initialize test shaders: {}", e.getMessage(), e);
        }
    }

    private static long getOrCreatePipelineForPass(long renderPassId) {
        return PIPELINES_BY_RENDER_PASS.computeIfAbsent(renderPassId, passId -> {
            VkDevice device = VulkanBackendAdapter.getDevice();
            VulkanPipelineFactory.PipelineParams params = new VulkanPipelineFactory.PipelineParams(
                    vertShader.getVkShaderModule(),
                    fragShader.getVkShaderModule(),
                    testPipelineLayout,
                    passId,
                    DefaultVertexFormat.POSITION_COLOR,
                    false, // depthTest
                    false, // depthWrite
                    false, // cull
                    true,  // blend
                    PipelineManager.getOrCreatePipelineCache()
            );
            long pipeline = VulkanPipelineFactory.createGraphicsPipeline(device, params);
            LOGGER.info("[Vulkairis] Created test VkPipeline 0x{} for VkRenderPass 0x{}",
                    Long.toHexString(pipeline), Long.toHexString(passId));
            return pipeline;
        });
    }

    /**
     * Issues a test quad draw call directly using native Vulkan VkPipeline
     * compiled from Vulkairis VkShaderModule handles.
     *
     * @deprecated Dead path, superseded by {@link StandaloneVulkanTest}. Gated by the world-only
     * policy so it can never draw over the menu even if re-wired.
     */
    @Deprecated
    public static void renderTestQuad() {
        if (!initialized) {
            initTestShaders();
        }
        if (!initialized || testDrawn) return;

        try {
            Renderer renderer = Renderer.getInstance();
            VkCommandBuffer cmdBuffer = Renderer.getCommandBuffer();
            if (renderer == null || cmdBuffer == null) return;

            // Shared world-only gate: no menu/GUI frames, no non-main targets.
            if (!StandaloneVulkanTest.isWorldPassActive(renderer)) return;

            RenderPass boundPass = renderer.getBoundRenderPass();
            if (boundPass == null || boundPass.getId() == 0L) return;

            long pipeline = getOrCreatePipelineForPass(boundPass.getId());
            if (pipeline == 0L) return;

            // 1. Bind native VkPipeline directly
            VK10.vkCmdBindPipeline(cmdBuffer, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);

            // 2. Set dynamic viewport and scissor based on bound framebuffer
            Framebuffer fb = renderer.getBoundFramebuffer();
            if (fb != null) {
                int w = fb.getWidth();
                int h = fb.getHeight();
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    VkViewport.Buffer viewport = VkViewport.calloc(1, stack)
                            .x(0.0f).y(0.0f)
                            .width((float) w).height((float) h)
                            .minDepth(0.0f).maxDepth(1.0f);
                    VK10.vkCmdSetViewport(cmdBuffer, 0, viewport);

                    VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
                    scissor.offset().set(0, 0);
                    scissor.extent().set(w, h);
                    VK10.vkCmdSetScissor(cmdBuffer, 0, scissor);
                }
            }

            // 3. Bind descriptor set
            if (testDescriptorSet != 0L && testPipelineLayout != 0L) {
                VulkanDescriptorManager.bindDescriptorSets(cmdBuffer, testPipelineLayout, testDescriptorSet);
            }

            // 4. Create quad in normalized device coordinates (NDC)
            // Position: (x, y, z), Color: (r, g, b, a) in bytes
            ByteBuffer buffer = BufferUtils.createByteBuffer(4 * 16);
            // Vertex 1: Bottom-Left (Cyan)
            buffer.putFloat(-0.5f).putFloat(-0.5f).putFloat(0.0f);
            buffer.put((byte) 0).put((byte) 255).put((byte) 255).put((byte) 255);
            // Vertex 2: Top-Left (Magenta)
            buffer.putFloat(-0.5f).putFloat(0.5f).putFloat(0.0f);
            buffer.put((byte) 255).put((byte) 0).put((byte) 255).put((byte) 255);
            // Vertex 3: Top-Right (Yellow)
            buffer.putFloat(0.5f).putFloat(0.5f).putFloat(0.0f);
            buffer.put((byte) 255).put((byte) 255).put((byte) 0).put((byte) 255);
            // Vertex 4: Bottom-Right (Green)
            buffer.putFloat(0.5f).putFloat(-0.5f).putFloat(0.0f);
            buffer.put((byte) 0).put((byte) 255).put((byte) 0).put((byte) 255);
            buffer.flip();

            // 5. Draw quad
            Renderer.getDrawer().draw(buffer, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR, 4);

            testDrawn = true;
            LOGGER.info("[Vulkairis] [SUCCESS] Real Vulkan graphics pipeline created from Vulkairis VkShaderModule handles (vert: 0x{}, frag: 0x{}) rendered successfully without GLSL re-feed!",
                    Long.toHexString(vertShader.getVkShaderModule()),
                    Long.toHexString(fragShader.getVkShaderModule()));
        } catch (NullPointerException e) {
            // Expected on first call before render pass begins
        } catch (Exception e) {
            LOGGER.error("[Vulkairis] Error during native test quad draw call: {}", e.getMessage(), e);
        }
    }

    public static void resetTestDraw() {
        testDrawn = false;
    }

    public static synchronized void cleanUp() {
        VkDevice device = VulkanBackendAdapter.getDevice();
        for (long pipe : PIPELINES_BY_RENDER_PASS.values()) {
            VulkanPipelineFactory.destroyPipeline(device, pipe);
        }
        PIPELINES_BY_RENDER_PASS.clear();

        if (testPipelineLayout != 0L && VulkanBackendAdapter.isLive(device)) {
            try {
                VK10.vkDestroyPipelineLayout(device, testPipelineLayout, null);
            } catch (Throwable ignored) {
            }
            testPipelineLayout = 0L;
        }

        if (vertShader != null) {
            vertShader.destroy(device);
            vertShader = null;
        }
        if (fragShader != null) {
            fragShader.destroy(device);
            fragShader = null;
        }

        initialized = false;
        testDrawn = false;
    }
}
