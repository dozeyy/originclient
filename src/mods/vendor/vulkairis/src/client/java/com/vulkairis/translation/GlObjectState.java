package com.vulkairis.translation;

/**
 * Explicit virtual shader/program object states (Phase 5).
 *
 * <p>A virtual object is never "ready" by virtue of its integer handle existing: callers must
 * consult the state. {@code LINKED} applies to programs whose stages validated; shaders never
 * reach it. {@code INVALIDATED} marks live objects whose references went stale (for example a
 * program still pointing at a deleted shader). {@code DELETED} marks tombstoned ids, which fail
 * every query predictably instead of aliasing a recycled handle — ids are never reused.
 */
public enum GlObjectState {
    CREATED,
    COMPILED,
    FAILED,
    LINKED,
    INVALIDATED,
    DELETED
}
