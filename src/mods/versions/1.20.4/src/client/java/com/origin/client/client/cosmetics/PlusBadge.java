package com.origin.client.client.cosmetics;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.origin.client.OriginClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import org.joml.Matrix4f;

import java.io.InputStream;

/**
 * The Origin badge: the Origin mark (three tilted stroke-only rings, #E0E0E0)
 * drawn small next to the name of anyone playing on Origin (it was Origin+
 * members only until 2026-10-18). Looks only. Three places draw it:
 *  - the nametag above the head ({@link #nametag}, called from NametagStyleMixin
 *    with vanilla's own text matrix, so sneaking / see-through / fading behave
 *    exactly like the name beside it);
 *  - the Tab player list ({@link #gui});
 *  - the friends list (gui/FriendsScreen, through OriginDeck.logo) - there it
 *    still marks Origin+ members, since every friend uses Origin.
 *
 * The art is textures/ui/origin_logo.png (256 px, white on transparent). A
 * 256 px picture shrunk to ~10 px by the GPU shimmers and loses the thin
 * strokes, so it is box-filtered once into 32 / 64 / 128 px copies and the
 * closest one above the on-screen size is used.
 *
 * Everything fails soft: any problem (missing texture, GL state, odd entity)
 * means no badge, never an error in game. Render thread only.
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
	private static final String SOURCE = com.origin.client.client.gui.OriginUi.LOGO_PATH;

	// null = not built yet; an array of nulls = tried and failed (never retried).
	private static ResourceLocation[] textures = null;
	private static boolean failed = false;

	// ------------------------------------------------------------ nametag

	/** Does this entity get a badge on its nametag? (a player using Origin - Will, 2026-10-18: everyone, not only Origin+) */
	public static boolean wantsNametag(Entity entity) {
		try {
			return !failed && entity instanceof Player p && CosmeticsService.usesOrigin(p.getUUID());
		} catch (Throwable t) {
			return false;
		}
	}

	/**
	 * True when this renderNameTag call is the below-name scoreboard line, not the
	 * name: PlayerRenderer sends that line through the same renderNameTag first, as
	 * "<score> <objective name>". Only the name line gets the badge.
	 */
	public static boolean isScoreLine(Entity entity, Component line) {
		try {
			if (!(entity instanceof Player p) || line == null) {
				return false;
			}
			Objective objective = p.getScoreboard().getDisplayObjective(DisplaySlot.BELOW_NAME);
			return objective != null && line.getString().endsWith(" " + objective.getDisplayName().getString());
		} catch (Throwable t) {
			return false;
		}
	}

	/**
	 * Blank text to put IN FRONT of a member's name so vanilla's own centring
	 * and background box grow to hold the badge (the badge is then drawn over
	 * the blank by {@link #nametag}). Null = can't make room, so no badge.
	 */
	public static String namePad(Font font) {
		try {
			int space = font.width(" ");
			if (space <= 0) {
				return null;
			}
			int n = (NAME_SIZE + NAME_GAP + 1 + space - 1) / space;
			return " ".repeat(Math.max(1, Math.min(n, 8)));
		} catch (Throwable t) {
			return null;
		}
	}

	/**
	 * Draws the badge on a nametag.
	 *
	 * @param matrix vanilla's nametag text matrix (billboarded, 0.025 scale)
	 * @param mode   the text pass being drawn (SEE_THROUGH faint pass, NORMAL solid pass)
	 * @param x      where the PADDED text starts (nametag units)
	 * @param y      the text's top
	 * @param padW   width of the blank in front of the name
	 * @param alpha  0..255, the same alpha the name uses in this pass
	 * @param light  the same packed light the name uses in this pass
	 */
	public static void nametag(Matrix4f matrix, MultiBufferSource buffer, Font.DisplayMode mode,
							   float x, float y, float padW, int alpha, int light) {
		try {
			if (alpha < 4) {
				return; // as invisible as the name beside it
			}
			ResourceLocation tex = textureFor(nametagPixels(matrix));
			if (tex == null) {
				return;
			}
			float x0 = x + padW - NAME_GAP - NAME_SIZE;
			float y0 = y;
			float x1 = x0 + NAME_SIZE;
			float y1 = y0 + NAME_SIZE;
			RenderType type = mode == Font.DisplayMode.SEE_THROUGH ? RenderType.textSeeThrough(tex) : RenderType.text(tex);
			VertexConsumer vc = buffer.getBuffer(type);
			int r = (RGB >> 16) & 0xFF, g = (RGB >> 8) & 0xFF, b = RGB & 0xFF;
			int a = Math.min(255, alpha);
			// same corner order and z as vanilla's glyph quads
			vc.vertex(matrix, x0, y0, 0f).color(r, g, b, a).uv(0f, 0f).uv2(light).endVertex();
			vc.vertex(matrix, x0, y1, 0f).color(r, g, b, a).uv(0f, 1f).uv2(light).endVertex();
			vc.vertex(matrix, x1, y1, 0f).color(r, g, b, a).uv(1f, 1f).uv2(light).endVertex();
			vc.vertex(matrix, x1, y0, 0f).color(r, g, b, a).uv(1f, 0f).uv2(light).endVertex();
		} catch (Throwable t) {
			// no badge
		}
	}

	/** Roughly how many screen pixels the nametag badge covers (from its distance to the camera). */
	private static int nametagPixels(Matrix4f m) {
		double dist = Math.sqrt((double) m.m30() * m.m30() + (double) m.m31() * m.m31() + (double) m.m32() * m.m32());
		if (dist < 0.5) {
			dist = 0.5;
		}
		// size in blocks / (view height in blocks at that distance, ~70 degree fov) * window height
		double blocks = NAME_SIZE * 0.025;
		double px = blocks / dist * Minecraft.getInstance().getWindow().getHeight() / 1.4;
		return (int) Math.max(1, Math.min(4096, Math.round(px)));
	}

	// ------------------------------------------------------------ GUI

	/** Draws the badge at (x, y), `size` GUI px square (Tab list). Fails soft. */
	public static void gui(GuiGraphics g, int x, int y, int size) {
		try {
			int phys = Math.max(1, Math.round(size * (float) Minecraft.getInstance().getWindow().getGuiScale()));
			ResourceLocation tex = textureFor(phys);
			if (tex == null) {
				return;
			}
			RenderSystem.enableBlend();
			RenderSystem.defaultBlendFunc();
			RenderSystem.setShaderColor(((RGB >> 16) & 0xFF) / 255f, ((RGB >> 8) & 0xFF) / 255f, (RGB & 0xFF) / 255f, 1f);
			g.blit(tex, x, y, size, size, 0f, 0f, 64, 64, 64, 64);
			RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
		} catch (Throwable t) {
			RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
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
		if (!RenderSystem.isOnRenderThread()) {
			return; // try again on the render thread; nothing is marked failed
		}
		try {
			// Built on the startup reload's worker threads (OriginAssets); only the
			// three GPU uploads happen here. Built here if that didn't run.
			NativeImage[] levels = com.origin.client.client.OriginAssets.derived(com.origin.client.client.OriginAssets.PLUS_BADGE);
			if (levels == null) {
				levels = buildLevels();
			}
			ResourceLocation[] made = new ResourceLocation[LEVELS.length];
			for (int i = 0; i < LEVELS.length; i++) {
				made[i] = register(levels[i], "plus_badge_" + LEVELS[i]);
			}
			textures = made;
		} catch (Throwable t) {
			failed = true;
			OriginClient.LOGGER.warn("Origin+ badge: couldn't build the badge textures; no badge will draw", t);
		}
	}

	/** Uploads the badge now (OriginAssets' per-frame upload slices) instead of on its first draw. */
	public static void preload() {
		ensure();
	}

	/**
	 * The badge's sizes, box-filtered from the logo: pure CPU, no GL, so the
	 * startup prefetch runs it on a worker thread.
	 */
	public static NativeImage[] buildLevels() throws Exception {
		try (InputStream in = PlusBadge.class.getResourceAsStream(SOURCE)) {
			if (in == null) {
				throw new IllegalStateException("origin_logo.png missing");
			}
			NativeImage[] out = new NativeImage[LEVELS.length];
			try (NativeImage src = NativeImage.read(in)) {
				for (int i = 0; i < LEVELS.length; i++) {
					out[i] = boxFilter(src, LEVELS[i]);
				}
			}
			return out;
		}
	}

	/**
	 * `src` shrunk to size x size by averaging whole blocks of pixels (alpha-
	 * weighted, so the edges don't darken). Every GUI/nametag copy is drawn
	 * stretched to the whole 64 x 64 UV square, so each level must be square
	 * and the source's size must divide evenly (256 -> 32 / 64 / 128).
	 */
	private static NativeImage boxFilter(NativeImage src, int size) {
		int f = Math.max(1, src.getWidth() / size);
		NativeImage out = new NativeImage(size, size, false);
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				long sa = 0, sr = 0, sg = 0, sb = 0;
				for (int dy = 0; dy < f; dy++) {
					for (int dx = 0; dx < f; dx++) {
						int p = src.getPixelRGBA(x * f + dx, y * f + dy); // ABGR
						int a = (p >>> 24) & 0xFF;
						sa += a;
						sr += (long) (p & 0xFF) * a;
						sg += (long) ((p >> 8) & 0xFF) * a;
						sb += (long) ((p >> 16) & 0xFF) * a;
					}
				}
				int n = f * f;
				int a = (int) (sa / n);
				int r = sa > 0 ? (int) (sr / sa) : 255;
				int g = sa > 0 ? (int) (sg / sa) : 255;
				int b = sa > 0 ? (int) (sb / sa) : 255;
				out.setPixelRGBA(x, y, (a << 24) | (b << 16) | (g << 8) | r);
			}
		}
		return out;
	}

	private static ResourceLocation register(NativeImage image, String name) {
		ResourceLocation id = new ResourceLocation("originclient", name);
		DynamicTexture tex = new DynamicTexture(image);
		tex.setFilter(true, false);
		Minecraft.getInstance().getTextureManager().register(id, tex);
		return id;
	}
}
