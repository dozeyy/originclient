package com.vulkairis.core;

import com.vulkairis.diagnostics.DiagnosticManager;
import com.vulkairis.iris.ShaderPackProgramCatalog;
import com.vulkairis.rendering.exec.PassGates;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Phase 1: Production readiness state machine governing shader pack lifecycle transitions.
 * Enforces truthful gating: no pack can reach EXECUTION_READY or RUNNING while required pass
 * families are CLOSED/PARTIAL or required GPU resources are unbacked.
 */
public class PackReadinessStateMachine {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkairis/Readiness");
    private static final int MAX_HISTORY = 64;

    private final Object lock = new Object();
    private volatile PackReadinessState currentState = PackReadinessState.NO_PACK;
    private volatile PackReadinessTransition lastTransition = null;
    private final Deque<PackReadinessTransition> history = new ArrayDeque<>();

    public PackReadinessState getState() {
        return currentState;
    }

    public PackReadinessTransition getLastTransition() {
        synchronized (lock) {
            return lastTransition;
        }
    }

    public List<PackReadinessTransition> getHistory(int limit) {
        synchronized (lock) {
            int count = Math.min(limit, history.size());
            List<PackReadinessTransition> result = new ArrayList<>(count);
            for (PackReadinessTransition t : history) {
                if (result.size() >= count) break;
                result.add(t);
            }
            return List.copyOf(result);
        }
    }

    /**
     * Resets the state machine back to NO_PACK (e.g. on pack unload or game reset).
     */
    public PackReadinessTransition reset(long generation, String reason) {
        synchronized (lock) {
            PackReadinessState prev = currentState;
            currentState = PackReadinessState.NO_PACK;
            PackReadinessTransition transition = PackReadinessTransition.success(
                    prev, PackReadinessState.NO_PACK, generation, "none", "none",
                    "all", List.of()
            );
            recordTransition(transition);
            LOGGER.info("[Vulkairis/Readiness] Reset state machine to NO_PACK: {}", reason);
            return transition;
        }
    }

    /**
     * Attempts to transition to the specified target state.
     * Enforces pass gates and resource requirements when advancing towards execution.
     *
     * @param target the desired state
     * @param catalog the discovered catalog (if any)
     * @param generation the current pack/dimension pipeline generation
     * @param packName active pack name
     * @param dimension current dimension identifier
     * @param programIdentifier program name or "all"
     * @param hasGpuResources true if real VulkanMod resources and descriptors are backed
     * @param failureReason if caller encountered a failure, reason string; otherwise null
     * @return the resulting transition record (success or failure/rejection/degraded)
     */
    public PackReadinessTransition transitionTo(
            PackReadinessState target,
            ShaderPackProgramCatalog catalog,
            long generation,
            String packName,
            String dimension,
            String programIdentifier,
            boolean hasGpuResources,
            String failureReason
    ) {
        synchronized (lock) {
            PackReadinessState from = currentState;
            List<PassGates.GateStatus> gateStatuses = (catalog != null)
                    ? PassGates.evaluate(catalog)
                    : List.of();

            // 1. Caller-signaled failure immediately transitions to REJECTED or DEGRADED
            if (failureReason != null && !failureReason.isBlank() && !failureReason.equals("none")) {
                PackReadinessState next = (target == PackReadinessState.DEGRADED)
                        ? PackReadinessState.DEGRADED
                        : PackReadinessState.REJECTED;
                currentState = next;
                PackReadinessTransition t = PackReadinessTransition.failure(
                        from, next, generation, packName, dimension, programIdentifier,
                        failureReason, gateStatuses
                );
                recordTransition(t);
                LOGGER.warn("[Vulkairis/Readiness] Transition {} -> {} failed: {}", from, next, failureReason);
                DiagnosticManager.log(DiagnosticManager.Category.COMPATIBILITY,
                        "Readiness transition failed", from + " -> " + next + ": " + failureReason);
                return t;
            }

            // 2. Gate-check: advancing to EXECUTION_READY or RUNNING is strictly forbidden
            // if any required pass family is CLOSED or PARTIAL.
            if (target == PackReadinessState.EXECUTION_READY || target == PackReadinessState.RUNNING) {
                if (catalog != null) {
                    Map<PassGates.Gate, PassGates.GateStatus> implemented = PassGates.implemented();
                    Set<PassGates.Gate> required = PassGates.requiredFor(catalog);

                    for (PassGates.Gate gate : required) {
                        PassGates.GateStatus status = implemented.get(gate);
                        if (status != null && status.status() != PassGates.Status.OPEN) {
                            String rejection = "Required gate " + gate + " is " + status.status()
                                    + ": " + status.note() + "; cannot promote to " + target;
                            currentState = PackReadinessState.DEGRADED;
                            PackReadinessTransition t = PackReadinessTransition.failure(
                                    from, PackReadinessState.DEGRADED, generation, packName, dimension,
                                    programIdentifier, rejection, gateStatuses
                            );
                            recordTransition(t);
                            LOGGER.warn("[Vulkairis/Readiness] Promotion blocked: {}", rejection);
                            DiagnosticManager.log(DiagnosticManager.Category.COMPATIBILITY,
                                    "Promotion blocked by pass gate", rejection);
                            return t;
                        }
                    }
                }

                if (!hasGpuResources) {
                    String rejection = "Required GPU resources / descriptor bindings are not backed; cannot promote to " + target;
                    currentState = PackReadinessState.DEGRADED;
                    PackReadinessTransition t = PackReadinessTransition.failure(
                            from, PackReadinessState.DEGRADED, generation, packName, dimension,
                            programIdentifier, rejection, gateStatuses
                    );
                    recordTransition(t);
                    LOGGER.warn("[Vulkairis/Readiness] Promotion blocked: {}", rejection);
                    return t;
                }
            }

            // 3. Normal valid transition
            currentState = target;
            PackReadinessTransition t = PackReadinessTransition.success(
                    from, target, generation, packName, dimension, programIdentifier, gateStatuses
            );
            recordTransition(t);
            LOGGER.info("[Vulkairis/Readiness] Transition {} -> {} (generation={})", from, target, generation);
            DiagnosticManager.log(DiagnosticManager.Category.COMPATIBILITY,
                    "Readiness transition", from + " -> " + target + " [pack=" + packName + "]");
            return t;
        }
    }

    private void recordTransition(PackReadinessTransition transition) {
        lastTransition = transition;
        if (history.size() >= MAX_HISTORY) {
            history.removeLast();
        }
        history.addFirst(transition);
    }
}
