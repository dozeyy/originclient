package com.vulkairis.framebuffer;

import com.vulkairis.backend.vulkanmod.VulkanBackendAdapter;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages VkSampler creation and caching for texture filtering.
 */
public class VulkanSampler {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkairis/Sampler");

    public record SamplerKey(int minFilter, int magFilter, int addressMode, boolean compareEnable) {
        /** Legacy shape: comparison disabled. */
        public SamplerKey(int minFilter, int magFilter, int addressMode) {
            this(minFilter, magFilter, addressMode, false);
        }
    }

    private static final Map<SamplerKey, Long> SAMPLER_CACHE = new ConcurrentHashMap<>();

    public static long getOrCreateSampler(boolean linear, boolean repeat) {
        int filter = linear ? VK10.VK_FILTER_LINEAR : VK10.VK_FILTER_NEAREST;
        int address = repeat ? VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT : VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;

        SamplerKey key = new SamplerKey(filter, filter, address);
        return SAMPLER_CACHE.computeIfAbsent(key, VulkanSampler::createSampler);
    }

    /**
     * Phase 10: policy-driven sampler creation. Depth/shadow policies materialize real
     * comparison samplers ({@code compareOp = LESS_OR_EQUAL}); without this, shadow lookups
     * would silently sample raw depth. Anisotropy stays disabled and mipmap interpolation
     * stays linear — both documented limitations, not silent behavior.
     */
    public static long getOrCreateSampler(com.vulkairis.resource.SamplerPolicy policy) {
        if (policy == null) {
            return getOrCreateSampler(true, false);
        }
        SamplerKey key = new SamplerKey(policy.minFilter(), policy.magFilter(),
                policy.addressMode(), policy.isCompareEnabled());
        return SAMPLER_CACHE.computeIfAbsent(key, VulkanSampler::createSampler);
    }

    private static long createSampler(SamplerKey key) {
        VkDevice device = VulkanBackendAdapter.getDevice();
        if (!VulkanBackendAdapter.isLive(device)) return 0L;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSamplerCreateInfo samplerInfo = VkSamplerCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
                    .magFilter(key.magFilter())
                    .minFilter(key.minFilter())
                    .addressModeU(key.addressMode())
                    .addressModeV(key.addressMode())
                    .addressModeW(key.addressMode())
                    .anisotropyEnable(false)
                    .borderColor(VK10.VK_BORDER_COLOR_INT_OPAQUE_BLACK)
                    .unnormalizedCoordinates(false)
                    .compareEnable(key.compareEnable())
                    .compareOp(key.compareEnable()
                            ? VK10.VK_COMPARE_OP_LESS_OR_EQUAL : VK10.VK_COMPARE_OP_NEVER)
                    .mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_LINEAR);

            LongBuffer pSampler = stack.mallocLong(1);
            int res = VK10.vkCreateSampler(device, samplerInfo, null, pSampler);
            if (res == VK10.VK_SUCCESS) {
                long handle = pSampler.get(0);
                LOGGER.debug("[Vulkairis/Sampler] Created VkSampler 0x{}", Long.toHexString(handle));
                return handle;
            }
        } catch (Throwable t) {
            LOGGER.error("[Vulkairis/Sampler] Error creating sampler: {}", t.getMessage());
        }

        return 0L;
    }

    public static void cleanUp() {
        VkDevice device = VulkanBackendAdapter.getDevice();
        if (VulkanBackendAdapter.isLive(device)) {
            SAMPLER_CACHE.forEach((key, sampler) -> {
                if (sampler != 0L) {
                    try {
                        VK10.vkDestroySampler(device, sampler, null);
                    } catch (Throwable ignored) {
                    }
                }
            });
        }
        SAMPLER_CACHE.clear();
    }
}
