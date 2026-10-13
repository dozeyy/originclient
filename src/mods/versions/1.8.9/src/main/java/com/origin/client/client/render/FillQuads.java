package com.origin.client.client.render;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.opengl.GL11;

/**
 * The quad surface ThickLine draws into when the CALLER owns the depth state
 * (hitbox edges, drawn where vanilla draws its hitbox wireframe).
 *
 * 1.16.5 built this as a RenderType (NO_TEXTURE + TRANSLUCENT + NO_CULL) for
 * the entity buffer. 1.8.9 has no RenderTypes, so it is the same state applied
 * around a private Tessellator: begin() hands out the buffer
 * (POSITION_COLOR quads), draw() draws it NOW under the current GL matrix,
 * leaving the depth test exactly as the caller has it (unlike
 * OriginRenderTypes, which forces its own depth rules).
 */
public final class FillQuads {
	private static final Tessellator TESS = new Tessellator(1 << 15);
	private static boolean open = false;

	private FillQuads() {
	}

	public static WorldRenderer begin() {
		WorldRenderer wr = TESS.getWorldRenderer();
		if (!open) {
			wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
			open = true;
		}
		return wr;
	}

	public static void draw() {
		if (!open) {
			return;
		}
		open = false;
		boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
		try {
			GlStateManager.disableTexture2D();
			GlStateManager.disableLighting();
			GlStateManager.enableBlend();
			GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
			GlStateManager.disableCull();
			TESS.draw();
		} catch (Throwable t) {
			try {
				TESS.getWorldRenderer().reset();
			} catch (Throwable ignored) {
				// nothing left to clean
			}
		} finally {
			if (cull) {
				GlStateManager.enableCull();
			}
			GlStateManager.disableBlend();
			GlStateManager.enableTexture2D();
			com.origin.client.client.gui.Gfx.resetColor();
		}
	}
}
