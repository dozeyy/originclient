package com.vulkairis.origin.shadow;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Origin (step 6): which sampler names in a program's final GLSL are {@code sampler2DShadow}. Vulkan
 * needs a compare-enabled sampler exactly for those (and forbids one for a plain {@code sampler2D}), so
 * {@link ShadowMap} hands out the compare wrapper only for these names. GPU-free, unit-tested.
 */
public final class ShadowSamplers {
    private static final Pattern SHADOW_SAMPLER = Pattern.compile(
            "\\buniform\\s+(?:(?:lowp|mediump|highp)\\s+)?sampler2DShadow\\s+(\\w+)");

    private ShadowSamplers() {
    }

    /** Names declared {@code uniform sampler2DShadow <name>} in any of the given sources. */
    public static Set<String> compareSamplerNames(String... sources) {
        Set<String> names = new LinkedHashSet<>();
        for (String src : sources) {
            if (src == null) {
                continue;
            }
            Matcher m = SHADOW_SAMPLER.matcher(src);
            while (m.find()) {
                names.add(m.group(1));
            }
        }
        return names;
    }
}
