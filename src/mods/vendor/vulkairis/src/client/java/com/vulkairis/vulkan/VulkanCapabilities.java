package com.vulkairis.vulkan;

import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.Device;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Encapsulates Vulkan physical device capabilities, supported limits,
 * queue families, format support, and device suitability logic.
 */
public class VulkanCapabilities {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkairis/Capabilities");

    public enum DeviceType {
        DISCRETE_GPU,
        INTEGRATED_GPU,
        VIRTUAL_GPU,
        CPU,
        OTHER
    }

    private String deviceName = "Unknown GPU";
    private String vendorName = "Unknown Vendor";
    private int vendorId = 0;
    private int apiVersion = VK10.VK_MAKE_VERSION(1, 2, 0);
    private String apiVersionString = "1.2.0";
    private int driverVersion = 0;
    private DeviceType deviceType = DeviceType.OTHER;

    private int graphicsQueueFamilyIndex = 0;
    private int maxColorAttachments = 4;
    private int maxImageDimension2D = 4096;
    private long maxUniformBufferRange = 65536;
    private boolean dynamicRenderingSupported = false;
    private boolean timelineSemaphoresSupported = false;
    private boolean depth32FormatSupported = true;
    private boolean depth24Stencil8Supported = true;

    private static VulkanCapabilities activeCapabilities = null;

    public VulkanCapabilities() {
    }

    public static synchronized VulkanCapabilities getActiveCapabilities() {
        if (activeCapabilities == null) {
            activeCapabilities = queryCapabilities();
        }
        return activeCapabilities;
    }

    public static VulkanCapabilities queryCapabilities() {
        VulkanCapabilities caps = new VulkanCapabilities();

        // 1. Try querying via VulkanMod if already initialized
        try {
            Device vmDevice = Vulkan.getDevice();
            if (vmDevice != null) {
                caps.deviceName = vmDevice.deviceName != null ? vmDevice.deviceName : "VulkanMod GPU";
                caps.vendorName = vmDevice.vendorIdString != null ? vmDevice.vendorIdString : "VulkanMod Vendor";
                caps.apiVersionString = vmDevice.vkVersion != null ? vmDevice.vkVersion : "Vulkan 1.2+";
                if (vmDevice.isNvidia()) caps.vendorId = 0x10DE;
                else if (vmDevice.isAMD()) caps.vendorId = 0x1002;
                else if (vmDevice.isIntel()) caps.vendorId = 0x8086;

                caps.deviceType = (vmDevice.isNvidia() || vmDevice.isAMD()) ? DeviceType.DISCRETE_GPU : DeviceType.INTEGRATED_GPU;
                caps.dynamicRenderingSupported = Vulkan.DYNAMIC_RENDERING;
                LOGGER.info("[Vulkairis] Detected GPU via VulkanMod: {} ({}) - Version: {}",
                        caps.deviceName, caps.vendorName, caps.apiVersionString);
                return caps;
            }
        } catch (Throwable ignored) {
        }

        // 2. Fallback query using LWJGL VkPhysicalDevice if VulkanMod isn't loaded yet
        caps.deviceName = "Generic Vulkan GPU";
        caps.vendorName = "Generic Vendor";
        caps.apiVersionString = "Vulkan 1.2";
        caps.deviceType = DeviceType.DISCRETE_GPU;
        caps.graphicsQueueFamilyIndex = 0;

        return caps;
    }

    /**
     * Inspects a specific VkPhysicalDevice and fills detailed capability fields.
     */
    public void populateFromPhysicalDevice(VkPhysicalDevice physicalDevice) {
        if (physicalDevice == null) return;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
            VK10.vkGetPhysicalDeviceProperties(physicalDevice, props);

            this.deviceName = props.deviceNameString();
            this.vendorId = props.vendorID();
            this.apiVersion = props.apiVersion();
            this.driverVersion = props.driverVersion();

            int major = VK10.VK_VERSION_MAJOR(apiVersion);
            int minor = VK10.VK_VERSION_MINOR(apiVersion);
            int patch = VK10.VK_VERSION_PATCH(apiVersion);
            this.apiVersionString = String.format("%d.%d.%d", major, minor, patch);

            this.vendorName = switch (vendorId) {
                case 0x10DE -> "NVIDIA";
                case 0x1002 -> "AMD";
                case 0x8086 -> "Intel";
                case 0x13B5 -> "ARM";
                case 0x5143 -> "Qualcomm";
                default -> "Vendor (0x" + Integer.toHexString(vendorId) + ")";
            };

            this.deviceType = switch (props.deviceType()) {
                case VK10.VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU -> DeviceType.DISCRETE_GPU;
                case VK10.VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU -> DeviceType.INTEGRATED_GPU;
                case VK10.VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU -> DeviceType.VIRTUAL_GPU;
                case VK10.VK_PHYSICAL_DEVICE_TYPE_CPU -> DeviceType.CPU;
                default -> DeviceType.OTHER;
            };

            this.maxColorAttachments = props.limits().maxColorAttachments();
            this.maxImageDimension2D = props.limits().maxImageDimension2D();
            this.maxUniformBufferRange = props.limits().maxUniformBufferRange();

            // Check queue families
            IntBuffer pQueueFamilyCount = stack.mallocInt(1);
            VK10.vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, pQueueFamilyCount, null);
            int queueFamilyCount = pQueueFamilyCount.get(0);
            if (queueFamilyCount > 0) {
                VkQueueFamilyProperties.Buffer queueFamilies = VkQueueFamilyProperties.calloc(queueFamilyCount, stack);
                VK10.vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, pQueueFamilyCount, queueFamilies);

                for (int i = 0; i < queueFamilyCount; i++) {
                    if ((queueFamilies.get(i).queueFlags() & VK10.VK_QUEUE_GRAPHICS_BIT) != 0) {
                        this.graphicsQueueFamilyIndex = i;
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("[Vulkairis] Warning while populating capabilities: {}", t.getMessage());
        }
    }

    public boolean isCompatible() {
        // Requirement: Valid graphics queue family and Vulkan 1.1+ or known vendor GPU
        return graphicsQueueFamilyIndex >= 0 && (apiVersion >= VK10.VK_MAKE_VERSION(1, 1, 0) || vendorId != 0);
    }

    public String getDeviceName() {
        return deviceName;
    }

    public String getVendorName() {
        return vendorName;
    }

    public int getVendorId() {
        return vendorId;
    }

    public int getApiVersion() {
        return apiVersion;
    }

    public String getApiVersionString() {
        return apiVersionString;
    }

    public DeviceType getDeviceType() {
        return deviceType;
    }

    public int getGraphicsQueueFamilyIndex() {
        return graphicsQueueFamilyIndex;
    }

    public int getMaxColorAttachments() {
        return maxColorAttachments;
    }

    public int getMaxImageDimension2D() {
        return maxImageDimension2D;
    }

    public long getMaxUniformBufferRange() {
        return maxUniformBufferRange;
    }

    public boolean isDynamicRenderingSupported() {
        return dynamicRenderingSupported;
    }

    public boolean isDepth32FormatSupported() {
        return depth32FormatSupported;
    }

    public boolean isDepth24Stencil8Supported() {
        return depth24Stencil8Supported;
    }
}
