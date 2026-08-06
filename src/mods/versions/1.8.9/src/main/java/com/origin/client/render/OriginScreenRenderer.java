package com.origin.client.render;

import com.origin.client.gui.OriginUi;
import com.origin.client.theme.OriginTheme;
import com.origin.client.util.Gl;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ResourceLocation;

/**
 * The Origin out-of-world scene: charcoal background, four slowly rotating
 * rings, film grain, vignette, corner brackets, drifting dust particles,
 * orbiting bodies, the ORIGIN wordmark (with per-letter reveal), progress
 * bars, caption and cursor spotlight. All constants are verbatim from the
 * modern OriginScreenRenderer — this is the file that makes 1.8.9 look
 * exactly like 1.21.
 *
 * Fail-soft: any Throwable latches {@link #broken}; callers must only
 * suppress vanilla drawing while {@link #isActive()} — so a failure here
 * degrades to vanilla for the session, never to a black screen.
 */
public final class OriginScreenRenderer {

    private static final ResourceLocation[] RINGS = {
        new ResourceLocation("originclient", "textures/ui/ring-0.png"),
        new ResourceLocation("originclient", "textures/ui/ring-1.png"),
        new ResourceLocation("originclient", "textures/ui/ring-2.png"),
        new ResourceLocation("originclient", "textures/ui/ring-3.png"),
    };
    private static final ResourceLocation GRAIN    = new ResourceLocation("originclient", "textures/ui/grain.png");
    private static final ResourceLocation VIGNETTE = new ResourceLocation("originclient", "textures/ui/vignette.png");
    private static final ResourceLocation WORDMARK = new ResourceLocation("originclient", "textures/ui/wordmark.png");

    private static final int RING_TEX = 768;

    // rings.json: widthFrac, opacity, angle0, periodSeconds, reverse
    private static final double[][] RING_PARAMS = {
        {0.59, 0.42,   0,  40, 0},
        {0.81, 0.34,  45,  65, 1},
        {1.02, 0.26, 100,  90, 0},
        {1.27, 0.20,  15, 120, 1},
    };

    // Orbiting bodies: ringIdx, periodSeconds, phase, coreFrac, alpha
    private static final double[][] BODIES = {
        {0, 26.0, 0.0, 0.010, 0.85},
        {2, 40.0, 0.5, 0.008, 0.55},
    };

    private static final int PARTICLE_COUNT = 28;

    // wordmark.json
    private static final int WM_W = 1761, WM_H = 535;
    private static final int WM_INK_X = 150, WM_INK_Y = 150, WM_INK_W = 1461, WM_INK_H = 235;
    private static final int[][] WM_LETTERS = {{0, 450}, {450, 714}, {714, 924}, {924, 1138}, {1138, 1357}, {1357, 1761}};
    private static final double BREATH_MS = 3200.0;

    public static volatile boolean broken = false;

    // Cursor spotlight state (title + menus)

    private OriginScreenRenderer() {}

    public static boolean isActive() { return !broken; }

    private static void fail(Throwable t) {
        broken = true;
        System.err.println("[OriginClient] screen renderer disabled for this session: " + t);
    }

    private static Minecraft mc() { return Minecraft.getMinecraft(); }

    private static long now() { return System.currentTimeMillis(); }

    // ------------------------------------------------------------------
    // Scene layers
    // ------------------------------------------------------------------

    /** Full backdrop: BG fill, rings, grain, particles, bodies, frame. */
    public static boolean renderTitleBackground(int w, int h) {
        if (broken) return false;
        try {
            Gl.fill(0, 0, w, h, OriginTheme.BG);
            drawRings(w, h);
            drawGrain(w, h);
            drawParticles(w, h);
            drawOrbitingBodies(w, h);
            drawFrame(w, h);
            return true;
        } catch (Throwable t) {
            fail(t);
            return false;
        }
    }

    /** Backdrop without particles/bodies — used behind generic menus. */
    public static boolean renderMenuBackdrop(int w, int h) {
        if (broken) return false;
        try {
            Gl.fill(0, 0, w, h, OriginTheme.BG);
            drawRings(w, h);
            drawGrain(w, h);
            drawFrame(w, h);
            return true;
        } catch (Throwable t) {
            fail(t);
            return false;
        }
    }

    private static void drawRings(int w, int h) {
        double cx = w / 2.0, cy = h / 2.0;
        double t = now() / 1000.0;
        for (int i = 0; i < RING_PARAMS.length; i++) {
            double[] p = RING_PARAMS[i];
            double revs = t / p[3];
            double angle = (p[2] + (p[4] > 0 ? -1 : 1) * revs * 360.0) % 360.0;
            double scale = p[0] * w * 1.1 / RING_TEX;
            GlStateManager.pushMatrix();
            GlStateManager.translate((float) cx, (float) cy, 0f);
            GlStateManager.rotate((float) angle, 0f, 0f, 1f);
            GlStateManager.scale((float) scale, (float) scale, 1f);
            GlStateManager.translate(-RING_TEX / 2f, -RING_TEX / 2f, 0f);
            Gl.bindLinear(RINGS[i]);
            Gl.texQuad(0, 0, RING_TEX, RING_TEX, (float) p[1]);
            GlStateManager.popMatrix();
        }
    }

