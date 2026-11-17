package com.vulkairis.origin.shadow;

import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkClearAttachment;
import org.lwjgl.vulkan.VkClearRect;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkFormatProperties;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.lwjgl.vulkan.VkViewport;

import java.nio.LongBuffer;

/**
 * Origin (step 6): the shadow map Iris packs sample: {@code shadowtex0/1} (one D32F depth image) and
 * {@code shadowcolor0/1} (one RGBA8 image), {@code resolution} square.
 *
 * <p>A pack declares the depth samplers either as {@code sampler2DShadow} (hardware compare, MakeUp) or as
 * {@code sampler2D}. Vulkan needs a compare-enabled sampler for the first and forbids one for the second,
 * and VulkanMod keeps one sampler per {@link VulkanImage}, so the depth image has two wrappers over the
 * same view: {@link #depth} (plain, clamp, nearest) and {@link #depthCompare} (LESS_OR_EQUAL compare).
 * Binding the old white RGBA8 placeholder to a {@code sampler2DShadow} was undefined: on AMD it read as
 * "everything in shadow", which made pack terrain dark (local run 2026-11-17).</p>
 *
 * <p>Iris clears shadowcolor to white and depth to 1.0 every frame; {@link #beginAndClear} does the same.</p>
 */
public final class ShadowMap {
    private static final int FORMAT_D32F = 126;                  // VK_FORMAT_D32_SFLOAT
    private static final int FORMAT_RGBA8 = 37;                  // VK_FORMAT_R8G8B8A8_UNORM
    private static final int USAGE_COLOR = 0x4 | 0x10;           // SAMPLED | COLOR_ATTACHMENT
    private static final int USAGE_DEPTH = 0x4 | 0x20;           // SAMPLED | DEPTH_STENCIL_ATTACHMENT
    private static final int LOAD_OP_LOAD = 0;
    private static final int LAYOUT_SHADER_READ_ONLY = 5;

    private static int size;
    private static VulkanImage depth;
    private static VulkanImage depthCompare;
    private static VulkanImage color;
    private static Framebuffer framebuffer;
    private static RenderPass renderPass;
    private static long compareSampler;
    /** True once the map holds this session's clear (or casters): before that, samplers get null (white). */
    private static boolean ready;

    private ShadowMap() {
    }

    public static int size() {
        return size;
    }

    /** (Re)creates the images when the pack's resolution changes. Call outside any render pass. */
    static void ensure(int resolution) {
        int res = Math.max(16, Math.min(resolution, 8192));
        if (depth != null && size == res) {
            return;
        }
        release();
        size = res;
        color = VulkanImage.createTextureImage(VulkanImage.builder(res, res)
                .setName("vulkairis shadowcolor")
                .setFormat(FORMAT_RGBA8)
                .setUsage(USAGE_COLOR)
                .setLinearFiltering(true)
                .setClamp(true));
        depth = VulkanImage.createTextureImage(VulkanImage.builder(res, res)
                .setName("vulkairis shadowtex")
                .setFormat(FORMAT_D32F)
                .setUsage(USAGE_DEPTH)
                .setLinearFiltering(false)
                .setClamp(true));
        compareSampler = createCompareSampler();
        depthCompare = new VulkanImage("vulkairis shadowtex (compare)", depth.getId(), depth.format, 1,
                res, res, 4, depth.usage, depth.getImageView());
        depthCompare.setSampler(compareSampler);
        framebuffer = Framebuffer.builder(color, depth).build();
        renderPass = RenderPass.builder(framebuffer).setLoadOp(LOAD_OP_LOAD).build();
    }

