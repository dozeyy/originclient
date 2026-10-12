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
import java.util.Optional;

/**
 * Cape textures, made by tools/cosmetics/export-capes.mjs from the store's
 * own art (so the game and the store always match). Each PNG is Minecraft's
 * cape layout at 4x (256x128 per frame); animated capes stack FRAMES frames
 * vertically, one LOOP of the website animation. Frames are cut into
 * separate textures on first use and then just picked by the clock.
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
		if (!RenderSystem.isOnRenderThread()) {
			return null;
		}
		ResourceLocation[] frames = FRAMES.computeIfAbsent(id, CapeTextures::load);
		if (frames.length == 0) {
			return null;
		}
		return frames[(int) ((Util.getMillis() / FRAME_MS) % frames.length)];
	}

	private static ResourceLocation[] load(String id) {
		ResourceLocation file = new ResourceLocation("originclient", "textures/cosmetics/capes/" + id + ".png");
		Optional<Resource> res = Minecraft.getInstance().getResourceManager().getResource(file);
		if (res.isEmpty()) {
			OriginClient.LOGGER.warn("Origin cosmetics: no cape texture for {}", id);
			return new ResourceLocation[0];
		}
		try (InputStream in = res.get().open(); NativeImage sheet = NativeImage.read(in)) {
			int count = Math.max(1, sheet.getHeight() / FRAME_H);
			ResourceLocation[] out = new ResourceLocation[count];
			for (int f = 0; f < count; f++) {
				NativeImage frame = new NativeImage(FRAME_W, FRAME_H, false);
				for (int y = 0; y < FRAME_H; y++) {
					for (int x = 0; x < FRAME_W; x++) {
						frame.setPixelRGBA(x, y, sheet.getPixelRGBA(x, f * FRAME_H + y));
					}
				}
				out[f] = new ResourceLocation("originclient", "cosmetics/cape/" + id + "/" + f);
				Minecraft.getInstance().getTextureManager().register(out[f], new DynamicTexture(frame));
			}
			return out;
		} catch (Exception e) {
			OriginClient.LOGGER.warn("Origin cosmetics: couldn't load cape texture {}", id, e);
			return new ResourceLocation[0];
		}
	}
}
