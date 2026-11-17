package com.vulkairis.compatibility;

import com.vulkairis.iris.ShaderPackProgram;
import com.vulkairis.iris.ShaderPackProgramCatalog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Pack-load capability gates (Phase 12).
 *
 * <p>Before any partial rendering begins, the required operations of a discovered pack are
 * classified: graphics programs needing tessellation, geometry or compute stages are rejected
 * outright (their pipeline configuration, capability checks and dispatch do not exist), packs
 * with no executable graphics path are rejected, and packs that merely <em>also</em> ship
 * compute programs are accepted as partial. One actionable verdict, no log spam, no half-built
 * frame.</p>
 */
public final class CapabilityGate {

    /** Pack-load verdict. */
    public enum PackVerdict {
        ACCEPT,
        ACCEPT_PARTIAL,
        REJECT
    }

    /** @param verdict the gate outcome @param notes actionable reasons, empty when clean */
    public record GateVerdict(PackVerdict verdict, List<String> notes) {
        public GateVerdict {
            notes = notes == null ? List.of() : List.copyOf(notes);
        }
    }

    private CapabilityGate() {
    }

    /**
     * Classifies a discovered pack before rendering begins.
     *
     * @param catalog the discovered catalog, or {@code null} for vanilla (accepted)
     */
    public static GateVerdict evaluatePack(ShaderPackProgramCatalog catalog) {
        if (catalog == null || catalog.isEmpty()) {
            return new GateVerdict(PackVerdict.ACCEPT, List.of());
        }
        List<String> partialNotes = new ArrayList<>();
        for (ShaderPackProgram program : catalog.getGraphicsPrograms()) {
            if (!(program instanceof ShaderPackProgram.Graphics graphics)) {
                continue;
            }
            List<String> unsupported = unsupportedStages(graphics);
            if (!unsupported.isEmpty()) {
                return new GateVerdict(PackVerdict.REJECT, List.of(
                        "program '" + graphics.name() + "' requires unsupported "
                                + String.join("/", unsupported)
                                + " stages; tessellation/geometry/compute pipelines are not "
                                + "implemented, so the pack is refused before partial rendering"));
            }
        }
        if (!catalog.hasGraphicsExecutionPath()) {
            return new GateVerdict(PackVerdict.REJECT, List.of(
                    "pack declares no gbuffers/deferred/composite/final execution path; "
                            + "there is nothing executable to bind"));
        }
        // DH degrade pass: distant programs are skipped entirely without the DH mod, so
        // they are partial (never fatal) with one labeled note; graphics families are
        // unaffected. With the mod present they flow through the normal path (Phase G).
        if (!DistantHorizonsSupport.isAvailable() && catalog.hasDistantPrograms()) {
            partialNotes.add(DistantHorizonsSupport.gateNote(catalog.getDistantPrograms()));
        }
        if (!catalog.getComputePrograms().isEmpty()) {
            partialNotes.add(catalog.getComputePrograms().size()
                    + " compute program(s) discovered but never executed; "
                    + "graphics families are unaffected");
        }
        if (partialNotes.isEmpty()) {
            return new GateVerdict(PackVerdict.ACCEPT, List.of());
        }
        return new GateVerdict(PackVerdict.ACCEPT_PARTIAL,
                Collections.unmodifiableList(partialNotes));
    }

    private static List<String> unsupportedStages(ShaderPackProgram.Graphics graphics) {
        List<String> stages = new ArrayList<>();
        if (graphics.hasGeometry()) {
            stages.add("geometry");
        }
        if (graphics.hasTessControl() || graphics.hasTessEval()) {
            stages.add("tessellation");
        }
        return stages;
    }
}
