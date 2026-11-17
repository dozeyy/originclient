package com.vulkairis.bridge;

import com.vulkairis.resource.TextureResource;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Best-effort factory for VulkanMod {@link Framebuffer}/{@link RenderPass} pairs backed by
 * Vulkairis-managed texture handles. Every method is guarded so a failure degrades to
 * {@code null} instead of crashing the frame.
 */
public final class VulkanModFramebufferFactory {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkairis/FbFactory");

    private VulkanModFramebufferFactory() {
    }

    /**
     * Creates a color-only offscreen framebuffer + render pass for the given texture resource.
     *
     * @return a two-element array {@code {framebuffer, renderPass}}, or {@code null} on failure.
     */
    public static Object[] create(TextureResource resource) {
        if (resource == null) return null;
        try {
            long imageHandle = resource.getImageHandle();
            long viewHandle = 0L;
            try {
                // TextureResource stores its view via ensureBacking; reflect to read it.
                java.lang.reflect.Field f = TextureResource.class.getDeclaredField("view");
                f.setAccessible(true);
                Object view = f.get(resource);
                if (view != null) {
                    java.lang.reflect.Method m = view.getClass().getMethod("getViewHandle");
                    Object r = m.invoke(view);
                    if (r instanceof Long l) viewHandle = l;
                }
            } catch (Throwable ignored) {
            }
            if (imageHandle == 0L) {
                resource.ensureBacking();
                imageHandle = resource.getImageHandle();
            }
            if (imageHandle == 0L) return null;

            int format = resource.getSpec().format().vkFormat();
            int width = resource.getWidth();
            int height = resource.getHeight();

            // Wrap our handle in a VulkanMod VulkanImage.
            VulkanImage vkImage = new VulkanImage(
                    resource.getSpec().resource().name(),
                    imageHandle,
                    format,
                    width,
                    height,
                    1, 1,
                    4,
                    viewHandle);

            Framebuffer.Builder fbBuilder = Framebuffer.builder(vkImage, null);
            Framebuffer fb = fbBuilder.build();
            if (fb == null) return null;

            RenderPass rp = RenderPass.builder(fb).build();
            if (rp == null) {
                try { fb.cleanUp(); } catch (Throwable ignored) {}
                return null;
            }
            return new Object[]{fb, rp};
        } catch (Throwable t) {
            LOGGER.warn("[Vulkairis/FbFactory] Failed to create framebuffer for {}: {}",
                    resource.getSpec().resource().name(),
                    t.getMessage() != null ? t.getMessage() : t.toString());
            return null;
        }
    }
}