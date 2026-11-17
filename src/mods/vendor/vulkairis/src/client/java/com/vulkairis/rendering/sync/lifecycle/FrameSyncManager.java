package com.vulkairis.rendering.sync.lifecycle;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks GPU-CPU synchronization via frame slots and fences.
 * Prevents CPU from overwriting resources still in use by the GPU.
 */
public final class FrameSyncManager {
    private static final int MAX_FRAMES_IN_FLIGHT = 2;
    private static final AtomicLong generation = new AtomicLong(0);

    private static final long[] frameFences = new long[MAX_FRAMES_IN_FLIGHT];
    private static int currentFrameIndex = 0;

    public static void init() {
        for (int i = 0; i < MAX_FRAMES_IN_FLIGHT; i++) {
            frameFences[i] = com.vulkairis.vulkan.VulkanSyncPrimitives.createFence(true);
        }
    }

    public static int getCurrentFrameIndex() {
        return currentFrameIndex;
    }

    public static void beginFrame() {
        long fence = frameFences[currentFrameIndex];
        com.vulkairis.vulkan.VulkanSyncPrimitives.waitForFence(fence, Long.MAX_VALUE);
        com.vulkairis.vulkan.VulkanSyncPrimitives.resetFence(fence);
        currentFrameIndex = (currentFrameIndex + 1) % MAX_FRAMES_IN_FLIGHT;
    }

    public static long getDeviceGeneration() {
        return generation.get();
    }

    public static void bumpGeneration() {
        generation.incrementAndGet();
    }

    public static void reset() {
        generation.set(0);
        currentFrameIndex = 0;
    }
}
