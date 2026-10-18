package com.origin.client.client.cosmetics;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.origin.client.OriginClient;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

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
 */
public final class CapeTextures {
	private CapeTextures() {
	}

	/** One loaded cape. `frames` are in order; all `w` x `h`. */
	private record Loaded(ResourceLocation[] frames, int w, int h, int scale, float fps) {
		static final Loaded NONE = new Loaded(new ResourceLocation[0], 0, 0, 1, 10f);
	}

	/**
	 * The outside face of a cape as a rectangle of one frame, for thumbnails:
	 * blit `tex` region (u, v, w, h) of a texW x texH texture.
	 */
	public record Face(ResourceLocation tex, int u, int v, int w, int h, int texW, int texH) {
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
		return (int) ((Util.getMillis() * (double) l.fps / 1000.0) % l.frames.length);
	}

	private static Loaded loaded(String id) {
		if (id == null || !RenderSystem.isOnRenderThread()) {
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
			ResourceLocation file = CosmeticCatalog.texture(id);
			// 1.17.1: getResource throws (FileNotFoundException) instead of returning
			// an Optional, and the stream is Resource.getInputStream.
			if (!Minecraft.getInstance().getResourceManager().hasResource(file)) {
				OriginClient.LOGGER.warn("Origin cosmetics: no cape texture for {}", id);
				return Loaded.NONE;
			}
			try (Resource res = Minecraft.getInstance().getResourceManager().getResource(file);
				 InputStream in = res.getInputStream(); NativeImage sheet = NativeImage.read(in)) {
				if (sheet.getWidth() != w || sheet.getHeight() < h * count) {
					OriginClient.LOGGER.warn("Origin cosmetics: cape {} is {}x{} but item.json says {} frame(s) at scale {} ({}x{} each)",
							id, sheet.getWidth(), sheet.getHeight(), count, scale, w, h);
					return Loaded.NONE;
				}
				ResourceLocation[] out = new ResourceLocation[count];
				for (int f = 0; f < count; f++) {
					NativeImage frame = new NativeImage(w, h, false);
					for (int y = 0; y < h; y++) {
						for (int x = 0; x < w; x++) {
							frame.setPixelRGBA(x, y, sheet.getPixelRGBA(x, f * h + y));
						}
					}
					out[f] = new ResourceLocation("originclient", "cosmetics/cape/" + id + "/" + f);
					Minecraft.getInstance().getTextureManager().register(out[f], new DynamicTexture(frame));
				}
				return new Loaded(out, w, h, scale, def.cape.fps() > 0f ? def.cape.fps() : 10f);
			}
		} catch (Throwable e) {
			OriginClient.LOGGER.warn("Origin cosmetics: couldn't load cape texture {}", id, e);
			return Loaded.NONE;
		}
	}
}
