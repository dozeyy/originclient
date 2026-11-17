package com.vulkairis.resource;

import com.vulkairis.rendergraph.ResourceKind;

import java.util.Optional;

import static org.lwjgl.vulkan.VK10.VK_FORMAT_B10G11R11_UFLOAT_PACK32;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D24_UNORM_S8_UINT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32B32A32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_DEPTH_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_STENCIL_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_SAMPLED_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_STORAGE_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;

/**
 * The concrete Vulkan format of a render target, and the graph-resource kind it plays.
 *
 * <p>Concrete formats matter: a depth target cannot be sampled as color, a float target cannot be
 * substituted for a normalized one, and shadow compare sampling needs a depth format. The registry
 * and pass contracts use this to reject incompatible attachments early.</p>
 */
public enum RenderTargetFormat {
    RGBA8(ResourceKind.COLOR_IMAGE, VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_ASPECT_COLOR_BIT, false, 4),
    RGBA16F(ResourceKind.COLOR_IMAGE, VK_FORMAT_R16G16B16A16_SFLOAT, VK_IMAGE_ASPECT_COLOR_BIT, false, 8),
    RGBA32F(ResourceKind.COLOR_IMAGE, VK_FORMAT_R32G32B32A32_SFLOAT, VK_IMAGE_ASPECT_COLOR_BIT, false, 16),
    R11G11B10F(ResourceKind.COLOR_IMAGE, VK_FORMAT_B10G11R11_UFLOAT_PACK32, VK_IMAGE_ASPECT_COLOR_BIT, false, 4),
    R8(ResourceKind.COLOR_IMAGE, VK_FORMAT_R8_UNORM, VK_IMAGE_ASPECT_COLOR_BIT, false, 1),
    DEPTH32F(ResourceKind.DEPTH_IMAGE, VK_FORMAT_D32_SFLOAT, VK_IMAGE_ASPECT_DEPTH_BIT, true, 4),
    DEPTH24_STENCIL8(ResourceKind.DEPTH_IMAGE, VK_FORMAT_D24_UNORM_S8_UINT,
            VK_IMAGE_ASPECT_DEPTH_BIT | VK_IMAGE_ASPECT_STENCIL_BIT, true, 4);

    private final ResourceKind kind;
    private final int vkFormat;
    private final int aspectMask;
    private final boolean depth;
    private final int bytesPerPixel;

    RenderTargetFormat(ResourceKind kind, int vkFormat, int aspectMask, boolean depth, int bytesPerPixel) {
        this.kind = kind;
        this.vkFormat = vkFormat;
        this.aspectMask = aspectMask;
        this.depth = depth;
        this.bytesPerPixel = bytesPerPixel;
    }

    /** @return the graph-resource kind this format plays */
    public ResourceKind kind() {
        return kind;
    }

    /** @return the Vulkan {@code VkFormat} value */
    public int vkFormat() {
        return vkFormat;
    }

    /** @return the default {@code VkImageAspectFlags} for this format */
    public int aspectMask() {
        return aspectMask;
    }

    /** @return whether this is a depth (or depth/stencil) format */
    public boolean isDepth() {
        return depth;
    }

    /** @return approximate bytes per pixel, used for budgeting */
    public int bytesPerPixel() {
        return bytesPerPixel;
    }

    /** @return whether this format can be sampled as a comparison (shadow) sampler */
    public boolean supportsCompareSampling() {
        return depth;
    }

    /**
     * Phase 8: exact OpenGL internalformat-to-target mapping. Only formats with a precise
     * Vulkan equivalent resolve; everything else yields {@code empty} (explicitly unsupported)
     * instead of a silent downcast. In particular there is no RGB fallback for RGBA targets,
     * no stencil-bearing substitute for stencil-less depth, and no precision downgrade.
     *
     * @param glInternalFormat GL internalformat (e.g. {@code GL_RGBA16F} = 34842)
     * @return the exact target format, or empty when no equivalent exists
     */
    public static Optional<RenderTargetFormat> fromGlInternalFormat(int glInternalFormat) {
        return switch (glInternalFormat) {
            case 32856 -> Optional.of(RGBA8); // GL_RGBA8
            case 34842 -> Optional.of(RGBA16F); // GL_RGBA16F
            case 34836 -> Optional.of(RGBA32F); // GL_RGBA32F
            case 35898 -> Optional.of(R11G11B10F); // GL_R11F_G11F_B10F
            case 33321 -> Optional.of(R8); // GL_R8
            case 36012 -> Optional.of(DEPTH32F); // GL_DEPTH_COMPONENT32F
            case 35056 -> Optional.of(DEPTH24_STENCIL8); // GL_DEPTH24_STENCIL8
            default -> Optional.empty();
        };
    }

    /**
     * @return the default {@code VkImageUsageFlags} for a target of this format: attachment, sampled
     *         read, storage for compute post-processing, and transfer copy/blit
     */
    public int defaultUsage() {
        int attachment = depth
                ? VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT
                : VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
        return attachment
                | VK_IMAGE_USAGE_SAMPLED_BIT
                | VK_IMAGE_USAGE_STORAGE_BIT
                | VK_IMAGE_USAGE_TRANSFER_SRC_BIT
                | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    }
}
