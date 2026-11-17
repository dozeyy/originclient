package com.vulkairis.rendering.exec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates one frame's recorded passes against the planned order (Phase 11).
 *
 * <p>Reports the four failure shapes the plan gates on: missing passes, unexpected duplicate
 * passes, passes nobody planned, and order violations. Pure data in, problems out — no GPU.
 */
public final class FrameTraceValidator {

    private FrameTraceValidator() {
    }

    /** @param problems human-readable problems, empty when the frame is clean */
    public record FrameTrace(List<String> problems) {
        public FrameTrace {
            problems = problems == null ? List.of() : List.copyOf(problems);
        }

        /** @return whether the frame recorded exactly the planned passes, once each, in order */
        public boolean clean() {
            return problems.isEmpty();
        }
    }

    /**
     * @param expected planned pass names in execution order
     * @param recorded pass names actually recorded this frame, in record order
     */
    public static FrameTrace validate(List<String> expected, List<String> recorded) {
        List<String> problems = new ArrayList<>();
        List<String> plan = expected != null ? expected : List.of();
        List<String> got = recorded != null ? recorded : List.of();

        Set<String> seen = new HashSet<>();
        Set<String> duplicates = new HashSet<>();
        for (String pass : got) {
            if (!seen.add(pass)) {
                duplicates.add(pass);
            }
        }
        for (String duplicate : duplicates) {
            problems.add("unexpected duplicate pass '" + duplicate + "'");
        }

        Set<String> planned = new HashSet<>(plan);
        for (String pass : got) {
            if (!planned.contains(pass)) {
                problems.add("unplanned pass '" + pass + "' recorded");
            }
        }
        for (String pass : plan) {
            if (!seen.contains(pass)) {
                problems.add("missing pass '" + pass + "'");
            }
        }

        List<String> ordered = new ArrayList<>();
        for (String pass : got) {
            if (planned.contains(pass) && !ordered.contains(pass)) {
                ordered.add(pass);
            }
        }
        List<String> planOrder = new ArrayList<>();
        for (String pass : plan) {
            if (ordered.contains(pass)) {
                planOrder.add(pass);
            }
        }
        if (!ordered.equals(planOrder)) {
            problems.add("passes recorded out of order: " + ordered + " vs planned " + planOrder);
        }

        return new FrameTrace(Collections.unmodifiableList(problems));
    }
}
