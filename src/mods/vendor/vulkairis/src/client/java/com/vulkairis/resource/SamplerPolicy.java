package com.vulkairis.resource;

import static org.lwjgl.vulkan.VK10.VK_FILTER_LINEAR;
import static org.lwjgl.vulkan.VK10.VK_FILTER_NEAREST;
import static org.lwjgl.vulkan.VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
import static org.lwjgl.vulkan.VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT;

/**
 * The sampling policy of a render target. Shader packs distinguish smooth scene color, pixel-exact
 * data (normals, depth), repeating noise, and comparison shadow sampling; those map to different
 * {@code VkSampler} settings and must not be collapsed into one default.
 */
public enum SamplerPolicy {
    LINEAR_CLAMP(VK_FILTER_LINEAR, VK_FILTER_LINEAR, VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE, false),
    LINEAR_REPEAT(VK_FILTER_LINEAR, VK_FILTER_LINEAR, VK_SAMPLER_ADDRESS_MODE_REPEAT, false),
    NEAREST_CLAMP(VK_FILTER_NEAREST, VK_FILTER_NEAREST, VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE, false),
    NEAREST_REPEAT(VK_FILTER_NEAREST, VK_FILTER_NEAREST, VK_SAMPLER_ADDRESS_MODE_REPEAT, false),
    SHADOW_COMPARE(VK_FILTER_LINEAR, VK_FILTER_LINEAR, VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE, true);

    private final int minFilter;
    private final int magFilter;
    private final int addressMode;
    private final boolean compareEnable;

    SamplerPolicy(int minFilter, int magFilter, int addressMode, boolean compareEnable) {
        this.minFilter = minFilter;
        this.magFilter = magFilter;
        this.addressMode = addressMode;
        this.compareEnable = compareEnable;
    }

    public int minFilter() {
        return minFilter;
    }

    public int magFilter() {
        return magFilter;
    }

    public int addressMode() {
        return addressMode;
    }

    /** @return whether this sampler performs depth comparison (shadow sampling) */
    public boolean isCompareEnabled() {
        return compareEnable;
    }

    /** @return whether this policy filters linearly */
    public boolean isLinear() {
        return minFilter == VK_FILTER_LINEAR || magFilter == VK_FILTER_LINEAR;
    }
}