    /** Grain tiled in REAL pixels so each noise texel = one physical pixel. */
    private static void drawGrain(int w, int h) {
        ScaledResolution sr = new ScaledResolution(mc());
        int scale = sr.getScaleFactor();
        int pw = w * scale, ph = h * scale;
        GlStateManager.pushMatrix();
        GlStateManager.scale(1f / scale, 1f / scale, 1f);
        Gl.bindLinear(GRAIN);
        Gl.texQuadUv(0, 0, pw, ph, 0, 0, pw / 256.0, ph / 256.0, 1f, 1f, 1f, 0.028f);
        GlStateManager.popMatrix();
    }

    private static void drawFrame(int w, int h) {
        Gl.bindLinear(VIGNETTE);
        Gl.texQuad(0, 0, w, h, 1f);
        drawCornerBrackets(w, h);
    }

    private static void drawCornerBrackets(int w, int h) {
        int inset = Math.max(10, (int) Math.round(w * 0.022));
        int len = Math.max(10, (int) Math.round(w * 0.018));
        int th = Math.max(1, (int) Math.round(w * 0.0015));
        int c = OriginTheme.STROKE_STRONG;
        // TL
        Gl.fill(inset, inset, inset + len, inset + th, c);
        Gl.fill(inset, inset, inset + th, inset + len, c);
        // TR
        Gl.fill(w - inset - len, inset, w - inset, inset + th, c);
        Gl.fill(w - inset - th, inset, w - inset, inset + len, c);
        // BL
        Gl.fill(inset, h - inset - th, inset + len, h - inset, c);
        Gl.fill(inset, h - inset - len, inset + th, h - inset, c);
        // BR
        Gl.fill(w - inset - len, h - inset - th, w - inset, h - inset, c);
        Gl.fill(w - inset - th, h - inset - len, w - inset, h - inset, c);
    }

    private static double frac(double v) { return v - Math.floor(v); }

    private static void drawParticles(int w, int h) {
        double t = now() / 1000.0;
        for (int i = 0; i < PARTICLE_COUNT; i++) {
            double h1 = frac(Math.sin(i * 12.9898) * 43758.5453);
            double h2 = frac(Math.sin(i * 78.233) * 12345.678);
            double h3 = frac(Math.sin(i * 39.425) * 98765.43);
            double speed = 0.004 + 0.010 * h3;
            double dir = h1 > 0.5 ? 1 : -1;
            double x = frac(h1 + t * speed * 0.15 * dir) * w;
            double y = frac(h2 - t * speed * 0.10) * h;
            double twinkle = 0.5 - 0.5 * Math.cos(t * (0.4 + 0.6 * h3) + h1 * 6.2832);
            int a = (int) Math.round((0.04 + 0.09 * h3) * twinkle * 255);
            if (a <= 1) continue;
            int size = h3 > 0.7 ? 2 : 1;
            Gl.fill(x, y, x + size, y + size, (a << 24) | 0xFFFFFF);
        }
    }

    private static void drawOrbitingBodies(int w, int h) {
        double t = now() / 1000.0;
        double cx = w / 2.0, cy = h / 2.0;
        for (double[] body : BODIES) {
            double[] ring = RING_PARAMS[(int) body[0]];
            double a = ring[0] * w * 0.99 / 2.0;
            double b = a * 0.37;
            double revs = t / ring[3];
            double ringAngle = Math.toRadians((ring[2] + (ring[4] > 0 ? -1 : 1) * revs * 360.0) % 360.0);
            double phi = (now() / (body[1] * 1000.0) + body[2]) * Math.PI * 2.0;
            double lx = a * Math.cos(phi), ly = b * Math.sin(phi);
            double x = cx + lx * Math.cos(ringAngle) - ly * Math.sin(ringAngle);
            double y = cy + lx * Math.sin(ringAngle) + ly * Math.cos(ringAngle);
            double core = w * body[3];
            OriginUi.glow(x, y, core * 3.2, body[4] * 0.28);
            OriginUi.glow(x, y, core, body[4]);
        }
    }

    // ------------------------------------------------------------------
    // Wordmark
    // ------------------------------------------------------------------

    /** Ink height that fits: min(desired, maxWidthFrac*w scaled back). */
    public static double fitInkHeight(double desired, int w, double maxWidthFrac) {
        double displayW = WM_INK_W * (desired / WM_INK_H);
        if (displayW > w * maxWidthFrac) return w * maxWidthFrac * WM_INK_H / WM_INK_W;
        return desired;
    }

    public static double wordmarkDisplayWidth(double inkH) { return WM_INK_W * (inkH / WM_INK_H); }

