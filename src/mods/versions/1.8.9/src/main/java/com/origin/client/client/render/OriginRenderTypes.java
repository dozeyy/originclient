package com.origin.client.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.opengl.GL11;

/**
 * Origin's own world-overlay surfaces (quads + lines) and the private buffers
 * they are drawn through. Quads: the block outline/overlay, waypoint beams and
 * chunk-border posts -- anything that paints translucent, depth-tested quads
 * into the world. Lines: chunk-border rings/lattice, waypoint highlight box.
 *
 * 1.8.9: there are no RenderTypes; everything is immediate fixed-function GL.
 * This class keeps 1.16.5's call shape -- begin() / beginLines() hand out a
 * buffer, draw() draws everything emitted, NOW, under the current GL matrix and
 * depth buffer -- with two PRIVATE Tessellators (public Tessellator(int)
 * constructor), so quads and lines can be emitted interleaved
 * (ChunkBorderRenderer does) without one flushing the other, and nothing of
 * ours ever rides vanilla's shared Tessellator.
 *
 * Emit {@code wr.pos(x, y, z).color(r, g, b, a).endVertex()} (POSITION_COLOR).
 * Call draw() once at the end of each RenderWorldLastEvent pass; coordinates
 * are camera-relative (subtract RenderManager.viewerPosX/Y/Z), as on 1.16.5.
 *
 * The state, spelled out (1.16.5's shard list):
 *   quads: texturing off, translucent blend, NO cull, LEQUAL depth TEST, no
 *     depth WRITE (overlapping translucent faces aren't punched out), and
 *     vanilla's outline nudge (VIEW_OFFSET_Z_LAYERING: model-view scaled a
 *     hair toward the camera) so faces ON a block surface win the depth tie;
 *   lines: the same plus depth write, alpha test off, and vanilla's line
 *     width rule max(2.5, windowWidth / 1920 * 2.5).
 */
public final class OriginRenderTypes {
	private static final Tessellator QUADS = new Tessellator(1 << 16);
	private static final Tessellator LINES = new Tessellator(1 << 16);
	private static boolean quadsOpen = false, linesOpen = false;

	// RenderType.VIEW_OFFSET_Z_LAYERING's scale (1.16.5: 0.99975586F).
	private static final float Z_LAYER = 0.99975586F;

	private OriginRenderTypes() {
	}

	/** The buffer to emit translucent world quads into (POSITION_COLOR). */
	public static WorldRenderer begin() {
		WorldRenderer wr = QUADS.getWorldRenderer();
		if (!quadsOpen) {
			wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
			quadsOpen = true;
		}
		return wr;
	}

	/** The buffer to emit GL_LINES vertex pairs into (POSITION_COLOR). */
	public static WorldRenderer beginLines() {
		WorldRenderer wr = LINES.getWorldRenderer();
		if (!linesOpen) {
			wr.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
			linesOpen = true;
		}
		return wr;
	}

	/** Draws everything emitted since begin()/beginLines(), NOW. Never throws. */
	public static void draw() {
		if (!quadsOpen && !linesOpen) {
			return;
		}
		boolean depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
		boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
		boolean alpha = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
		GlStateManager.pushMatrix();
		try {
			GlStateManager.disableTexture2D();
			GlStateManager.disableLighting();
			GlStateManager.enableBlend();
			GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
			GlStateManager.disableCull();
			GlStateManager.enableDepth();
			GlStateManager.depthFunc(GL11.GL_LEQUAL);
			GlStateManager.scale(Z_LAYER, Z_LAYER, Z_LAYER);
			if (quadsOpen) {
				quadsOpen = false;
				GlStateManager.depthMask(false);
				QUADS.draw();
			}
			if (linesOpen) {
				linesOpen = false;
				GlStateManager.depthMask(true);
				GlStateManager.disableAlpha();
				int w = Math.max(1, Minecraft.getMinecraft().displayWidth);
				GL11.glLineWidth(Math.max(2.5F, w / 1920.0F * 2.5F));
				LINES.draw();
				GL11.glLineWidth(1.0F);
			}
		} catch (Throwable t) {
			// a world overlay must never take the frame down; drop what was queued
			quadsOpen = false;
			linesOpen = false;
			try {
				QUADS.getWorldRenderer().reset();
				LINES.getWorldRenderer().reset();
			} catch (Throwable ignored) {
				// nothing left to clean
			}
		} finally {
			GlStateManager.popMatrix();
			GlStateManager.depthMask(true);
			if (!depthTest) {
				GlStateManager.disableDepth();
			}
			if (cull) {
				GlStateManager.enableCull();
			}
			if (alpha) {
				GlStateManager.enableAlpha();
			}
			GlStateManager.disableBlend();
			GlStateManager.enableTexture2D();
			com.origin.client.client.gui.Gfx.resetColor();
		}
	}
}
