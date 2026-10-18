package com.origin.client.client.cosmetics;

import com.origin.client.OriginClient;
import com.origin.client.client.gui.Gfx;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

import java.awt.image.BufferedImage;
import java.io.InputStream;

/**
 * The Origin+ badge: the Origin mark (three tilted stroke-only rings, #E0E0E0)
 * drawn small next to a member's name. Looks only. Three places draw it:
 *  - the nametag above the head ({@link #nametag}, called from NametagLabelMixin
 *    inside the label's own billboard matrix, in the same two passes as the name);
 *  - the Tab player list ({@link #gui});
 *  - the friends list (gui/FriendsScreen, through OriginDeck.logo).
 *
 * The art is textures/ui/origin_logo.png (256 px, white on transparent). A
 * 256 px picture shrunk to ~10 px by the GPU shimmers and loses the thin
 * strokes, so it is box-filtered once into 32 / 64 / 128 px copies and the
 * closest one above the on-screen size is used.
 *
 * Everything fails soft: any problem (missing texture, GL state, odd entity)
 * means no badge, never an error in game. Render thread only.
 *
 * 1.8.9: no NativeImage or RenderType -- the copies are BufferedImages
 * registered through Gfx.registerTexture (linear), and the nametag badge is an
 * immediate textured quad drawn like WorldLabel.text's two passes.
 */
public final class PlusBadge {
	private PlusBadge() {
	}

	/** Badge edge on a nametag, in nametag units (the font is 8 high, so it sits level with the letters). */
	public static final int NAME_SIZE = 8;
	/** Space between the badge's right edge and the first letter of the name, nametag units. */
	private static final int NAME_GAP = 2;
	/** Silver-white, the brand's one accent. */
	private static final int RGB = 0xE0E0E0;

	private static final int[] LEVELS = {32, 64, 128};
	private static final String SOURCE = "/assets/originclient/textures/ui/origin_logo.png";

	// null = not built yet; failed = tried and failed (never retried).
	private static ResourceLocation[] textures = null;
	private static boolean failed = false;

	// ------------------------------------------------------------ nametag

	/** Does this entity get a badge on its nametag? (a player who is an Origin+ member) */
	public static boolean wantsNametag(Entity entity) {
		try {
			return !failed && entity instanceof EntityPlayer
					&& CosmeticsService.isPlus(((EntityPlayer) entity).getUniqueID());
		} catch (Throwable t) {
			return false;
		}
	}

	/**
	 * Blank text to put IN FRONT of a member's name so the label's centring
	 * and background box grow to hold the badge (the badge is then drawn over
	 * the blank by {@link #nametag}). Null = can't make room, so no badge.
	 */
	public static String namePad(FontRenderer font) {
		try {
			int space = font.getStringWidth(" ");
			if (space <= 0) {
				return null;
			}
			int n = (NAME_SIZE + NAME_GAP + 1 + space - 1) / space;
			// 1.8.9: Java 8 has no String.repeat
			StringBuilder b = new StringBuilder();
			for (int i = Math.max(1, Math.min(n, 8)); i > 0; i--) {
				b.append(' ');
			}
			return b.toString();
		} catch (Throwable t) {
			return null;
		}
	}

	/**
	 * Draws the badge on a nametag, inside WorldLabel.begin/end (label px, y down).
	 *
	 * @param x          where the PADDED text starts (label px)
	 * @param y          the text's top
	 * @param padW       width of the blank in front of the name
	 * @param ghostAlpha 0..255, the faint see-through pass's alpha (as the name's)
	 * @param solidAlpha 0..255, the solid pass's alpha; 0 = no solid pass
	 * @param dist       the label's distance from the camera, blocks (picks the copy)
	 * @param scale      world units per label px (WorldLabel's scale times the Nametags Scale)
	 */
	public static void nametag(float x, float y, float padW, int ghostAlpha, int solidAlpha, double dist, float scale) {
		try {
			ResourceLocation tex = textureFor(nametagPixels(dist, scale));
			if (tex == null) {
				return;
			}
			float x0 = x + padW - NAME_GAP - NAME_SIZE;
			Gfx.bind(tex);
			GlStateManager.enableTexture2D();
			// 1.8.9: vanilla's label passes -- faint with depth test off, then solid with it on
			if (ghostAlpha >= 4) {
				GlStateManager.disableDepth();
				quad(x0, y, ghostAlpha);
				GlStateManager.enableDepth();
			}
			if (solidAlpha >= 4) {
				quad(x0, y, solidAlpha);
			}
		} catch (Throwable t) {
			// no badge
		} finally {
			GlStateManager.color(1f, 1f, 1f, 1f);
		}
	}

