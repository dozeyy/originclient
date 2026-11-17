package com.vulkairis.vulkan;

import net.vulkanmod.vulkan.Vulkan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;

/**
 * Manages the logical VkDevice, graphics queue, command pools, and device-level synchronization.
 */
public class VulkanDevice {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkairis/Device");

    private static VkDevice logicalDevice = null;
    private static VkQueue graphicsQueue = null;
    private static long commandPool = 0L;
    private static boolean owned = false;

    /**
     * Obtains the primary active VkDevice (from VulkanMod or standalone).
     */
    public static synchronized VkDevice getDevice() {
        if (isLive(logicalDevice)) return logicalDevice;

        try {
            VkDevice vmDevice = Vulkan.getVkDevice();
            if (isLive(vmDevice)) {
                logicalDevice = vmDevice;
                commandPool = Vulkan.getCommandPool();
                return logicalDevice;
            }
        } catch (Throwable ignored) {
        }

        return logicalDevice;
    }

    public static boolean isLive(VkDevice device) {
        try {
            return device != null && device.address() != 0L;
        } catch (Throwable t) {
            return false;
        }
    }

    public static synchronized long getOrCreateCommandPool() {
        if (commandPool != 0L) return commandPool;

        VkDevice device = getDevice();
        if (!isLive(device)) return 0L;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            int queueFamily = VulkanCapabilities.getActiveCapabilities().getGraphicsQueueFamilyIndex();
            VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                    .flags(VK10.VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                    .queueFamilyIndex(queueFamily);

            LongBuffer pCmdPool = stack.mallocLong(1);
            int res = VK10.vkCreateCommandPool(device, poolInfo, null, pCmdPool);
            if (res == VK10.VK_SUCCESS) {
                commandPool = pCmdPool.get(0);
                owned = true;
                LOGGER.info("[Vulkairis] Created command pool 0x{}", Long.toHexString(commandPool));
            } else {
                LOGGER.error("[Vulkairis] Failed to create command pool, code: {}", res);
            }
        } catch (Throwable t) {
            LOGGER.error("[Vulkairis] Error creating command pool: {}", t.getMessage());
        }

        return commandPool;
    }

    public static void waitIdle() {
        VkDevice device = getDevice();
        if (isLive(device)) {
            try {
                VK10.vkDeviceWaitIdle(device);
            } catch (Throwable t) {
                LOGGER.warn("[Vulkairis] vkDeviceWaitIdle notice: {}", t.getMessage());
            }
        }
    }

    public static synchronized void destroy() {
        VkDevice device = getDevice();
        if (owned && isLive(device)) {
            if (commandPool != 0L) {
                VK10.vkDestroyCommandPool(device, commandPool, null);
                commandPool = 0L;
            }
        }
        logicalDevice = null;
        graphicsQueue = null;
        owned = false;
        LOGGER.info("[Vulkairis] VulkanDevice cleaned up.");
    }
}
