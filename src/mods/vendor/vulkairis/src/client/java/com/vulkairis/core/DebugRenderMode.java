package com.vulkairis.core;

import java.util.Locale;

/**
 * Selects which rendering path Vulkairis drives. This deliberately separates the three
 * failure classes identified during the audit:
 *
 * <ul>
 *     <li>{@link #OFF} / {@link #NORMAL} — no Vulkan override; failures here are Minecraft/Iris
 *     integration failures.</li>
 *     <li>{@link #TRIANGLE} — controlled in-process Vulkan triangle/quad. A failure here is a
 *     Vulkan rendering failure.</li>
 *     <li>{@link #SHADER_TEST} — controlled shader path that loads user-editable GLSL from disk and
 *     runs it through the Vulkairis interception path. A failure here is a shader translation
 *     failure.</li>
 *     <li>{@link #FLAT}, {@link #UV}, {@link #LIGHTMAP}, {@link #DEPTH}, {@link #NO_POST} —
 *     controlled diagnostic visualizers (plan sections 6 and 9) that shade a full-screen quad with
 *     a single built-in fragment program so a staging failure can be localized.</li>
 * </ul>
 *
 * <p>{@link #NORMAL} stays the regular Minecraft/Iris integration path and is deliberately
 * <em>not</em> controlled; it is asserted non-controlled by the regression suite.</p>
 *
 * Mirrors the debug mode list from the implementation plan (section 13).
 */
public enum DebugRenderMode {
    /** Do not record any Vulkairis draw. */
    OFF,
    /** Controlled Vulkan quad using the built-in test shaders. */
    TRIANGLE,
    /** Controlled quad using reloadable shaders from {@code run/vulkairis/}. */
    SHADER_TEST,
    /** Regular Minecraft/Iris integration path (no controlled override). */
    NORMAL,
    /** Visualizer: constant flat colour across the controlled quad. */
    FLAT,
    /** Visualizer: {@code fract(uv)} derived from the quad position. */
    UV,
    /** Visualizer: lightmap-style {@code uv} gradient. */
    LIGHTMAP,
    /** Visualizer: grayscale {@code gl_FragCoord.z}. */
    DEPTH,
    /** Visualizer: pass the interpolated vertex colour through without post-processing. */
    NO_POST;

    public static DebugRenderMode fromString(String value) {
        // Fail safe: anything unrecognised (including null) disables the controlled override
        // rather than silently drawing test geometry over the menu.
        if (value == null) return OFF;
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return switch (normalized) {
            case "OFF", "NONE" -> OFF;
            case "TRIANGLE", "TRI", "QUAD" -> TRIANGLE;
            case "SHADER_TEST", "SHADER", "TEST" -> SHADER_TEST;
            case "NORMAL" -> NORMAL;
            case "FLAT" -> FLAT;
            case "UV" -> UV;
            case "LIGHTMAP", "LIGHT_MAP" -> LIGHTMAP;
            case "DEPTH" -> DEPTH;
            case "NO_POST", "NOPOST" -> NO_POST;
            default -> OFF;
        };
    }

    /** True when this mode records a controlled Vulkairis draw. */
    public boolean isControlled() {
        return this == TRIANGLE || this == SHADER_TEST
                || this == FLAT || this == UV || this == LIGHTMAP || this == DEPTH
                || this == NO_POST;
    }
}
