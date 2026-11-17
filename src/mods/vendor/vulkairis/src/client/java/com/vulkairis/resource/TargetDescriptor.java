package com.vulkairis.resource;

import com.vulkairis.rendergraph.RenderResource;

import java.util.Arrays;
import java.util.Objects;

/**
 * The full plan descriptor for one render target (Phase 8): logical Iris identity, size
 * scaling, concrete Vulkan format, sample count, color/depth role, clear/load/store
 * operations, sampling use, lifetime, owning pass and clear values.
 *
 * <p>A descriptor is what the target graph allocates from; {@link TextureSpec} is the
 * allocation-time view derived from it at a concrete base resolution.</p>
 */
public record TargetDescriptor(
        String logicalId,
        float widthScale,
        float heightScale,
        RenderTargetFormat format,
        int samples,
        Role role,
        LoadOp loadOp,
        StoreOp storeOp,
        boolean sampled,
        boolean transientTarget,
        String owningPass,
        float[] clearColor,
        float clearDepth) {

    /** Color or depth role; depth roles require depth formats. */
    public enum Role {
        COLOR,
        DEPTH
    }

    /** Attachment load behavior. */
    public enum LoadOp {
        LOAD,
        CLEAR,
        DONT_CARE
    }

    /** Attachment store behavior. */
    public enum StoreOp {
        STORE,
        DONT_CARE
    }

    public TargetDescriptor {
        Objects.requireNonNull(logicalId, "logicalId");
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(loadOp, "loadOp");
        Objects.requireNonNull(storeOp, "storeOp");
        if (logicalId.isBlank()) {
            throw new IllegalArgumentException("target logical id must be non-blank");
        }
        if (widthScale <= 0.0f || heightScale <= 0.0f || !Float.isFinite(widthScale)
                || !Float.isFinite(heightScale)) {
            throw new IllegalArgumentException("target '" + logicalId
                    + "' has non-positive scale " + widthScale + "x" + heightScale);
        }
        if (samples < 1 || (samples & (samples - 1)) != 0) {
            throw new IllegalArgumentException("target '" + logicalId
                    + "' sample count must be a power of two >= 1, got " + samples);
        }
        if (role == Role.DEPTH && !format.isDepth()) {
            throw new IllegalArgumentException("target '" + logicalId
                    + "' is a depth target but format " + format + " is not a depth format");
        }
        if (role == Role.COLOR && format.isDepth()) {
            throw new IllegalArgumentException("target '" + logicalId
                    + "' is a color target but format " + format + " is a depth format");
        }
        clearColor = clearColor == null ? new float[]{0.0f, 0.0f, 0.0f, 1.0f}
                : Arrays.copyOf(clearColor, 4);
        owningPass = owningPass == null ? "" : owningPass;
    }

    /** @return a full-resolution persistent color target cleared on load */
    public static TargetDescriptor color(String logicalId, RenderTargetFormat format) {
        return new TargetDescriptor(logicalId, 1.0f, 1.0f, format, 1, Role.COLOR,
                LoadOp.CLEAR, StoreOp.STORE, true, false, "", null, 1.0f);
    }

    /** @return a full-resolution persistent depth target cleared on load */
    public static TargetDescriptor depth(String logicalId, RenderTargetFormat format) {
        return new TargetDescriptor(logicalId, 1.0f, 1.0f, format, 1, Role.DEPTH,
                LoadOp.CLEAR, StoreOp.STORE, true, false, "", null, 1.0f);
    }

    /** @return the pixel width at the given base resolution (at least 1) */
    public int resolveWidth(int baseWidth) {
        return Math.max(1, Math.round(baseWidth * widthScale));
    }

    /** @return the pixel height at the given base resolution (at least 1) */
    public int resolveHeight(int baseHeight) {
        return Math.max(1, Math.round(baseHeight * heightScale));
    }

    /**
     * @return the allocation-time spec at the given base resolution
     */
    public TextureSpec toSpec(int baseWidth, int baseHeight, SamplerPolicy sampler) {
        RenderResource resource = role == Role.DEPTH
                ? RenderResource.depth(logicalId)
                : RenderResource.color(logicalId);
        if (transientTarget) {
            resource = resource.asTransient();
        }
        return TextureSpec.of(resource, format,
                resolveWidth(baseWidth), resolveHeight(baseHeight), sampler);
    }
}
