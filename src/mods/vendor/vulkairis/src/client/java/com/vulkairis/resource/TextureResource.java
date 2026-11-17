package com.vulkairis.resource;

import com.vulkairis.framebuffer.VulkanImageResource;
import com.vulkairis.framebuffer.VulkanImageView;
import com.vulkairis.framebuffer.VulkanSampler;
import com.vulkairis.backend.vulkanmod.VulkanBackendAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT;

/**
 * A live render target: its {@link TextureSpec}, its current image layout, its backing Vulkan image
 * and view (created lazily when a device is available), and its producer/consumer graph roles.
 *
 * <p>Metadata exists and is queryable even with no device, so resource planning and graph wiring are
 * testable; the Vulkan handles only materialize once {@link #ensureBacking()} runs against a live
 * device. This mirrors the plan's requirement that a resource carry format, usage, view, sampler
 * policy, current layout, producer and consumers.</p>
 */
public final class TextureResource {

    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkairis/TextureResource");

    private TextureSpec spec;
    private int currentLayout = VK_IMAGE_LAYOUT_UNDEFINED;

    private VulkanImageResource image;
    private VulkanImageView view;
    private long samplerHandle;
    private boolean backingCreated;
    /**
     * Phase 8: whether the backing was cleared since (re)creation. A fresh backing must be
     * cleared before its first read — stale-frame and menu-square artifacts start here.
     */
    private boolean cleared;

    private String producerPass;
    private final Set<String> consumerPasses = new LinkedHashSet<>();

    public TextureResource(TextureSpec spec) {
        this.spec = spec;
    }

    public TextureSpec getSpec() {
        return spec;
    }

    public RenderTargetFormat getFormat() {
        return spec.format();
    }

    public int getWidth() {
        return spec.width();
    }

    public int getHeight() {
        return spec.height();
    }

    public int getCurrentLayout() {
        return currentLayout;
    }

    /**
     * Updates the tracked image layout.
     *
     * @param newLayout the new {@code VkImageLayout}
     * @return whether the layout changed
     */
    public boolean transitionTo(int newLayout) {
        if (currentLayout == newLayout) {
            return false;
        }
        currentLayout = newLayout;
        return true;
    }

    /**
     * Creates the backing image, view and sampler if a Vulkan device is live. Safe to call repeatedly
     * and safe to call with no device (it becomes a no-op, leaving metadata intact).
     *
     * @return whether a backing image exists after the call
     */
    public boolean ensureBacking() {
        if (backingCreated) {
            return image != null && image.getImageHandle() != 0L;
        }
        if (!VulkanBackendAdapter.isLive(VulkanBackendAdapter.getDevice())) {
            return false;
        }
        try {
            image = new VulkanImageResource(
                    spec.width(), spec.height(), spec.format().vkFormat(), spec.format().defaultUsage());
            if (image.getImageHandle() != 0L) {
                view = new VulkanImageView(
                        image.getImageHandle(), spec.format().vkFormat(), spec.format().aspectMask());
            }
            // Phase 10: policy-driven creation so depth/shadow targets get real comparison
            // samplers instead of silently regular ones.
            samplerHandle = VulkanSampler.getOrCreateSampler(spec.sampler());
            backingCreated = true;
            cleared = false;
            LOGGER.debug("[Vulkairis/Resource] Backing created for {}", spec);
            return image.getImageHandle() != 0L;
        } catch (Throwable t) {
            LOGGER.error("[Vulkairis/Resource] Failed to create backing for {}: {}", spec.resource().name(),
                    t.getMessage());
            return false;
        }
    }

    /**
     * Resizes the target, destroying and (if a device is live) recreating its backing.
     *
     * @param width  new width
     * @param height new height
     */
    public void resize(int width, int height) {
        if (width == spec.width() && height == spec.height()) {
            return;
        }
        destroyBacking();
        this.spec = spec.withSize(width, height);
        this.currentLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        this.cleared = false;
        ensureBacking();
    }

    /** Records the pass that produces this target. */
    public void markProducedBy(String passName) {
        this.producerPass = passName;
    }

    /** Records a pass that consumes this target. */
    public void addConsumer(String passName) {
        consumerPasses.add(passName);
    }

    public String getProducerPass() {
        return producerPass;
    }

    public Set<String> getConsumerPasses() {
        return Collections.unmodifiableSet(consumerPasses);
    }

    public long getImageHandle() {
        return image == null ? 0L : image.getImageHandle();
    }

    public long getViewHandle() {
        return view == null ? 0L : view.getViewHandle();
    }

    public long getSamplerHandle() {
        return samplerHandle;
    }

    public boolean isBackingCreated() {
        return backingCreated;
    }

    private void destroyBacking() {
        if (view != null) {
            view.destroy();
            view = null;
        }
        if (image != null) {
            image.destroy();
            image = null;
        }
        samplerHandle = 0L;
        backingCreated = false;
    }

    /** Destroys the backing Vulkan objects. Metadata remains queryable. */
    public void destroy() {
        destroyBacking();
        consumerPasses.clear();
        producerPass = null;
        currentLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        cleared = false;
    }

    /** Records that the backing was cleared; the target is now safe to read. */
    public void markCleared() {
        cleared = true;
    }

    /** @return whether the backing exists and still needs its first clear */
    public boolean needsClear() {
        return backingCreated && !cleared;
    }

    /** @return whether the backing was cleared since (re)creation */
    public boolean isCleared() {
        return cleared;
    }

    @Override
    public String toString() {
        return "TextureResource{" + spec + ", layout=" + currentLayout
                + ", backing=" + (backingCreated ? "yes" : "no") + "}";
    }
}
