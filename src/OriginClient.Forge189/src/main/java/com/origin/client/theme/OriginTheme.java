package com.origin.client.theme;

// Design tokens exact-matched to the launcher/website palette (DESIGN_SYSTEM.md
// §1) — colors as 0xAARRGGBB. Shared verbatim with the Fabric build so the
// classic Forge versions read the identical look. No Minecraft imports.
public final class OriginTheme {
    private OriginTheme() {
    }

    // ---- Colors ----
    public static final int BG = 0xFF050505;
    public static final int BG_ALT = 0xFF0A0A0A;
    public static final int PANEL = 0xFF101010;
    public static final int PANEL_TRANSLUCENT = 0x8C101010;
    public static final int PANEL_ALT = 0xFF161616;
    public static final int STROKE = 0x14FFFFFF;
    public static final int STROKE_STRONG = 0x2EFFFFFF;
    public static final int TEXT = 0xFFF5F5F5;
    public static final int TEXT_DIM = 0xFF9A9A9A;
    public static final int MUTED = 0xFF616161;
    public static final int ACCENT = 0xFFFFFFFF;
    public static final int ACCENT_GLOW = 0x59FFFFFF;
    public static final int ACCENT_DIM = 0x8CFFFFFF;

    // ---- Spacing (8px grid) ----
    public static final int SPACE_1 = 8;
    public static final int SPACE_2 = 16;
    public static final int SPACE_3 = 24;
    public static final int SPACE_4 = 32;

    // ---- Corner radii ----
    public static final int RADIUS_SM = 6;
    public static final int RADIUS_MD = 10;
    public static final int RADIUS_LG = 14;

    private static final double[] EASE_OUT = {0.16, 1.0, 0.3, 1.0};

    /** cubic-bezier(0.16, 1, 0.3, 1) — the site's ease-out. */
    public static double easeOut(double t) {
        return cubicBezier(EASE_OUT[0], EASE_OUT[1], EASE_OUT[2], EASE_OUT[3], t);
    }

    public static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    public static int lerpColor(int a, int b, double t) {
        int aa = (a >>> 24) & 0xFF, ar = (a >>> 16) & 0xFF, ag = (a >>> 8) & 0xFF, ab = a & 0xFF;
        int ba = (b >>> 24) & 0xFF, br = (b >>> 16) & 0xFF, bg = (b >>> 8) & 0xFF, bb = b & 0xFF;
        int ra = (int) Math.round(lerp(aa, ba, t));
        int rr = (int) Math.round(lerp(ar, br, t));
        int rg = (int) Math.round(lerp(ag, bg, t));
        int rb = (int) Math.round(lerp(ab, bb, t));
        return (ra << 24) | (rr << 16) | (rg << 8) | rb;
    }

    public static double cubicBezier(double x1, double y1, double x2, double y2, double t) {
        double u = clamp01(t);
        for (int i = 0; i < 8; i++) {
            double x = bezierComponent(u, x1, x2);
            double dx = bezierDerivative(u, x1, x2);
            if (Math.abs(dx) < 1e-6) {
                break;
            }
            u = clamp01(u - (x - t) / dx);
        }
        return bezierComponent(u, y1, y2);
    }

    private static double bezierComponent(double u, double p1, double p2) {
        double v = 1 - u;
        return 3 * v * v * u * p1 + 3 * v * u * u * p2 + u * u * u;
    }

    private static double bezierDerivative(double u, double p1, double p2) {
        double v = 1 - u;
        return 3 * v * v * p1 + 6 * v * u * (p2 - p1) + 3 * u * u * (1 - p2);
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
