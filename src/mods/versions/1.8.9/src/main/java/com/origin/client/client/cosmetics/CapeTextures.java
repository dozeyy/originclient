package com.origin.client.client.cosmetics;

import com.origin.client.OriginClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.client.resources.IResource;
import net.minecraft.util.ResourceLocation;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Cape textures. Each cape is a PNG in Minecraft's cape layout (64x32 model
 * px) at the item's own scale, animated capes stacking their frames
 * vertically (FORMAT.md "Capes"). The scale, frame count and fps come from the
 * item's item.json ({@link CosmeticDef#cape}); nothing is read from the PNG's
 * size except to check it matches. Frames are cut into separate textures on
 * first use and then just picked by the clock.
 *
 * 1.8.9: no NativeImage -- the sheet is read as a BufferedImage
 * (TextureUtil.readBufferedImage), each frame is copied into its own image and
 * registered as a DynamicTexture with TextureManager.loadTexture. 1.8.9's cape
 * model is laid out for a 64x32 texture and its UVs are normalised, so any
 * scale of that layout maps 1:1.
 */
public final class CapeTextures {
	private CapeTextures() {
	}

	/** One loaded cape. `frames` are in order; all `w` x `h`. */
	// 1.8.9: Java 8 has no records, a final value class
	private static final class Loaded {
		final ResourceLocation[] frames;
		final int w, h, scale;
		final float fps;

		Loaded(ResourceLocation[] frames, int w, int h, int scale, float fps) {
			this.frames = frames;
			this.w = w;
			this.h = h;
			this.scale = scale;
			this.fps = fps;
		}

		static final Loaded NONE = new Loaded(new ResourceLocation[0], 0, 0, 1, 10f);
	}

	/**
	 * The outside face of a cape as a rectangle of one frame, for thumbnails:
	 * blit `tex` region (u, v, w, h) of a texW x texH texture.
	 */
	// 1.8.9: Java 8 has no records, a final value class with the record's accessors
	public static final class Face {
		private final ResourceLocation tex;
		private final int u, v, w, h, texW, texH;

		public Face(ResourceLocation tex, int u, int v, int w, int h, int texW, int texH) {
			this.tex = tex;
			this.u = u;
			this.v = v;
			this.w = w;
			this.h = h;
			this.texW = texW;
			this.texH = texH;
		}

		public ResourceLocation tex() {
			return tex;
		}

		public int u() {
			return u;
		}

		public int v() {
			return v;
		}

		public int w() {
			return w;
		}

		public int h() {
			return h;
		}

		public int texW() {
			return texW;
		}

		public int texH() {
			return texH;
		}
	}

	// Render thread only. NONE = tried and failed: never retried, so a missing or
	// wrong-sized texture costs one log line, not one per frame.
	private static final Map<String, Loaded> LOADED = new HashMap<>();

	/** The texture to draw right now for cape `id`, or null if there isn't one. */
	public static ResourceLocation current(String id) {
		Loaded l = loaded(id);
		return l == null ? null : l.frames[frameIndex(l)];
	}

	/** The cape's outside face (vanilla layout: 10 x 16 px at 1,1), at the current frame; null if no cape. */
	public static Face face(String id) {
		Loaded l = loaded(id);
		if (l == null) {
			return null;
		}
		int s = l.scale;
		return new Face(l.frames[frameIndex(l)], s, s, 10 * s, 16 * s, l.w, l.h);
	}

	private static int frameIndex(Loaded l) {
		if (l.frames.length <= 1) {
			return 0;
		}
		return (int) ((Minecraft.getSystemTime() * (double) l.fps / 1000.0) % l.frames.length);
	}

	private static Loaded loaded(String id) {
		// 1.8.9: the render thread is the client thread (no RenderSystem.isOnRenderThread)
		if (id == null || !Minecraft.getMinecraft().isCallingFromMinecraftThread()) {
			return null;
		}
		Loaded l = LOADED.get(id);
		if (l == null) {
			l = load(id);
			// A catalog that isn't loaded yet (resources still reloading) must not be remembered as "no cape".
			if (l != Loaded.NONE || CosmeticCatalog.item(id) != null) {
				LOADED.put(id, l);
			}
		}
		return l.frames.length == 0 ? null : l;
	}

	/** Drops every cut frame (resource reload); the next draw cuts them again. */
	public static void clear() {
		LOADED.clear();
	}

	private static Loaded load(String id) {
		try {
			CosmeticDef def = CosmeticCatalog.def(id);
			if (def == null || def.cape == null) {
				return Loaded.NONE;
			}
			int scale = def.cape.scale(), count = Math.max(1, def.cape.frames());
			int w = 64 * scale, h = 32 * scale;
			Minecraft mc = Minecraft.getMinecraft();
			BufferedImage sheet;
			try {
				// 1.8.9: getResource throws (FileNotFoundException) for a missing file,
				// and IResource itself is not Closeable -- its stream is.
				IResource res = mc.getResourceManager().getResource(CosmeticCatalog.texture(id));
				InputStream in = res.getInputStream();
				try {
					sheet = TextureUtil.readBufferedImage(in);
				} finally {
					in.close();
				}
			} catch (java.io.FileNotFoundException e) {
				OriginClient.LOGGER.warn("Origin cosmetics: no cape texture for " + id);
				return Loaded.NONE;
			}
			if (sheet.getWidth() != w || sheet.getHeight() < h * count) {
				OriginClient.LOGGER.warn("Origin cosmetics: cape " + id + " is " + sheet.getWidth() + "x" + sheet.getHeight()
						+ " but item.json says " + count + " frame(s) at scale " + scale + " (" + w + "x" + h + " each)");
				return Loaded.NONE;
			}
			ResourceLocation[] out = new ResourceLocation[count];
			for (int f = 0; f < count; f++) {
				// a copy, not a view: a sub-image would keep the whole sheet's raster alive
				BufferedImage frame = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
				int[] px = sheet.getRGB(0, f * h, w, h, null, 0, w);
				frame.setRGB(0, 0, w, h, px, 0, w);
				out[f] = new ResourceLocation("originclient", "cosmetics/cape/" + id + "/" + f);
				mc.getTextureManager().loadTexture(out[f], new DynamicTexture(frame));
			}
			return new Loaded(out, w, h, scale, def.cape.fps() > 0f ? def.cape.fps() : 10f);
		} catch (Throwable e) {
			OriginClient.LOGGER.warn("Origin cosmetics: couldn't load cape texture " + id, e);
			return Loaded.NONE;
		}
	}
}
