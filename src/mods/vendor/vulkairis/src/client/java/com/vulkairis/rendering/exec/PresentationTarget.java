package com.vulkairis.rendering.exec;

import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.framebuffer.SwapChain;
import org.lwjgl.vulkan.VkExtent2D;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.lwjgl.vulkan.VK10.VK_FORMAT_UNDEFINED;

/**
 * Phase 12: identifies the authoritative presentation target.
 *
 * <p>The final shader-pack image must reach the actual VulkanMod swapchain image, not a Vulkairis
 * intermediate. This reads the live {@link SwapChain} through VulkanMod's {@link Renderer}, falling
 * back to an offscreen descriptor when no swapchain is available (menu, tests, device not ready).</p>
 */
public final class PresentationTarget {

    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkairis/Presentation");

    /**
     * @param name      target name ({@code "swapchain"} or {@code "offscreen"})
     * @param width     target width
     * @param height    target height
     * @param format    VkFormat of the presentation image
     * @param swapchain whether this is the real swapchain
     */
    public record Info(String name, int width, int height, int format, boolean swapchain) {
    }

    private static volatile Info current =
            new Info("offscreen", 0, 0, VK_FORMAT_UNDEFINED, false);

    private PresentationTarget() {
    }

    /**
     * Refreshes the presentation-target descriptor from the live swapchain.
     *
     * @return the current presentation target
     */
    public static Info capture() {
        try {
            Renderer renderer = Renderer.getInstance();
            if (renderer != null) {
                SwapChain swapChain = renderer.getSwapChain();
                if (swapChain != null && swapChain.hasImages()) {
                    VkExtent2D extent = swapChain.getExtent();
                    current = new Info("swapchain", extent.width(), extent.height(),
                            swapChain.getFormat(), true);
                    return current;
                }
            }
        } catch (Throwable t) {
            LOGGER.debug("[Vulkairis/Presentation] Swapchain unavailable: {}", t.getMessage());
        }
        return current;
    }

    /** @return the most recently captured presentation target */
    public static Info current() {
        return current;
    }

    /** @return whether the real swapchain is currently the presentation target */
    public static boolean isSwapchainAvailable() {
        return current.swapchain();
    }

    /** @return the swapchain color view handle, or {@code 0} when unavailable */
    public static long colorAttachmentView() {
        try {
            Renderer renderer = Renderer.getInstance();
            if (renderer != null) {
                SwapChain swapChain = renderer.getSwapChain();
                if (swapChain != null) {
                    return swapChain.getColorAttachmentView();
                }
            }
        } catch (Throwable ignored) {
        }
        return 0L;
    }

    /** @return the current swapchain image index, or {@code -1} when unavailable */
    public static int currentImageIndex() {
        try {
            return Renderer.getCurrentImage();
        } catch (Throwable ignored) {
            return -1;
        }
    }
}
