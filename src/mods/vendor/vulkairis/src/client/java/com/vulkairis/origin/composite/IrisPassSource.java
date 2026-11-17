package com.vulkairis.origin.composite;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;

import java.util.ArrayList;
import java.util.List;

/**
 * Origin (step 2): reads the active pack's post chain from Iris's own parsed program set
 * (program names, DRAWBUFFERS/RENDERTARGETS, flip directives), so routing follows the pack exactly
 * as Iris understands it.
 *
 * <p>Deferred passes run first, then composite passes, in one ping-pong sequence (Iris carries the
 * flip state from deferred into composite). Until the gbuffers step lands, the vanilla world stands in
 * for the gbuffers, so deferred passes see the finished world rather than opaque-only geometry.</p>
 */
public final class IrisPassSource {
    /** The pack's post chain: deferred + composite passes in order, the final pass name, and the scene buffer. */
    public record Chain(List<CompositeRouting.PassSpec> passes, String finalName, int sceneBuffer) {
    }

    private IrisPassSource() {
    }

    /** The active program set, or {@code null} when no pack is active. Cheap; used as a cache key. */
    public static ProgramSet currentSet() {
        try {
            var pack = Iris.getCurrentPack();
            return pack.isEmpty() ? null : pack.get().getProgramSet(Iris.getCurrentDimension());
        } catch (Throwable t) {
            return null;
        }
    }

    /** The chain for {@code set}, or {@code null} when it cannot be read. */
    public static Chain chainOf(ProgramSet set) {
        if (set == null) {
            return null;
        }
        try {
            List<CompositeRouting.PassSpec> passes = new ArrayList<>();
            addAll(passes, set.getComposite(ProgramArrayId.Deferred));
            addAll(passes, set.getComposite(ProgramArrayId.Composite));
            String finalName = set.get(ProgramId.Final).filter(ProgramSource::isValid)
                    .map(ProgramSource::getName).orElse(null);
            int scene = CompositeRouting.sceneBuffer(drawBuffers(set, ProgramId.Terrain),
                    drawBuffers(set, ProgramId.Textured));
            return new Chain(List.copyOf(passes), finalName, scene);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void addAll(List<CompositeRouting.PassSpec> out, ProgramSource[] sources) {
        if (sources == null) {
            return;
        }
        for (ProgramSource source : sources) {
            if (source == null || !source.isValid()) {
                continue;
            }
            var directives = source.getDirectives();
            out.add(new CompositeRouting.PassSpec(source.getName(),
                    directives.getDrawBuffers(), directives.getExplicitFlips()));
        }
    }

    private static int[] drawBuffers(ProgramSet set, ProgramId id) {
        return set.get(id).filter(ProgramSource::isValid)
                .map(s -> s.getDirectives().getDrawBuffers()).orElse(null);
    }
}
