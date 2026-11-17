package com.vulkairis.rendering.exec;

import com.vulkairis.resource.RenderTargetRegistry;

import java.util.Set;

/**
 * Phase 12: keeps render targets consistent across window resize and dimension change.
 *
 * <p>Screen-space targets follow the framebuffer size, while fixed-resolution targets such as shadow
 * maps must not resize with the window. Recreating a target resets its layout, so dependent pipelines
 * and barriers are refreshed too.</p>
 */
public final class ResizeCoordinator {

    private final RenderTargetRegistry registry;
    private final Set<String> fixedTargets;
    private int width;
    private int height;

    /**
     * @param registry     the target registry to keep in sync
     * @param fixedTargets target names that must not be resized (e.g. shadow maps)
     * @param width        current width
     * @param height       current height
     */
    public ResizeCoordinator(RenderTargetRegistry registry, Set<String> fixedTargets, int width, int height) {
        this.registry = registry;
        this.fixedTargets = fixedTargets == null ? Set.of() : Set.copyOf(fixedTargets);
        this.width = width;
        this.height = height;
    }

    /**
     * Resizes screen-space targets if the dimensions changed.
     *
     * @param newWidth  new framebuffer width
     * @param newHeight new framebuffer height
     * @return whether a resize occurred
     */
    public boolean onResize(int newWidth, int newHeight) {
        if (newWidth <= 0 || newHeight <= 0 || (newWidth == width && newHeight == height)) {
            return false;
        }
        this.width = newWidth;
        this.height = newHeight;
        registry.resizeAllExcept(fixedTargets, newWidth, newHeight);
        return true;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public Set<String> getFixedTargets() {
        return fixedTargets;
    }
}
