package com.vulkairis.rendering.exec;

import com.vulkairis.backend.vulkanmod.VulkanBackendAdapter;

/**
 * Compute/image-store execution gate (Phase 11).
 *
 * <p>Compute dispatch and image-store behavior stay disabled until the device actually exposes
 * them: no compute or storage-image work is recorded without a live device. Geometry and
 * tessellation stages are rejected earlier at link time; this gate covers execution.</p>
 */
public final class ComputeGate {

    private ComputeGate() {
    }

    /** @return whether compute work may be recorded right now */
    public static boolean isOperational() {
        try {
            return VulkanBackendAdapter.isLive(VulkanBackendAdapter.getDevice());
        } catch (Throwable t) {
            return false;
        }
    }
}
