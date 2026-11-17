package com.vulkairis.origin.composite;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Origin (step 2): VulkanMod's render passes and pipelines have exactly one colour attachment, but
 * packs write several buffers per pass (MakeUp: DRAWBUFFERS:016). A pass with N outputs therefore
 * runs N times, each time with a fragment shader that keeps one output at location 0 and turns the
 * others into plain globals (their writes become dead stores). Pure text transform, unit-tested.
 */
public final class FragOutputVariant {
    private static final Pattern OUT_DECL = Pattern.compile(
            "layout\\s*\\(\\s*location\\s*=\\s*(\\d+)\\s*\\)\\s*out\\s+(\\w+)\\s+(\\w+)\\s*;");

    private FragOutputVariant() {
    }

    /** Number of located fragment outputs in {@code glsl}. */
    public static int countOutputs(String glsl) {
        Matcher m = OUT_DECL.matcher(glsl);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    /**
     * @param glsl fragment source with {@code layout(location = N) out} declarations
     * @param keep the location to keep (moved to location 0)
     * @return the patched source
     * @throws IllegalArgumentException when no output has location {@code keep}
     */
    public static String keepOnly(String glsl, int keep) {
        Matcher m = OUT_DECL.matcher(glsl);
        StringBuilder out = new StringBuilder(glsl.length());
        boolean found = false;
        while (m.find()) {
            int location = Integer.parseInt(m.group(1));
            String replacement;
            if (location == keep) {
                found = true;
                replacement = "layout(location = 0) out " + m.group(2) + " " + m.group(3) + ";";
            } else {
                replacement = m.group(2) + " " + m.group(3) + ";";
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        if (!found) {
            throw new IllegalArgumentException("no fragment output at location " + keep);
        }
        return out.toString();
    }
}
