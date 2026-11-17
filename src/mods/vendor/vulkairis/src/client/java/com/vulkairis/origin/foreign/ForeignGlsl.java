package com.vulkairis.origin.foreign;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Origin (step 9): text helpers for pack programs prepared for another mod's draw
 * ({@code ForeignPackPrograms}). Pure string logic, unit-tested ({@code ForeignGlslTest}).
 */
public final class ForeignGlsl {
    /** A layout qualifier list that names a descriptor binding. */
    private static final Pattern BINDING_LAYOUT = Pattern.compile("layout\\s*\\(([^)]*\\bbinding\\s*=[^)]*)\\)");
    private static final Pattern SET = Pattern.compile("\\bset\\s*=\\s*\\d+\\s*,?\\s*");

    private ForeignGlsl() {
    }

    /**
     * Puts every descriptor ({@code layout(... binding = N ...)}) into descriptor set {@code set}, so
     * the pack's descriptors sit beside the caller's own set 0 instead of colliding with its bindings.
     * Locations, push constants and anything without a binding are left alone.
     */
    public static String moveToSet(String glsl, int set) {
        if (glsl == null) {
            return null;
        }
        Matcher m = BINDING_LAYOUT.matcher(glsl);
        StringBuilder out = new StringBuilder(glsl.length() + 64);
        while (m.find()) {
            String qualifiers = SET.matcher(m.group(1)).replaceAll("").trim();
            if (qualifiers.endsWith(",")) {
                qualifiers = qualifiers.substring(0, qualifiers.length() - 1).trim();
            }
            m.appendReplacement(out, Matcher.quoteReplacement("layout(set = " + set + ", " + qualifiers + ")"));
        }
        m.appendTail(out);
        return out.toString();
    }
}
