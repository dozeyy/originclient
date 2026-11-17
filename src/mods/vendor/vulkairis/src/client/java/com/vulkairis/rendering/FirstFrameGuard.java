package com.vulkairis.rendering;

/**
 * Phase 6: guards against first-frame square / stale-target flash.
 *
 * <p>The first frame after a pipeline build or resize must not present a stale or
 * half-populated target. This guard tracks whether the current generation has produced
 * at least one fully executed frame; callers skip presentation until it is ready.</p>
 */
public final class FirstFrameGuard {

    private volatile long readyGeneration = -1L;
    private volatile boolean firstFrameDone = false;

    public FirstFrameGuard() {
    }

    /** Marks the given generation as having completed a full frame. */
    public void markReady(long generation) {
        readyGeneration = generation;
        firstFrameDone = true;
    }

    /** @return whether the current generation has completed a full frame */
    public boolean isReady(long currentGeneration) {
        return firstFrameDone && readyGeneration == currentGeneration;
    }

    /** Resets on pipeline rebuild / resize. */
    public void reset() {
        readyGeneration = -1L;
        firstFrameDone = false;
    }

    /** @return whether this is the very first frame (no frame completed yet) */
    public boolean isFirstFrame() {
        return !firstFrameDone;
    }
}