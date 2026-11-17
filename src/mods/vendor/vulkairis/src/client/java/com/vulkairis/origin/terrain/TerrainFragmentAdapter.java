package com.vulkairis.origin.terrain;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Origin (2026-11-17): the alpha test Iris adds to cutout terrain.
 *
 * <p>Iris never relies on a pack to discard the transparent texels of grass, flowers, leaves or
 * saplings: when it builds a terrain program it appends its own alpha test to the first fragment
 * output ({@code AlphaTests.ONE_TENTH_ALPHA}, "alpha &gt; 0.1", unless the pack sets
 * {@code alphaTest.<program>}). Vulkairis links the terrain family from the pack's own source, which
 * has no such test (MakeUp's gbuffers_terrain only discards for entities), so every cutout block
 * drew its whole quad: tall grass as opaque green squares, plus their square shadows.</p>
 *
 * <p>This wraps the translated fragment {@code main} and discards when location 0's alpha is not
 * above the reference, exactly Iris's condition. Translucent programs (gbuffers_water,
 * shadow_translucent) are left alone, as Iris leaves them.</p>
 */
public final class TerrainFragmentAdapter {
    /** Iris's default cutout reference ({@code AlphaTests.ONE_TENTH_ALPHA}). */
    public static final float CUTOUT_REFERENCE = 0.1f;

    private static final Pattern MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*(?:void\\s*)?\\)");
    private static final Pattern OUTPUT_ZERO = Pattern.compile(
            "layout\\s*\\(\\s*location\\s*=\\s*0\\s*\\)\\s*out\\s+vec4\\s+([A-Za-z_]\\w*)\\s*;");

    private TerrainFragmentAdapter() {
    }

    /** Whether Iris alpha-tests this terrain-family program (everything but the translucent ones). */
    public static boolean alphaTested(String programName) {
        return programName != null && !programName.equals("gbuffers_water") && !programName.equals("shadow_translucent");
    }

    /**
     * The fragment source with Iris's alpha test after the pack's main, or the source unchanged when it
     * has no main or no vec4 output at location 0 (nothing to test).
     */
    public static String alphaTest(String fragmentSource, float reference) {
        if (fragmentSource == null) {
            return null;
        }
        Matcher out = OUTPUT_ZERO.matcher(fragmentSource);
        Matcher main = MAIN.matcher(fragmentSource);
        if (!out.find() || !main.find()) {
            return fragmentSource;
        }
        String body = main.replaceFirst("void vulkairis_packMain()");
        return body + "\n// Origin: Iris's cutout alpha test (TerrainFragmentAdapter).\n"
                + "void main() {\n"
                + "    vulkairis_packMain();\n"
                + "    if (!(" + out.group(1) + ".a > " + reference + ")) {\n"
                + "        discard;\n"
                + "    }\n"
                + "}\n";
    }
}
