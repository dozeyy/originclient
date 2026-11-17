package com.vulkairis.plan;

import com.vulkairis.resource.RenderTargetRegistry;
import com.vulkairis.resource.TextureResource;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 5: Typed render target plan and ping-pong schedule.
 *
 * <p>Preserves sparse target indices, formats, dynamic scaling or fixed resolutions, clear colors,
 * mipmapping requests, and ping-pong read/write states across the pass chain. Ensures render-graph
 * outputs map to concrete GPU image views and attachments.</p>
 */
public final class TargetPlan {

    public record TargetEntry(
            String name,
            int index,
            String format,
            int width,
            int height,
            boolean pingPong,
            boolean clearEachFrame,
            String clearColor,
            boolean mipmapped,
            long imageView,
            long sampler
    ) {
        public TargetEntry {
            Objects.requireNonNull(name, "name");
            format = (format != null) ? format : "RGBA8";
            clearColor = (clearColor != null) ? clearColor : "0.0,0.0,0.0,1.0";
        }
    }

    private final Map<String, TargetEntry> targets = new LinkedHashMap<>();
    private final Map<String, Integer> pingPongState = new ConcurrentHashMap<>();
    private final int windowWidth;
    private final int windowHeight;
    private final int shadowResolution;
    private final long generation;

    public TargetPlan(int windowWidth, int windowHeight, int shadowResolution, long generation) {
        this.windowWidth = Math.max(1, windowWidth);
        this.windowHeight = Math.max(1, windowHeight);
        this.shadowResolution = Math.max(1, shadowResolution);
        this.generation = generation;
    }

    public static TargetPlan buildStandard(int windowWidth, int windowHeight, int shadowResolution,
                                           RenderTargetRegistry registry, long generation) {
        TargetPlan plan = new TargetPlan(windowWidth, windowHeight, shadowResolution, generation);

        // Standard colortex0..7 and depth
        for (int i = 0; i < 8; i++) {
            String name = "colortex" + i;
            long imageView = 0L;
            long sampler = 0L;
            if (registry != null) {
                java.util.Optional<TextureResource> opt = registry.get(name);
                if (opt.isPresent()) {
                    imageView = opt.get().getViewHandle();
                    sampler = opt.get().getSamplerHandle();
                }
            }
            plan.registerTarget(new TargetEntry(
                    name, i, "RGBA16F", windowWidth, windowHeight, true, true,
                    "0.0,0.0,0.0,0.0", false, imageView, sampler));
        }

        // Depth targets
        for (int i = 0; i < 3; i++) {
            String name = "depthtex" + i;
            long imageView = 0L;
            long sampler = 0L;
            if (registry != null) {
                java.util.Optional<TextureResource> opt = registry.get(name);
                if (opt.isPresent()) {
                    imageView = opt.get().getViewHandle();
                    sampler = opt.get().getSamplerHandle();
                }
            }
            plan.registerTarget(new TargetEntry(
                    name, i, "DEPTH32F", windowWidth, windowHeight, false, false,
                    "1.0", false, imageView, sampler));
        }

        // Shadow targets
        plan.registerTarget(new TargetEntry(
                "shadowtex0", 0, "DEPTH32F", shadowResolution, shadowResolution, false, true,
                "1.0", false, 0L, 0L));
        plan.registerTarget(new TargetEntry(
                "shadowcolor0", 0, "RGBA8", shadowResolution, shadowResolution, false, true,
                "1.0,1.0,1.0,1.0", false, 0L, 0L));

        return plan;
    }

    public void registerTarget(TargetEntry entry) {
        if (entry != null) {
            targets.put(entry.name().toLowerCase(java.util.Locale.ROOT), entry);
        }
    }

    public TargetEntry getTarget(String name) {
        if (name == null) return null;
        return targets.get(name.toLowerCase(java.util.Locale.ROOT));
    }

    public Map<String, TargetEntry> getAllTargets() {
        return Collections.unmodifiableMap(targets);
    }

    public int getWindowWidth() {
        return windowWidth;
    }

    public int getWindowHeight() {
        return windowHeight;
    }

    public int getShadowResolution() {
        return shadowResolution;
    }

    public long getGeneration() {
        return generation;
    }

    /**
     * TargetSchedule ping-pong management: flips the active side for a target.
     */
    public int flipPingPong(String targetName) {
        return pingPongState.compute(targetName.toLowerCase(java.util.Locale.ROOT),
                (k, v) -> (v == null || v == 0) ? 1 : 0);
    }

    public int getActiveWriteSide(String targetName) {
        return pingPongState.getOrDefault(targetName.toLowerCase(java.util.Locale.ROOT), 0);
    }

    public int getActiveReadSide(String targetName) {
        TargetEntry entry = getTarget(targetName);
        if (entry == null || !entry.pingPong()) {
            return 0;
        }
        return 1 - getActiveWriteSide(targetName);
    }

    public void resetSchedule() {
        pingPongState.clear();
    }
}
