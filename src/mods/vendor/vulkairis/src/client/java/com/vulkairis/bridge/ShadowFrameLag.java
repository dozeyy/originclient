package com.vulkairis.bridge;

import org.joml.Matrix4f;

import java.util.Optional;

/**
 * Phase D shadow timing: the shadow map is drawn one frame late by design.
 *
 * <p>Semantics reimplemented (not copied): the shadow pass of frame {@code N} renders the
 * light-space depth + color from frame {@code N}'s scene; the scene passes of frame
 * {@code N+1} sample that map. Same-framing (render shadow then immediately sample it for
 * the same camera) is explicitly <em>not</em> attempted: it would serialize shadow and
 * scene work and fight the one-frame pipelining the Vulkan submission path relies on.
 * The first frame after load has no shadow map yet and samples must treat shadow as
 * unavailable rather than stale.</p>
 */
public final class ShadowFrameLag {

    private long publishedFrame = -1L;
    private Matrix4f publishedViewProjection;
    private long sampledFrame = -1L;

    /** Records the shadow map produced by {@code frameIndex}. */
    public synchronized void publish(long frameIndex, Matrix4f viewProjection) {
        if (frameIndex < 0L) {
            return;
        }
        this.publishedFrame = frameIndex;
        this.publishedViewProjection = viewProjection == null ? null : new Matrix4f(viewProjection);
    }

    /**
     * Resolves the shadow map a scene pass at {@code frameIndex} must sample: the previous
     * frame's publication.
     *
     * @param frameIndex current scene frame
     * @return the previous frame's view-projection, or empty when unavailable (first frame,
     *         or no publication yet)
     */
    public synchronized Optional<Matrix4f> sampleFor(long frameIndex) {
        if (publishedFrame < 0L || frameIndex != publishedFrame + 1L) {
            return Optional.empty();
        }
        sampledFrame = publishedFrame;
        return Optional.ofNullable(publishedViewProjection == null
                ? null : new Matrix4f(publishedViewProjection));
    }

    /** @return the last published shadow frame, or -1 when nothing published yet */
    public synchronized long publishedFrame() {
        return publishedFrame;
    }

    /** @return the shadow frame last sampled, or -1 when nothing sampled yet */
    public synchronized long sampledFrame() {
        return sampledFrame;
    }

    /** @return whether a scene pass at {@code frameIndex} has a shadow map to sample */
    public synchronized boolean hasShadowFor(long frameIndex) {
        return publishedFrame >= 0L && frameIndex == publishedFrame + 1L;
    }

    /** Drops all state (pack reload, dimension change). */
    public synchronized void invalidate() {
        publishedFrame = -1L;
        publishedViewProjection = null;
        sampledFrame = -1L;
    }

    /** @return design documentation for diagnostics */
    public static String describe() {
        return "shadow draw is one frame late by design: frame N publishes, frame N+1 samples;"
                + " never same-framed";
    }
}
