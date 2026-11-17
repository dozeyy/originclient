package com.vulkairis.rendering;

/**
 * Phase 10: color-space helpers (linear <-> sRGB).
 */
public final class ColorSpace {

    private ColorSpace() {
    }

    public static double toLinear(double srgb) {
        if (srgb <= 0.04045) {
            return srgb / 12.92;
        }
        return Math.pow((srgb + 0.055) / 1.055, 2.4);
    }

    public static double toSrgb(double linear) {
        if (linear <= 0.0031308) {
            return linear * 12.92;
        }
        return 1.055 * Math.pow(linear, 1.0 / 2.4) - 0.055;
    }
}