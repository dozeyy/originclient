package com.origin.client.client.hooks;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.opengl.GL11;

/**
 * 1.8.9-only helper: a camera-facing text label in the world, drawn exactly the
 * way vanilla's Render.renderLivingLabel draws a name tag (billboard from
 * RenderManager.playerViewY/X, lighting off, a 25%-black backdrop, a faint
 * see-through pass with the depth test off, then the solid text with it on).
 *
 * Shared by the world area's labels -- the restyled name tags (NametagLabelMixin),
 * the Hypixel Level Head line and the TNT Countdown -- so the three read as one
 * thing and match vanilla's own tags. 1.16.5 got this from Font.drawInBatch with
 * SEE_THROUGH / NORMAL display modes; 1.8.9 has no such modes, so the two passes
 * are spelled out with GL state here.
 *
 * Usage: {@link #begin} (pushes the GL matrix and sets the label state), any
 * number of {@link #backdrop} / {@link #text} calls in label px (x right, y
 * down, 0 = the text's top), then {@link #end} (always, in a finally).
 */
public final class WorldLabel {
	private WorldLabel() {
	}

	/** Vanilla's name-tag scale: 0.016666668 * 1.6. */
	public static final float VANILLA_SCALE = 0.02666667F;

	/**
	 * Pushes a matrix at camera-relative (x, y, z), turned to face the camera and
	 * scaled to label px (`scale` world units per px; vanilla tags use
	 * {@link #VANILLA_SCALE}). Lighting off, blend on; `fullBright` forces the
	 * lightmap to full (Origin's own labels, readable at night) -- vanilla tags
	 * keep the entity's light, so restyled name tags pass false.
	 */
	public static void begin(double x, double y, double z, float scale, boolean fullBright) {
		RenderManager rm = Minecraft.getMinecraft().getRenderManager();
		GlStateManager.pushMatrix();
		GlStateManager.translate(x, y, z);
		GL11.glNormal3f(0.0F, 1.0F, 0.0F);
		GlStateManager.rotate(-rm.playerViewY, 0.0F, 1.0F, 0.0F);
		GlStateManager.rotate(rm.playerViewX, 1.0F, 0.0F, 0.0F);
		GlStateManager.scale(-scale, -scale, scale);
		GlStateManager.disableLighting();
		if (fullBright) {
			OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240f, 240f);
		}
		GlStateManager.enableBlend();
		GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
		GlStateManager.depthMask(false);
	}

	/** A flat colour box in label px, drawn see-through (depth test off) like vanilla's tag backdrop. */
	public static void backdrop(float x0, float y0, float x1, float y1, int argb) {
		float a = ((argb >>> 24) & 0xFF) / 255f;
		if (a <= 0f) {
			return;
		}
		float r = ((argb >> 16) & 0xFF) / 255f, g = ((argb >> 8) & 0xFF) / 255f, b = (argb & 0xFF) / 255f;
		GlStateManager.disableDepth();
		GlStateManager.disableTexture2D();
		Tessellator tess = Tessellator.getInstance();
		WorldRenderer wr = tess.getWorldRenderer();
		wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
		wr.pos(x0, y0, 0.0).color(r, g, b, a).endVertex();
		wr.pos(x0, y1, 0.0).color(r, g, b, a).endVertex();
		wr.pos(x1, y1, 0.0).color(r, g, b, a).endVertex();
		wr.pos(x1, y0, 0.0).color(r, g, b, a).endVertex();
		tess.draw();
		GlStateManager.enableTexture2D();
		GlStateManager.enableDepth();
	}

	/**
	 * Text at (x, 0) in label px, in vanilla's two passes: a faint see-through
	 * copy (`ghostArgb`, depth test off -- shows through walls), then, if `solid`,
	 * the real colour depth-tested. Returns the text width.
	 */
	public static int text(String s, float x, float y, int ghostArgb, int argb, boolean shadow, boolean solid) {
		FontRenderer font = Minecraft.getMinecraft().fontRendererObj;
		GlStateManager.disableDepth();
		font.drawString(s, x, y, ghostArgb, false);
		GlStateManager.enableDepth();
		if (solid) {
			font.drawString(s, x, y, argb, shadow);
		}
		return font.getStringWidth(s);
	}

	/** Pops the matrix and puts back the state the entity pass expects. */
	public static void end() {
		GlStateManager.depthMask(true);
		GlStateManager.enableDepth();
		GlStateManager.enableLighting();
		GlStateManager.disableBlend();
		GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
		GlStateManager.popMatrix();
	}
}
