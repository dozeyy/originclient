package com.vulkairis.core;

import com.vulkairis.rendering.exec.PassGates;

import java.util.Collections;
import java.util.List;

/**
 * Phase 1: Immutable transition record capturing every state transition in the readiness state machine.
 */
public record PackReadinessTransition(
        PackReadinessState fromState,
        PackReadinessState toState,
        boolean success,
        long timestamp,
        long generation,
        String packName,
        String dimension,
        String programIdentifier,
        String failureCause,
        List<PassGates.GateStatus> gateStatuses
) {
    public PackReadinessTransition {
        gateStatuses = (gateStatuses == null) ? List.of() : List.copyOf(gateStatuses);
        programIdentifier = (programIdentifier == null) ? "all" : programIdentifier;
        failureCause = (failureCause == null) ? "none" : failureCause;
        packName = (packName == null) ? "none" : packName;
        dimension = (dimension == null) ? "none" : dimension;
    }

    public static PackReadinessTransition success(
            PackReadinessState fromState,
            PackReadinessState toState,
            long generation,
            String packName,
            String dimension,
            String programIdentifier,
            List<PassGates.GateStatus> gateStatuses
    ) {
        return new PackReadinessTransition(
                fromState, toState, true, System.currentTimeMillis(),
                generation, packName, dimension, programIdentifier, "none", gateStatuses
        );
    }

    public static PackReadinessTransition failure(
            PackReadinessState fromState,
            PackReadinessState toState,
            long generation,
            String packName,
            String dimension,
            String programIdentifier,
            String failureCause,
            List<PassGates.GateStatus> gateStatuses
    ) {
        return new PackReadinessTransition(
                fromState, toState, false, System.currentTimeMillis(),
                generation, packName, dimension, programIdentifier, failureCause, gateStatuses
        );
    }
}
