package com.vulkairis.rendering;

/**
 * High-level graphics backend abstraction decoupling Minecraft/Iris rendering
 * from low-level Vulkan or fallback APIs.
 */
public interface RenderBackend {
    void beginFrame();
    void endFrame();
    void submitDraw(DrawCommand command);
    void flush();
    boolean isAvailable();
}
