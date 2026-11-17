package com.vulkairis.vulkan.descriptor;

import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;

/**
 * Descriptor-set numbering convention used by Vulkairis.
 *
 * <p>A single generic set is not enough: frame/global values change once per frame, pass resources
 * change per pass, and compute storage resources are bound only by compute passes. Keeping them in
 * distinct sets lets the descriptor layer update only what changed.</p>
 */
public enum DescriptorSetConvention {

    /** Set 0: frame/global resources (uniform blocks, noise, celestial data). */
    FRAME(0),

    /** Set 1: pass resources (sampled images and combined image samplers). */
    PASS(1),

    /** Set 2: compute resources (storage images/buffers). */
    COMPUTE(2);

    private final int set;

    DescriptorSetConvention(int set) {
        this.set = set;
    }

    /** @return the Vulkan descriptor set number */
    public int set() {
        return set;
    }

    /** @return the descriptor type used for uniform blocks in this convention */
    public static int uniformBufferType() {
        return VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
    }

    /** @return the descriptor type used for sampled textures */
    public static int combinedImageSamplerType() {
        return VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    }

    /** @return the descriptor type used for storage images */
    public static int storageImageType() {
        return VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    }
}
