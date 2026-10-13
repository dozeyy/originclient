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
 * Cape textures, made by tools/cosmetics/export-capes.mjs from the store's
 * own art (so the game and the store always match). Each PNG is Minecraft's
 * cape layout at 4x (256x128 per frame); animated capes stack FRAMES frames
 * vertically, one LOOP of the website animation. Frames are cut into
 * separate textures on first use and then just picked by the clock.
 *
 * 1.8.9: no NativeImage -- the sheet is read as a BufferedImage
 * (TextureUtil.readBufferedImage), each frame is a getSubimage registered as a
 * DynamicTexture with TextureManager.loadTexture. 1.8.9's cape model is laid
 * out for a 64x32 texture; a 256x128 frame is the same layout at 4x, and the
 * model's UVs are normalised, so it maps 1:1.
 */
public final class CapeTextures {
	private CapeTextures() {
	}

	/** One frame's time: the website's LOOP (2.4 s) / the exporter's FRAMES (24). */
	static final long FRAME_MS = 100;
	/** Size of one frame, also the outside face's place within it (for thumbnails). */
	public static final int FRAME_W = 256, FRAME_H = 128;
	public static final int FACE_U = 4, FACE_V = 4, FACE_W = 40, FACE_H = 64;

	// Render thread only. An empty array = tried and failed: never retried, so
	// a missing texture costs one log line, not one per frame.
	private static final Map<String, ResourceLocation[]> FRAMES = new HashMap<>();

	/** The texture to draw right now for cape `id`, or null if there isn't one. */
	public static ResourceLocation current(String id) {
		// 1.8.9: the render thread is the client thread (no RenderSystem.isOnRenderThread)
		if (id == null || !Minecraft.getMinecraft().isCallingFromMinecraftThread()) {
			return null;
		}
		ResourceLocation[] frames = FRAMES.get(id);
		if (frames == null) {
			frames = load(id);
			FRAMES.put(id, frames);
		}
		if (frames.length == 0) {
			return null;
		}
		return frames[(int) ((Minecraft.getSystemTime() / FRAME_MS) % frames.length)];
	}

	private static ResourceLocation[] load(String id) {
		ResourceLocation file = new ResourceLocation("originclient", "textures/cosmetics/capes/" + id + ".png");
		Minecraft mc = Minecraft.getMinecraft();
		BufferedImage sheet;
		try {
			// 1.8.9: getResource throws (FileNotFoundException) for a missing file,
			// and IResource itself is not Closeable -- its stream is.
			IResource res = mc.getResourceManager().getResource(file);
			InputStream in = res.getInputStream();
			try {
				sheet = TextureUtil.readBufferedImage(in);
			} finally {
				in.close();
			}
		} catch (java.io.FileNotFoundException e) {
			OriginClient.LOGGER.warn("Origin cosmetics: no cape texture for " + id);
			return new ResourceLocation[0];
		} catch (Exception e) {
			OriginClient.LOGGER.warn("Origin cosmetics: couldn't load cape texture " + id, e);
			return new ResourceLocation[0];
		}
		try {
			int count = Math.max(1, sheet.getHeight() / FRAME_H);
			ResourceLocation[] out = new ResourceLocation[count];
			for (int f = 0; f < count; f++) {
				// a copy, not a view: DynamicTexture reads the pixels once, but a
				// sub-image keeps the whole sheet's raster alive otherwise
				BufferedImage frame = new BufferedImage(FRAME_W, FRAME_H, BufferedImage.TYPE_INT_ARGB);
				int h = Math.min(FRAME_H, sheet.getHeight() - f * FRAME_H);
				int w = Math.min(FRAME_W, sheet.getWidth());
				int[] row = sheet.getRGB(0, f * FRAME_H, w, h, null, 0, w);
				frame.setRGB(0, 0, w, h, row, 0, w);
				out[f] = new ResourceLocation("originclient", "cosmetics/cape/" + id + "/" + f);
				mc.getTextureManager().loadTexture(out[f], new DynamicTexture(frame));
			}
			return out;
		} catch (Exception e) {
			OriginClient.LOGGER.warn("Origin cosmetics: couldn't cut cape texture " + id, e);
			return new ResourceLocation[0];
		}
	}
}
