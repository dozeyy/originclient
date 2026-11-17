package com.vulkairis.origin.composite;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Origin (step 2): which physical image each composite/final pass reads and writes, following
 * Iris's ping-pong rule. Pure logic, no GPU, unit-tested.
 *
 * <p>Every colortex has two images, MAIN and ALT. The world (gbuffers) writes MAIN. A composite
 * pass reads each buffer from its current read side and writes its draw buffers to the other
 * side; afterwards every buffer it wrote flips, unless the pack says otherwise with a
 * {@code flip.<pass>.<buffer>} directive. The final pass only reads.</p>
 */
public final class CompositeRouting {
    /** Number of colortex buffers Iris supports (colortex0..15). */
    public static final int MAX_BUFFERS = 16;

    public enum Side { MAIN, ALT }

    /** One composite pass as the pack declares it. */
    public record PassSpec(String name, int[] drawBuffers, Map<Integer, Boolean> explicitFlips) {
        public PassSpec {
            drawBuffers = drawBuffers.clone();
            explicitFlips = explicitFlips == null ? Map.of() : Map.copyOf(explicitFlips);
            for (int b : drawBuffers) {
                if (b < 0 || b >= MAX_BUFFERS) {
                    throw new IllegalArgumentException(name + ": draw buffer " + b + " out of range");
                }
            }
        }
    }

    /** Where one pass reads every buffer from, and which side each of its outputs lands on. */
    public record PassRoute(String name, Side[] readSides, int[] drawBuffers, Side[] writeSides) {
        public Side readSide(int buffer) {
            return readSides[buffer];
        }
    }

    /** The routes for every composite pass plus the read sides the final pass sees. */
    public record Plan(List<PassRoute> passes, Side[] finalReadSides) {
        public Side finalReadSide(int buffer) {
            return finalReadSides[buffer];
        }
    }

    private CompositeRouting() {
    }

    public static Plan plan(List<PassSpec> passes) {
        boolean[] flipped = new boolean[MAX_BUFFERS];
        List<PassRoute> routes = new ArrayList<>(passes.size());
        for (PassSpec pass : passes) {
            Side[] read = sides(flipped);
            int[] outs = pass.drawBuffers();
            Side[] write = new Side[outs.length];
            for (int i = 0; i < outs.length; i++) {
                // Writing the side being read would be a feedback loop: always the other side.
                write[i] = read[outs[i]] == Side.MAIN ? Side.ALT : Side.MAIN;
            }
            routes.add(new PassRoute(pass.name(), read, outs.clone(), write));

            boolean[] next = flipped.clone();
            for (int b : outs) {
                next[b] = !flipped[b];
            }
            for (Map.Entry<Integer, Boolean> flip : pass.explicitFlips().entrySet()) {
                int b = flip.getKey();
                if (b >= 0 && b < MAX_BUFFERS) {
                    next[b] = flip.getValue() ? !flipped[b] : flipped[b];
                }
            }
            flipped = next;
        }
        return new Plan(Collections.unmodifiableList(routes), sides(flipped));
    }

    /**
     * The buffer the vanilla world image stands in for until gbuffers run on Vulkan (step 5):
     * the first draw buffer of {@code gbuffers_terrain}, else of {@code gbuffers_textured}, else
     * colortex0 (Iris's default when a program declares none).
     */
    public static int sceneBuffer(int[] terrainDrawBuffers, int[] texturedDrawBuffers) {
        if (terrainDrawBuffers != null && terrainDrawBuffers.length > 0) {
            return terrainDrawBuffers[0];
        }
        if (texturedDrawBuffers != null && texturedDrawBuffers.length > 0) {
            return texturedDrawBuffers[0];
        }
        return 0;
    }

    private static Side[] sides(boolean[] flipped) {
        Side[] out = new Side[MAX_BUFFERS];
        Arrays.setAll(out, i -> flipped[i] ? Side.ALT : Side.MAIN);
        return out;
    }
}