    /**
     * Crisp wordmark centered at (cx, cy) with a breathing glow pass — the
     * title-screen treatment.
     */
    public static void drawWordmarkBreathing(double cx, double cy, double inkH) {
        double pulse = 0.5 - 0.5 * Math.cos(now() / BREATH_MS * Math.PI * 2.0);
        drawWordmark(cx, cy, inkH * 1.06, (float) (0.05 + 0.10 * pulse));
        drawWordmark(cx, cy, inkH, 1f);
    }

    /** Blit the whole wordmark so its ink box centers on (cx, cy). */
    public static void drawWordmark(double cx, double cy, double inkH, float alpha) {
        double s = inkH / WM_INK_H;
        double x = cx - (WM_INK_X + WM_INK_W / 2.0) * s;
        double y = cy - (WM_INK_Y + WM_INK_H / 2.0) * s;
        Gl.bindLinear(WORDMARK);
        Gl.texQuad(x, y, WM_W * s, WM_H * s, alpha);
    }

    /**
     * Per-letter staggered reveal (rise + fade). elapsedMs counts from scene
     * open; letters finish tiling back seamlessly.
     */
    public static void drawWordmarkReveal(double cx, double cy, double inkH, long elapsedMs) {
        double s = inkH / WM_INK_H;
        double x0 = cx - (WM_INK_X + WM_INK_W / 2.0) * s;
        double y0 = cy - (WM_INK_Y + WM_INK_H / 2.0) * s;
        double rise = WM_INK_H * 0.10 * s;
        Gl.bindLinear(WORDMARK);
        for (int i = 0; i < WM_LETTERS.length; i++) {
            double lt = OriginTheme.clamp01((elapsedMs - i * 55) / 300.0);
            if (lt <= 0) continue;
            double eased = OriginTheme.easeOut(lt);
            double yoff = (1.0 - eased) * rise;
            double u0 = WM_LETTERS[i][0] / (double) WM_W;
            double u1 = WM_LETTERS[i][1] / (double) WM_W;
            double lx = x0 + WM_LETTERS[i][0] * s;
            double lw = (WM_LETTERS[i][1] - WM_LETTERS[i][0]) * s;
            Gl.texQuadUv(lx, y0 + yoff, lw, WM_H * s, u0, 0, u1, 1, 1f, 1f, 1f, (float) eased);
        }
    }

    // ------------------------------------------------------------------
    // Bars + caption
    // ------------------------------------------------------------------

    /** Determinate progress bar (track + glow + fill). */
    public static void drawBar(double x, double y, double w, double h, double progress) {
        Gl.fill(x, y, x + w, y + h, 0x29FFFFFF);
        double fw = w * OriginTheme.clamp01(progress);
        if (fw > 0) {
            Gl.fill(x - 1, y - 1, x + fw + 1, y + h + 1, OriginTheme.ACCENT_GLOW);
            Gl.fill(x, y, x + fw, y + h, OriginTheme.ACCENT);
        }
    }

    /** Indeterminate eased ping-pong segment bar. */
    public static void drawIndeterminateBar(double x, double y, double w, double h) {
        Gl.fill(x, y, x + w, y + h, 0x29FFFFFF);
        double seg = Math.max(8, w * 0.32);
        double eased = 0.5 - 0.5 * Math.cos(Math.PI * 2.0 * (now() % 1800) / 1800.0);
        double sx = x + (w - seg) * eased;
        Gl.fill(sx - 1, y - 1, sx + seg + 1, y + h + 1, OriginTheme.ACCENT_GLOW);
        Gl.fill(sx, y, sx + seg, y + h, OriginTheme.ACCENT);
    }

    /** "LOADING" + cycling dots, letter-tracked 2px, dots slot-reserved. */
    public static void drawCaption(double cx, double y, long elapsedMs) {
        Minecraft mc = mc();
        String word = "LOADING";
        int dots = (int) ((elapsedMs / 400) % 4);
        int track = 2;
        int wordW = 0;
        for (int i = 0; i < word.length(); i++)
            wordW += mc.fontRendererObj.getCharWidth(word.charAt(i)) + (i > 0 ? track : 0);
        int dotSlot = mc.fontRendererObj.getCharWidth('.') + track;
        int total = wordW + dotSlot * 3;
        double x = cx - total / 2.0;
        for (int i = 0; i < word.length(); i++) {
            String ch = String.valueOf(word.charAt(i));
            mc.fontRendererObj.drawString(ch, (float) x, (float) y, OriginTheme.MUTED, false);
            x += mc.fontRendererObj.getCharWidth(word.charAt(i)) + track;
        }
        for (int i = 0; i < dots; i++) {
            mc.fontRendererObj.drawString(".", (float) x, (float) y, OriginTheme.MUTED, false);
            x += dotSlot;
        }
    }

    /** Retired (Will, 2026-08-02): the mouse-follow spotlight on the title
     *  screen. It never worked right on Minecraft's pixel grid -- the blur IS
     *  the effect, and blur doesn't exist at this resolution. Kept as an empty
     *  method rather than deleted: both call sites in OriginScreens.java stay
     *  wired for free. */
    public static void renderCursorGlow(int w, int mouseX, int mouseY, boolean hoveringWidget) {
    }
}