	private static void quad(float x0, float y0, int alpha) {
		GlStateManager.color(((RGB >> 16) & 0xFF) / 255f, ((RGB >> 8) & 0xFF) / 255f, (RGB & 0xFF) / 255f,
				Math.min(255, alpha) / 255f);
		float x1 = x0 + NAME_SIZE, y1 = y0 + NAME_SIZE;
		Tessellator tess = Tessellator.getInstance();
		WorldRenderer wr = tess.getWorldRenderer();
		wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
		// the same corner order as the label's backdrop
		wr.pos(x0, y0, 0.0).tex(0.0, 0.0).endVertex();
		wr.pos(x0, y1, 0.0).tex(0.0, 1.0).endVertex();
		wr.pos(x1, y1, 0.0).tex(1.0, 1.0).endVertex();
		wr.pos(x1, y0, 0.0).tex(1.0, 0.0).endVertex();
		tess.draw();
	}

	/**
	 * Roughly how many screen pixels the nametag badge covers.
	 * 1.8.9: from the label's camera distance (1.21.1 reads it off the text matrix).
	 */
	private static int nametagPixels(double dist, float scale) {
		if (dist < 0.5) {
			dist = 0.5;
		}
		// size in blocks / (view height in blocks at that distance, ~70 degree fov) * window height
		double blocks = NAME_SIZE * scale;
		double px = blocks / dist * Gfx.windowHeight() / 1.4;
		return (int) Math.max(1, Math.min(4096, Math.round(px)));
	}

	// ------------------------------------------------------------ GUI

	/** Draws the badge at (x, y), `size` GUI px square (Tab list). Fails soft. */
	public static void gui(Gfx g, int x, int y, int size) {
		try {
			int phys = Math.max(1, size * Gfx.guiScale());
			ResourceLocation tex = textureFor(phys);
			if (tex == null) {
				return;
			}
			Gfx.defaultBlend();
			GlStateManager.color(((RGB >> 16) & 0xFF) / 255f, ((RGB >> 8) & 0xFF) / 255f, (RGB & 0xFF) / 255f, 1f);
			g.blit(tex, x, y, size, size, 0f, 0f, 64, 64, 64, 64);
		} catch (Throwable t) {
			// no badge
		} finally {
			Gfx.resetColor();
		}
	}

	// ------------------------------------------------------------ textures

	/** The copy to use for a badge `px` screen pixels wide: the smallest one that is not smaller. */
	private static ResourceLocation textureFor(int px) {
		ensure();
		ResourceLocation[] t = textures;
		if (t == null) {
			return null;
		}
		for (int i = 0; i < LEVELS.length; i++) {
			if (LEVELS[i] >= px || i == LEVELS.length - 1) {
				return t[i];
			}
		}
		return null;
	}

	private static void ensure() {
		if (textures != null || failed) {
			return;
		}
		// 1.8.9: the render thread is the client thread (no RenderSystem.isOnRenderThread)
		if (!Minecraft.getMinecraft().isCallingFromMinecraftThread()) {
			return; // try again on the render thread; nothing is marked failed
		}
		try {
			InputStream in = PlusBadge.class.getResourceAsStream(SOURCE);
			if (in == null) {
				throw new IllegalStateException("origin_logo.png missing");
			}
			BufferedImage src = TextureUtil.readBufferedImage(in);   // closes the stream
			ResourceLocation[] made = new ResourceLocation[LEVELS.length];
			for (int i = 0; i < LEVELS.length; i++) {
				ResourceLocation id = new ResourceLocation("originclient", "plus_badge_" + LEVELS[i]);
				if (Gfx.registerTexture(id, boxFilter(src, LEVELS[i]), true) == null) {
					throw new IllegalStateException("couldn't register " + id);
				}
				made[i] = id;
			}
			textures = made;
		} catch (Throwable t) {
			failed = true;
			OriginClient.LOGGER.warn("Origin+ badge: couldn't build the badge textures; no badge will draw", t);
		}
	}

	/**
	 * `src` shrunk to size x size by averaging whole blocks of pixels (alpha-
	 * weighted, so the edges don't darken). Every GUI/nametag copy is drawn
	 * stretched to the whole UV square, so each level must be square and the
	 * source's size must divide evenly (256 -> 32 / 64 / 128).
	 *
	 * 1.8.9: BufferedImage.getRGB is ARGB (NativeImage's getPixelRGBA is ABGR).
	 */
	private static BufferedImage boxFilter(BufferedImage src, int size) {
		int f = Math.max(1, src.getWidth() / size);
		BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				long sa = 0, sr = 0, sg = 0, sb = 0;
				for (int dy = 0; dy < f; dy++) {
					for (int dx = 0; dx < f; dx++) {
						int p = src.getRGB(x * f + dx, y * f + dy);
						int a = (p >>> 24) & 0xFF;
						sa += a;
						sr += (long) ((p >> 16) & 0xFF) * a;
						sg += (long) ((p >> 8) & 0xFF) * a;
						sb += (long) (p & 0xFF) * a;
					}
				}
				int n = f * f;
				int a = (int) (sa / n);
				int r = sa > 0 ? (int) (sr / sa) : 255;
				int g = sa > 0 ? (int) (sg / sa) : 255;
				int b = sa > 0 ? (int) (sb / sa) : 255;
				out.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
			}
		}
		return out;
	}
}
