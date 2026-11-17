package com.vulkairis.iris;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Deterministic PBR texture generation tracking (Phase 10).
 *
 * <p>Iris owns PBR/specular/normal texture content and reloads it on resource-pack changes;
 * Vulkairis must never serve PBR bindings from an older pack generation. Every pack change,
 * dimension change or reload advances the generation; PBR consumers compare the generation
 * they bound against the current one instead of trusting stale handles.</p>
 */
public final class PbrTextureState {

    private static final AtomicLong GENERATION = new AtomicLong(0);

    private PbrTextureState() {
    }

    /** @return the current PBR generation */
    public static long currentGeneration() {
        return GENERATION.get();
    }

    /**
     * Advances the generation (pack change, reload, dimension change).
     *
     * @param reason why PBR content is stale
     * @return the new generation
     */
    public static long invalidate(String reason) {
        long generation = GENERATION.incrementAndGet();
        com.vulkairis.diagnostics.DiagnosticManager.logStructured(
                com.vulkairis.diagnostics.DiagnosticManager.Category.RESOURCE,
                com.vulkairis.diagnostics.DiagnosticManager.Phase.LIFECYCLE,
                "PBR textures invalidated", reason, null,
                null, null, null, -1, null,
                "Rebind PBR/specular/normal inputs from the new pack generation");
        return generation;
    }

    /** @return whether the generation a binding was made under is stale */
    public static boolean isStale(long bindingGeneration) {
        return bindingGeneration != GENERATION.get();
    }
}
