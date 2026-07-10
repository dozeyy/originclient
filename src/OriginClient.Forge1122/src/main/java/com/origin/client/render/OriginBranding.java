package com.origin.client.render;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.util.ResourceLocation;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.origin.client.theme.OriginTheme;

/**
 * Branded Origin backdrop for the classic (Forge) versions: the exact same
 * near-black + orbital-rings + grain + vignette + "ORIGIN" wordmark look as the
 * Fabric build's OriginScreenRenderer, reimplemented on fixed-function GL and
 * driven by the identical baked texture assets (rings/wordmark/grain/vignette).
 *
 * Fail-soft: any load or draw failure flips {@link #broken} and the caller
 * falls back to the vanilla screen for the session — never a crash.
 */
public final class OriginBranding {
    private static final Gson GSON = new Gson();
    private static final int TEX = 768;
    private static final double BREATH_MS = 3200.0;

    private static volatile boolean broken = false;
    private static volatile boolean loaded = false;
    private static boolean ringsFailed = false;

    private static final List<Ring> rings = new ArrayList<Ring>();
    private static ResourceLocation grainId;
    private static ResourceLocation vignetteId;
    private static ResourceLocation wordmarkId;
    private static int wmTexW, wmTexH, wmInkX, wmInkY, wmInkW, wmInkH;

    private OriginBranding() {
    }

    private static final class Ring {
        final ResourceLocation texture;
        final double widthFrac;
        final float opacity;
        final double angle0;
        final double periodSeconds;
        final boolean reverse;

        Ring(ResourceLocation texture, double widthFrac, float opacity,
             double angle0, double periodSeconds, boolean reverse) {
            this.texture = texture;
            this.widthFrac = widthFrac;
            this.opacity = opacity;
            this.angle0 = angle0;
            this.periodSeconds = periodSeconds;
            this.reverse = reverse;
        }
    }

    public static boolean isActive() {
        return !broken;
    }

    /** Draw the full menu backdrop (bg + rings + grain + vignette + brackets). Returns false if it fell back. */
    public static boolean renderTitleBackground(int w, int h) {
        if (broken) {
            return false;
        }
        try {
            ensureLoaded();
            OriginGl.fill(0, 0, w, h, OriginTheme.BG);
            if (!ringsFailed) {
                drawRings(w, h);
                drawGrain(w, h);
            }
            drawVignette(w, h);
            drawCornerBrackets(w, h);
            return true;
        } catch (Throwable t) {
            broken = true;
            return false;
        }
    }

    /** Draw the centered "ORIGIN" wordmark with its breathing glow. Returns false if it fell back. */
    public static boolean renderTitleWordmark(int w, int h) {
        if (broken) {
            return false;
        }
        try {
            ensureLoaded();
            int singleplayerTop = h / 4 + 48;         // vanilla first-button Y
            double centerY = singleplayerTop / 2.0;   // midpoint above the buttons
            double inkH = fitInkHeight(h * 0.13, w, 0.82);
            double pulse = 0.5 - 0.5 * Math.cos(System.currentTimeMillis() / BREATH_MS * 2.0 * Math.PI);
            drawWordmark(w / 2.0, centerY, inkH, 1.06, (float) (0.05 + 0.10 * pulse)); // glow pass
            drawWordmark(w / 2.0, centerY, inkH, 1.0, 1.0f);                            // crisp pass
            return true;
        } catch (Throwable t) {
            broken = true;
            return false;
        }
    }

    private static double fitInkHeight(double target, int w, double maxWidthFrac) {
        if (wordmarkId == null || wmInkW <= 0) {
            return target;
        }
        double dispW = wmInkW * (target / wmInkH);
        double maxW = w * maxWidthFrac;
        if (dispW > maxW) {
            return wmInkH * (maxW / wmInkW);
        }
        return target;
    }

    private static void drawRings(int w, int h) {
        double cx = w / 2.0, cy = h / 2.0;
        long now = System.currentTimeMillis();
        OriginGl.enableBlend();
        for (Ring ring : rings) {
            double revs = (now / 1000.0) / ring.periodSeconds;
            double angle = (ring.angle0 + (ring.reverse ? -revs : revs) * 360.0) % 360.0;
            double scale = ring.widthFrac * w * 1.1 / TEX;
            OriginGl.push();
            OriginGl.translate(cx, cy);
            OriginGl.rotate((float) angle);
            OriginGl.scale(scale, scale);
            OriginGl.blit(ring.texture, -TEX / 2.0, -TEX / 2.0, TEX, TEX, 1f, 1f, 1f, ring.opacity);
            OriginGl.pop();
        }
        OriginGl.color(1f, 1f, 1f, 1f);
    }

