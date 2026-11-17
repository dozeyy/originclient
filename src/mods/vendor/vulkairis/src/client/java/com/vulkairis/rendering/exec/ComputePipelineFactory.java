package com.vulkairis.rendering.exec;

import com.vulkairis.backend.vulkanmod.VulkanBackendAdapter;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 9: creates real {@code VkComputePipeline} objects from compute shader modules.
 *
 * <p>The bridge advertised {@code supportsCompute = true} while dispatch was a no-op. This factory
 * plus {@link ComputeDispatcher} make the compute path real: a compute shader compiled to SPIR-V,
 * a compute pipeline, and an actual {@code vkCmdDispatch}. Pipelines are cached by
 * (shader module, pipeline layout) so repeated frames do not recreate them.</p>
 */
public final class ComputePipelineFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkairis/ComputePipeline");

    private record Key(long shaderModule, long pipelineLayout) {
    }

    private static final Map<Key, Long> PIPELINE_CACHE = new ConcurrentHashMap<>();

    private ComputePipelineFactory() {
    }

    /**
     * Creates (or returns a cached) compute pipeline.
     *
     * @param device         the live logical device
     * @param shaderModule   the compute {@code VkShaderModule}
     * @param pipelineLayout the pipeline layout (descriptor sets + push constants)
     * @return the pipeline handle, or {@code 0} when the device/module is unavailable
     */
    public static long getOrCreateComputePipeline(VkDevice device, long shaderModule, long pipelineLayout) {
        if (!VulkanBackendAdapter.isLive(device) || shaderModule == 0L || pipelineLayout == 0L) {
            return 0L;
        }
        return PIPELINE_CACHE.computeIfAbsent(
                new Key(shaderModule, pipelineLayout),
                key -> create(device, key.shaderModule(), key.pipelineLayout()));
    }

    private static long create(VkDevice device, long shaderModule, long pipelineLayout) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer mainEntry = stack.UTF8("main");

            VkComputePipelineCreateInfo.Buffer info = VkComputePipelineCreateInfo.calloc(1, stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO)
                    .layout(pipelineLayout);

            info.stage()
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT)
                    .module(shaderModule)
                    .pName(mainEntry);

            LongBuffer pPipeline = stack.mallocLong(1);
            int result = VK10.vkCreateComputePipelines(device, 0L, info, null, pPipeline);
            if (result != VK10.VK_SUCCESS) {
                LOGGER.error("[Vulkairis/Compute] vkCreateComputePipelines failed: {}", result);
                return 0L;
            }
            long pipeline = pPipeline.get(0);
            LOGGER.info("[Vulkairis/Compute] Created VkComputePipeline 0x{} (module 0x{})",
                    Long.toHexString(pipeline), Long.toHexString(shaderModule));
            return pipeline;
        } catch (Throwable t) {
            LOGGER.error("[Vulkairis/Compute] Error creating compute pipeline: {}", t.getMessage());
            return 0L;
        }
    }

    /** Destroys all cached compute pipelines (shader reload / shutdown). */
    public static synchronized void cleanUp() {
        VkDevice device = VulkanBackendAdapter.getDevice();
        if (VulkanBackendAdapter.isLive(device)) {
            for (long pipeline : PIPELINE_CACHE.values()) {
                if (pipeline != 0L) {
                    try {
                        VK10.vkDestroyPipeline(device, pipeline, null);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        PIPELINE_CACHE.clear();
    }

    /** @return the number of cached compute pipelines (test/diagnostic helper) */
    public static int cachedCount() {
        return PIPELINE_CACHE.size();
    }
}
