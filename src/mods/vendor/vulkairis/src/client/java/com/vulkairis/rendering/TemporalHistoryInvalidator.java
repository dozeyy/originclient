package com.vulkairis.rendering;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Phase 11: tracks the events that must invalidate temporal history buffers.
 *
 * <p>Temporal buffers (motion vectors, previous depth/color) are only valid while the
 * camera, dimension, pack, render scale and world stay stable. Any of the listed events
 * forces a reset so stale history can never bleed into a new scene.</p>
 */
public final class TemporalHistoryInvalidator {

    public enum Event {
        RESIZE,
        TELEPORT,
        DIMENSION_SWITCH,
        CAMERA_CUT,
        PACK_SWITCH,
        RENDER_SCALE_CHANGE,
        SHADER_RESET,
        WORLD_RELOAD
    }

    private final List<Event> pending = new ArrayList<>();
    private boolean invalidated = false;

    public TemporalHistoryInvalidator() {
    }

    /** Records an invalidating event. */
    public void invalidate(Event event) {
        if (event == null) {
            return;
        }
        if (!pending.contains(event)) {
            pending.add(event);
        }
        invalidated = true;
    }

    /** @return whether any invalidation is pending */
    public boolean isInvalidated() {
        return invalidated;
    }

    /** @return the pending events (unmodifiable) */
    public List<Event> pendingEvents() {
        return Collections.unmodifiableList(new ArrayList<>(pending));
    }

    /** Clears pending state after history buffers have been reset. */
    public void clear() {
        pending.clear();
        invalidated = false;
    }

    /** @return whether the given event type is pending */
    public boolean isPending(Event event) {
        return pending.contains(event);
    }
}