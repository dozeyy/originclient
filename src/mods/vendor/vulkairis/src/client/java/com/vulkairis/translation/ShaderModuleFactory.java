package com.vulkairis.translation;

import com.vulkairis.backend.vulkanmod.VulkanBackendAdapter;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

/**
 * Dedicated factory for allocating and destroying native Vulkan VkShaderModule handles.
 */
public class ShaderModuleFactory {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkairis/ShaderModuleFactory");

    /**
     * Creates a native VkShaderModule on the specified logical device.
     */
    public static long createShaderModule(VkDevice device, ByteBuffer spirv) {
        if (spirv == null || !spirv.hasRemaining()) {
            throw new IllegalArgumentException("Cannot create VkShaderModule: SPIR-V buffer is null or empty");
        }

        if (!VulkanBackendAdapter.isLive(device)) {
            LOGGER.warn("[Vulkairis] Cannot create VkShaderModule: VkDevice is not live");
            return 0L;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkShaderModuleCreateInfo createInfo = VkShaderModuleCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                    .pCode(spirv);

            LongBuffer pModule = stack.mallocLong(1);
            int result = VK10.vkCreateShaderModule(device, createInfo, null, pModule);
            if (result != VK10.VK_SUCCESS) {
                throw new RuntimeException("vkCreateShaderModule failed with Vulkan error code: " + result);
            }

            long handle = pModule.get(0);
            // Lifecycle: this factory does not receive the owning VulkairisShader, so it cannot
            // advance MODULE_CREATED itself. The registry's VulkairisShader constructor derives
            // MODULE_CREATED from this non-zero handle.
            LOGGER.info("[Vulkairis] Created VkShaderModule handle 0x{}", Long.toHexString(handle));
            return handle;
        }
    }

    /**
     * Convenience method using the active logical device.
     */
    public static long createShaderModule(ByteBuffer spirv) {
        return createShaderModule(VulkanBackendAdapter.getDevice(), spirv);
    }

    /**
     * Safely destroys a VkShaderModule handle.
     */
    public static void destroyShaderModule(VkDevice device, long shaderModule) {
        if (shaderModule != 0L && VulkanBackendAdapter.isLive(device)) {
            try {
                VK10.vkDestroyShaderModule(device, shaderModule, null);
                LOGGER.info("[Vulkairis] Destroyed VkShaderModule 0x{}", Long.toHexString(shaderModule));
            } catch (Throwable t) {
                LOGGER.warn("[Vulkairis] Error destroying VkShaderModule 0x{}: {}",
                        Long.toHexString(shaderModule), t.getMessage());
            }
        }
    }
}
