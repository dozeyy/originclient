package com.vulkairis.origin.composite;

import java.util.Map;

/**
 * Origin (step 2): what a composite/final sampler name refers to, using Iris's names and the
 * OptiFine aliases Iris accepts (gcolor = colortex0 ... gaux4 = colortex7, gdepthtex = depthtex0).
 * Pure logic, unit-tested.
 */
public final class SamplerNames {
    public enum Kind { COLORTEX, DEPTH, OTHER }

    /** Kind plus index: colortex number for COLORTEX, depthtex number (0..2) for DEPTH, -1 for OTHER. */
    public record Target(Kind kind, int index) {
    }

    private static final Map<String, Integer> COLOR_ALIASES = Map.of(
            "gcolor", 0, "gdepth", 1, "gnormal", 2, "composite", 3,
            "gaux1", 4, "gaux2", 5, "gaux3", 6, "gaux4", 7);

    private SamplerNames() {
    }

    public static Target resolve(String name) {
        if (name == null) {
            return new Target(Kind.OTHER, -1);
        }
        Integer alias = COLOR_ALIASES.get(name);
        if (alias != null) {
            return new Target(Kind.COLORTEX, alias);
        }
        if (name.equals("gdepthtex")) {
            return new Target(Kind.DEPTH, 0);
        }
        int n = suffix(name, "colortex");
        if (n >= 0 && n < CompositeRouting.MAX_BUFFERS) {
            return new Target(Kind.COLORTEX, n);
        }
        n = suffix(name, "depthtex");
        if (n >= 0 && n <= 2) {
            return new Target(Kind.DEPTH, n);
        }
        return new Target(Kind.OTHER, -1);
    }

    private static int suffix(String name, String prefix) {
        if (!name.startsWith(prefix) || name.length() == prefix.length()) {
            return -1;
        }
        String digits = name.substring(prefix.length());
        for (int i = 0; i < digits.length(); i++) {
            if (!Character.isDigit(digits.charAt(i))) {
                return -1;
            }
        }
        return digits.length() > 2 ? -1 : Integer.parseInt(digits);
    }
}
