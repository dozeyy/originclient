package com.vulkairis.rendering.state;

import com.vulkairis.diagnostics.DiagnosticManager;

import java.util.OptionalInt;

/**
 * Validated texture-unit mapping (Phase 10).
 *
 * <p>Replaces the scattered {@code [0, 11]} range checks with one policy: VulkanMod exposes 12
 * texture units, so virtual units map 1:1 while in range, and anything above
 * {@link #MAX_SUPPORTED_UNIT} is refused with the exact limit named — once per unit, via
 * diagnostic dedup — instead of being silently dropped. Programs that need more samplers than
 * units are refused at link time by the program linker, which cites the same limit.</p>
 */
public final class TextureUnitPolicy {

    /** GL_TEXTURE0 enum base. */
    public static final int GL_TEXTURE0 = 33984;

    /** Highest virtual texture unit the bridge supports (VulkanMod exposes units 0-11). */
    public static final int MAX_SUPPORTED_UNIT = 11;

    /** Number of supported units. */
    public static final int SUPPORTED_UNIT_COUNT = MAX_SUPPORTED_UNIT + 1;

    private TextureUnitPolicy() {
    }

    /**
     * Maps a GL texture enum to its unit, refusing out-of-range values loudly.
     *
     * @param glTextureEnum e.g. {@code GL_TEXTURE0 + unit}
     * @return the unit, or empty when outside the supported range (diagnosed once per unit)
     */
    public static OptionalInt toUnit(int glTextureEnum) {
        int unit = glTextureEnum - GL_TEXTURE0;
        if (unit < 0 || unit > MAX_SUPPORTED_UNIT) {
            DiagnosticManager.logStructured(DiagnosticManager.Category.RESOURCE,
                    DiagnosticManager.Phase.BIND,
                    "Texture unit out of range",
                    "unit " + unit + " exceeds the supported bridge range 0.."
                            + MAX_SUPPORTED_UNIT,
                    null, null, null, null, -1, null,
                    "Only units 0.." + MAX_SUPPORTED_UNIT + " exist; the sampler stays unbound");
            return OptionalInt.empty();
        }
        return OptionalInt.of(unit);
    }

    /** @return whether the unit is within the supported range (no diagnostics) */
    public static boolean isSupported(int unit) {
        return unit >= 0 && unit <= MAX_SUPPORTED_UNIT;
    }

    /** Reports an out-of-range unit with the exact limit (deduplicated); no mapping. */
    public static void noteOutOfRange(int unit) {
        DiagnosticManager.logStructured(DiagnosticManager.Category.RESOURCE,
                DiagnosticManager.Phase.BIND,
                "Texture unit out of range",
                "unit " + unit + " exceeds the supported bridge range 0.."
                        + MAX_SUPPORTED_UNIT,
                null, null, null, null, -1, null,
                "Only units 0.." + MAX_SUPPORTED_UNIT + " exist; the sampler stays unbound");
    }
}