    private static void drawGrain(int w, int h) {
        if (grainId == null) {
            return;
        }
        int tile = 256;
        OriginGl.enableBlend();
        for (int y = 0; y < h; y += tile) {
            for (int x = 0; x < w; x += tile) {
                OriginGl.blit(grainId, x, y, tile, tile, 1f, 1f, 1f, 0.028f);
            }
        }
        OriginGl.color(1f, 1f, 1f, 1f);
    }

    private static void drawVignette(int w, int h) {
        if (vignetteId == null) {
            return;
        }
        OriginGl.enableBlend();
        OriginGl.blit(vignetteId, 0, 0, w, h, 1f, 1f, 1f, 1f);
    }

    private static void drawCornerBrackets(int w, int h) {
        int inset = Math.max(10, (int) Math.round(w * 0.022));
        int len = Math.max(10, (int) Math.round(w * 0.018));
        int th = Math.max(1, (int) Math.round(w * 0.0015));
        int c = OriginTheme.STROKE_STRONG;
        OriginGl.fill(inset, inset, inset + len, inset + th, c);
        OriginGl.fill(inset, inset, inset + th, inset + len, c);
        OriginGl.fill(w - inset - len, inset, w - inset, inset + th, c);
        OriginGl.fill(w - inset - th, inset, w - inset, inset + len, c);
        OriginGl.fill(inset, h - inset - th, inset + len, h - inset, c);
        OriginGl.fill(inset, h - inset - len, inset + th, h - inset, c);
        OriginGl.fill(w - inset - len, h - inset - th, w - inset, h - inset, c);
        OriginGl.fill(w - inset - th, h - inset - len, w - inset, h - inset, c);
    }

    private static void drawWordmark(double inkCenterX, double inkCenterY, double targetInkH,
                                     double scaleMul, float alpha) {
        if (wordmarkId == null || wmInkH <= 0 || alpha <= 0.001f) {
            return;
        }
        double scale = targetInkH / wmInkH * scaleMul;
        double icx = (wmInkX + wmInkW / 2.0) * scale;
        double icy = (wmInkY + wmInkH / 2.0) * scale;
        OriginGl.push();
        OriginGl.translate(inkCenterX - icx, inkCenterY - icy);
        OriginGl.scale(scale, scale);
        OriginGl.enableBlend();
        OriginGl.blit(wordmarkId, 0, 0, wmTexW, wmTexH, 1f, 1f, 1f, alpha);
        OriginGl.pop();
        OriginGl.color(1f, 1f, 1f, 1f);
    }

    // ---- Loading ----

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        ensureLoaded0();
    }

    private static synchronized void ensureLoaded0() {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            JsonObject meta = readJson("/assets/originclient/textures/ui/rings.json");
            com.google.gson.JsonArray arr = meta.getAsJsonArray("rings");
            for (int i = 0; i < arr.size(); i++) {
                JsonObject r = arr.get(i).getAsJsonObject();
                int index = r.get("index").getAsInt();
                ResourceLocation id = OriginGl.loadTexture("origin_ring_" + index,
                        "/assets/originclient/textures/ui/ring-" + index + ".png");
                if (id == null) {
                    continue;
                }
                rings.add(new Ring(id,
                        r.get("widthFrac").getAsDouble(),
                        r.get("opacity").getAsFloat(),
                        r.get("angle0").getAsDouble(),
                        r.get("periodSeconds").getAsDouble(),
                        r.get("reverse").getAsBoolean()));
            }
            grainId = OriginGl.loadTexture("origin_grain", "/assets/originclient/textures/ui/grain.png");
        } catch (Throwable t) {
            ringsFailed = true;
        }

        try {
            vignetteId = OriginGl.loadTexture("origin_vignette", "/assets/originclient/textures/ui/vignette.png");
        } catch (Throwable t) {
            vignetteId = null;
        }

        try {
            JsonObject wm = readJson("/assets/originclient/textures/ui/wordmark.json");
            wmTexW = wm.get("width").getAsInt();
            wmTexH = wm.get("height").getAsInt();
            wmInkX = wm.get("inkX").getAsInt();
            wmInkY = wm.get("inkY").getAsInt();
            wmInkW = wm.get("inkWidth").getAsInt();
            wmInkH = wm.get("inkHeight").getAsInt();
            wordmarkId = OriginGl.loadTexture("origin_wordmark", "/assets/originclient/textures/ui/wordmark.png");
        } catch (Throwable t) {
            wordmarkId = null;
        }
    }

    private static JsonObject readJson(String classpathResource) throws Exception {
        InputStream in = OriginBranding.class.getResourceAsStream(classpathResource);
        if (in == null) {
            throw new java.io.FileNotFoundException("Missing Origin asset: " + classpathResource);
        }
        try {
            byte[] bytes = readAll(in);
            return GSON.fromJson(new String(bytes, StandardCharsets.UTF_8), JsonObject.class);
        } finally {
            in.close();
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