    /** Ends nothing: the caller has ended the main pass. Begins the shadow pass and clears it like Iris. */
    static void beginAndClear(VkCommandBuffer cmd, Renderer renderer) {
        if (!renderer.beginRenderPass(renderPass, framebuffer)) {
            throw new IllegalStateException("could not begin the shadow render pass");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Positive-height viewport: clip y = -1 lands on row 0, OpenGL's memory layout, which is what
            // the pack's shadowPos lookups assume.
            VkViewport.Buffer vp = VkViewport.malloc(1, stack);
            vp.x(0).y(0).width(size).height(size).minDepth(0f).maxDepth(1f);
            VK10.vkCmdSetViewport(cmd, 0, vp);
            Renderer.setScissor(0, 0, size, size);

            VkClearAttachment.Buffer clears = VkClearAttachment.calloc(2, stack);
            VkClearAttachment c = clears.get(0);
            c.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).colorAttachment(0);
            c.clearValue().color().float32(0, 1f).float32(1, 1f).float32(2, 1f).float32(3, 1f);
            VkClearAttachment d = clears.get(1);
            d.aspectMask(VK10.VK_IMAGE_ASPECT_DEPTH_BIT);
            d.clearValue().depthStencil().set(1f, 0);
            VkClearRect.Buffer rect = VkClearRect.calloc(1, stack);
            rect.rect().offset().set(0, 0);
            rect.rect().extent().set(size, size);
            rect.baseArrayLayer(0).layerCount(1);
            VK10.vkCmdClearAttachments(cmd, clears, rect);
        }
    }

    /** Ends the shadow pass and leaves both images shader-readable for gbuffers and the post chain. */
    static void end(VkCommandBuffer cmd, Renderer renderer) {
        renderer.endRenderPass(cmd);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            depth.readOnlyLayout(stack, cmd);
            color.readOnlyLayout(stack, cmd);
        }
        depthCompare.setCurrentLayout(LAYOUT_SHADER_READ_ONLY);
        ready = true;
    }

    /**
     * The image for a shadow sampler name, or null when {@code name} is not one (or the map is not ready,
     * or the shadow pass itself is drawing: a pass must not sample what it writes).
     */
    public static VulkanImage forSampler(String name, boolean compare) {
        if (!ready || ShadowPass.isActive() || name == null) {
            return null;
        }
        return switch (name) {
            case "shadowtex0", "shadowtex1", "shadow", "watershadow" -> compare ? depthCompare : depth;
            case "shadowcolor0", "shadowcolor1", "shadowcolor" -> color;
            default -> null;
        };
    }

    public static boolean isShadowSamplerName(String name) {
        return switch (name) {
            case "shadowtex0", "shadowtex1", "shadow", "watershadow", "shadowcolor0", "shadowcolor1", "shadowcolor" -> true;
            default -> false;
        };
    }

    /** Frees everything (pack change / resolution change). Waits for the GPU first. */
    public static void release() {
        ready = false;
        if (depth == null) {
            return;
        }
        VK10.vkDeviceWaitIdle(DeviceManager.vkDevice);
        try {
            if (renderPass != null) {
                renderPass.cleanUp();
            }
            if (framebuffer != null) {
                framebuffer.cleanUp(false);
            }
        } catch (Throwable ignored) {
        }
        color.doFree();
        depth.doFree();
        if (compareSampler != 0L) {
            VK10.vkDestroySampler(DeviceManager.vkDevice, compareSampler, null);
        }
        color = depth = depthCompare = null;
        framebuffer = null;
        renderPass = null;
        compareSampler = 0L;
        size = 0;
    }

    private static long createCompareSampler() {
        boolean linear = supportsLinear(FORMAT_D32F);
        int filter = linear ? VK10.VK_FILTER_LINEAR : VK10.VK_FILTER_NEAREST;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSamplerCreateInfo info = VkSamplerCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
                    .magFilter(filter)
                    .minFilter(filter)
                    .mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .compareEnable(true)
                    .compareOp(VK10.VK_COMPARE_OP_LESS_OR_EQUAL)
                    .minLod(0f)
                    .maxLod(0f)
                    .borderColor(VK10.VK_BORDER_COLOR_FLOAT_OPAQUE_WHITE);
            LongBuffer out = stack.mallocLong(1);
            int result = VK10.vkCreateSampler(DeviceManager.vkDevice, info, null, out);
            if (result != VK10.VK_SUCCESS) {
                throw new IllegalStateException("vkCreateSampler (shadow compare) failed: " + result);
            }
            return out.get(0);
        }
    }

    private static boolean supportsLinear(int format) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkFormatProperties props = VkFormatProperties.malloc(stack);
            VK10.vkGetPhysicalDeviceFormatProperties(DeviceManager.physicalDevice, format, props);
            return (props.optimalTilingFeatures() & VK10.VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT) != 0;
        } catch (Throwable t) {
            return false;
        }
    }
}
