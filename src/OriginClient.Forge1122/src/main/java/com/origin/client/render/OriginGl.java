package com.origin.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.ResourceLocation;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;

/**
 * Fixed-function-GL primitive layer for 1.12.2 — the era equivalent of the
 * Fabric build's GuiGraphics helpers. Everything Origin draws on the classic
 * versions goes through here: filled rects, tinted textured quads (whole and
 * sub-region), text, and matrix transforms via GlStateManager.
 */
public final class OriginGl {
    private OriginGl() {
    }

    public static Minecraft mc() {
        return Minecraft.getMinecraft();
    }

    // ---- Fills ----

    public static void fill(int x0, int y0, int x1, int y1, int argb) {
        Gui.drawRect(x0, y0, x1, y1, argb);
    }

    // ---- Textures ----

    public static ResourceLocation loadTexture(String name, String classpathPng) {
        try (InputStream in = OriginGl.class.getResourceAsStream(classpathPng)) {
            if (in == null) {
                return null;
            }
            BufferedImage img = ImageIO.read(in);
            if (img == null) {
                return null;
            }
            ResourceLocation id = new ResourceLocation("originclient", name);
            mc().getTextureManager().loadTexture(id, new DynamicTexture(img));
            return id;
        } catch (Throwable t) {
            return null;
        }
    }

    public static void bind(ResourceLocation id) {
        mc().getTextureManager().bindTexture(id);
    }

    public static void blit(ResourceLocation id, double x, double y, double w, double h,
                            float r, float g, float b, float a) {
        blitUV(id, x, y, w, h, 0.0, 0.0, 1.0, 1.0, r, g, b, a);
    }

    public static void blitUV(ResourceLocation id, double x, double y, double w, double h,
                              double u0, double v0, double u1, double v1,
                              float r, float g, float b, float a) {
        bind(id);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.enableTexture2D();
        GlStateManager.color(r, g, b, a);
        Tessellator t = Tessellator.getInstance();
        BufferBuilder wr = t.getBuffer();
        wr.begin(7, DefaultVertexFormats.POSITION_TEX);
        wr.pos(x, y + h, 0).tex(u0, v1).endVertex();
        wr.pos(x + w, y + h, 0).tex(u1, v1).endVertex();
        wr.pos(x + w, y, 0).tex(u1, v0).endVertex();
        wr.pos(x, y, 0).tex(u0, v0).endVertex();
        t.draw();
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    // ---- Matrix ----

    public static void push() {
        GlStateManager.pushMatrix();
    }

    public static void pop() {
        GlStateManager.popMatrix();
    }

    public static void translate(double x, double y) {
        GlStateManager.translate(x, y, 0);
    }

    public static void scale(double sx, double sy) {
        GlStateManager.scale(sx, sy, 1.0);
    }

    public static void rotate(float degrees) {
        GlStateManager.rotate(degrees, 0f, 0f, 1f);
    }

    public static void enableBlend() {
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
    }

    public static void color(float r, float g, float b, float a) {
        GlStateManager.color(r, g, b, a);
    }

    // ---- Text ----

    public static void text(String s, int x, int y, int color, boolean shadow) {
        if (shadow) {
            mc().fontRenderer.drawStringWithShadow(s, x, y, color);
        } else {
            mc().fontRenderer.drawString(s, x, y, color);
        }
    }

    public static int textWidth(String s) {
        return mc().fontRenderer.getStringWidth(s);
    }

    public static int fontHeight() {
        return mc().fontRenderer.FONT_HEIGHT;
    }

    // ---- Scaled screen size ----

    public static int scaledWidth() {
        return new net.minecraft.client.gui.ScaledResolution(mc()).getScaledWidth();
    }

    public static int scaledHeight() {
        return new net.minecraft.client.gui.ScaledResolution(mc()).getScaledHeight();
    }
}
