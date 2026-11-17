package com.vulkairis.rendering.state;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks sampler-name to texture identity per program (Phase 10).
 *
 * <p>When Minecraft reuses or updates a texture object, the same sampler name resolves to a new
 * texture id. Recording the identity per program (plus the pipeline generation that bound it)
 * distinguishes a legitimate rebind from a conflicting assignment, and resource-pack reloads
 * invalidate every entry deterministically instead of leaking stale texture references.</p>
 */
public final class SamplerBindingTracker {

    /** Outcome of a bind call. */
    public enum BindOutcome {
        /** First binding for this sampler in this program. */
        ASSIGNED,
        /** Same texture rebound; nothing changed. */
        SAME,
        /** Texture object changed under a live sampler; the binding must be refreshed. */
        REBOUND
    }

    private record Entry(int textureId, long generation) {
    }

    private final Map<String, Entry> bindings = new ConcurrentHashMap<>();
    private volatile long generation = 0L;

    /**
     * Binds a texture object to a sampler name.
     *
     * @return what the bind did
     */
    public BindOutcome bind(int programId, String sampler, int textureId) {
        String key = programId + ":" + sampler;
        Entry existing = bindings.get(key);
        if (existing == null) {
            bindings.put(key, new Entry(textureId, generation));
            return BindOutcome.ASSIGNED;
        }
        if (existing.textureId() == textureId) {
            return BindOutcome.SAME;
        }
        bindings.put(key, new Entry(textureId, generation));
        return BindOutcome.REBOUND;
    }

    /** @return the bound texture id, or empty when unassigned or invalidated */
    public java.util.OptionalInt boundTexture(int programId, String sampler) {
        Entry entry = bindings.get(programId + ":" + sampler);
        if (entry == null || entry.generation() != generation) {
            return java.util.OptionalInt.empty();
        }
        return java.util.OptionalInt.of(entry.textureId());
    }

    /**
     * Invalidates every binding (resource-pack reload, dimension change). Entries are dropped
     * rather than flagged so no stale texture id can leak into a later frame.
     */
    public void invalidate() {
        generation++;
        bindings.clear();
    }

    /** @return the current invalidation generation */
    public long generation() {
        return generation;
    }

    /** @return number of live bindings */
    public int size() {
        return bindings.size();
    }
}
