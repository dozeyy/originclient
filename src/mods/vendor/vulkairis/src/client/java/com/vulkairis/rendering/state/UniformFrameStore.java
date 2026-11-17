package com.vulkairis.rendering.state;

import com.vulkairis.rendering.state.ProgramUniformStore.UniformValue;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-program, per-frame uniform isolation (Phase 10).
 *
 * <p>The legacy global uniform store keys values by location alone, so two programs sharing a
 * location id overwrite each other and stale values survive reloads. This store keys by
 * program <em>and</em> location, and every entry belongs to exactly one frame: starting a new
 * frame or invalidating (reload/dimension change) drops everything, so no stale value can leak
 * into a later frame or a rebuilt pipeline.</p>
 */
public final class UniformFrameStore {

    private final Map<Integer, Map<Integer, UniformValue>> values = new ConcurrentHashMap<>();
    private volatile long frameIndex = -1L;

    /** Starts a new frame, dropping every value written for earlier frames. */
    public void beginFrame(long frame) {
        if (frame != frameIndex) {
            values.clear();
            frameIndex = frame;
        }
    }

    /** @return the current frame, or -1 when no frame began yet */
    public long frameIndex() {
        return frameIndex;
    }

    /** Drops every value (resource-pack reload, dimension change, pipeline rebuild). */
    public void invalidate() {
        values.clear();
        frameIndex = -1L;
    }

    /** Writes one uniform value for a program. */
    public void write(int programId, int location, UniformValue value) {
        if (value == null) {
            return;
        }
        values.computeIfAbsent(programId, ignored -> new ConcurrentHashMap<>())
                .put(location, value);
    }

    /** @return the value written for this program/location in the current frame, if any */
    public Optional<UniformValue> read(int programId, int location) {
        Map<Integer, UniformValue> program = values.get(programId);
        if (program == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(program.get(location));
    }

    /** @return an unmodifiable snapshot of one program's current-frame values */
    public Map<Integer, UniformValue> snapshot(int programId) {
        Map<Integer, UniformValue> program = values.get(programId);
        if (program == null) {
            return Map.of();
        }
        return Collections.unmodifiableMap(program);
    }

    /** @return how many programs hold values right now */
    public int programCount() {
        return values.size();
    }

    /** @return how many values one program holds right now */
    public int valueCount(int programId) {
        Map<Integer, UniformValue> program = values.get(programId);
        return program == null ? 0 : program.size();
    }
}
