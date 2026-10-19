package com.origin.client.client.shaders;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import javax.imageio.ImageIO;
import javax.imageio.spi.IIORegistry;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// Lazy per-pack preview thumbnails from Modrinth (featured gallery image, else
// the project icon). Fetched + decoded on a daemon thread, uploaded to GL on
// the render thread, then cached. Only visible rows call get(), so at most a
// handful load at once. Fully guarded — a missing/undecodable image just shows
// the fallback tile.
//
// Decoding goes through ImageIO, not NativeImage/STB: Modrinth serves covers as
// lossless WebP, which STB can't read. TwelveMonkeys' WebP reader is registered
// with ImageIO once below so ImageIO.read handles WebP (and png/jpeg) uniformly.
public final class ShaderPreviews {
	private ShaderPreviews() {
	}

	static {
		try {
			IIORegistry.getDefaultInstance().registerServiceProvider(
					new com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi());
		} catch (Throwable t) {
			com.origin.client.OriginClient.LOGGER.warn("WebP preview decoder unavailable; covers will fall back", t);
		}
	}

	// a decoded preview image (Java-8 final class, was a record).
	public static final class Preview {
		private final ResourceLocation id; private final int w; private final int h;
		public Preview(ResourceLocation id, int w, int h) { this.id = id; this.w = w; this.h = h; }
		public ResourceLocation id() { return id; }
		public int w() { return w; }
		public int h() { return h; }
	}

	private static final Map<String, Preview> READY = new ConcurrentHashMap<>();
	private static final Set<String> INFLIGHT = ConcurrentHashMap.newKeySet();
	// Loads not finished yet (fetch + decode + the render-thread upload). Only the dev
	// shots read it: they wait on it so a capture never races a run's first fetch.
	private static final java.util.concurrent.atomic.AtomicInteger LOADING = new java.util.concurrent.atomic.AtomicInteger();

	/** True while any preview is still downloading, decoding or waiting for its GL upload. */
	public static boolean loading() {
		return LOADING.get() > 0;
	}
	private static final Gson GSON = new Gson();
	private static final String UA = "OriginClient (will@willhenry.me)";

	/** The cached preview, kicking off a one-time async load on first miss. */
	public static Preview get(String slug) {
		Preview p = READY.get(slug);
		if (p != null) {
			return p;
		}
		if (INFLIGHT.add(slug)) {
			LOADING.incrementAndGet();
			Thread t = new Thread(() -> {
				try {
					load(slug);
				} finally {
					// Queued behind load()'s own upload task (same FIFO queue), so it counts the upload too.
					Minecraft.getInstance().execute(LOADING::decrementAndGet);
				}
			}, "origin-shader-preview-" + slug);
			t.setDaemon(true);
			t.start();
		}
		return null;
	}

	private static void load(String slug) {
		try {
			JsonObject proj = getJson("https://api.modrinth.com/v2/project/" + slug);
			String url = null;
			JsonArray gallery = proj.getAsJsonArray("gallery");
			if (gallery != null && gallery.size() > 0) {
				for (com.google.gson.JsonElement el : gallery) {
					JsonObject gi = el.getAsJsonObject();
					if (gi.has("featured") && gi.get("featured").getAsBoolean()) {
						url = gi.get("url").getAsString();
						break;
					}
				}
				if (url == null) {
					url = gallery.get(0).getAsJsonObject().get("url").getAsString();
				}
			}
			if (url == null && proj.has("icon_url") && !proj.get("icon_url").isJsonNull()) {
				url = proj.get("icon_url").getAsString();
			}
			if (url == null) {
				return;
			}
			byte[] bytes = getBytes(url);
			BufferedImage bi = ImageIO.read(new ByteArrayInputStream(bytes));
			if (bi == null) {
				return;
			}
			NativeImage img = toNativeImage(bi);
			int w = img.getWidth(), h = img.getHeight();
			Minecraft.getInstance().execute(() -> {
				try {
					ResourceLocation id = new ResourceLocation(
							"originclient", "shader_preview/" + slug.replaceAll("[^a-z0-9/._-]", "_"));
					DynamicTexture tex = new DynamicTexture(img);
					tex.setFilter(true, false);
					Minecraft.getInstance().getTextureManager().register(id, tex);
					READY.put(slug, new Preview(id, w, h));
				} catch (Throwable t) {
					img.close();
				}
			});
		} catch (Throwable t) {
			// leave unavailable; INFLIGHT keeps it from retrying in a tight loop
			com.origin.client.OriginClient.LOGGER.debug("preview load failed for {}", slug, t);
		}
	}

	// Copies an ImageIO BufferedImage into a fresh NativeImage. getRGB gives
	// ARGB (0xAARRGGBB); NativeImage stores RGBA bytes little-endian, so the
	// packed int it wants is ABGR (0xAABBGGRR) — swap R and B.
	private static NativeImage toNativeImage(BufferedImage bi) {
		int w = bi.getWidth(), h = bi.getHeight();
		NativeImage img = new NativeImage(NativeImage.Format.RGBA, w, h, false);
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int argb = bi.getRGB(x, y);
				int a = (argb >>> 24) & 0xFF, r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
				img.setPixelRGBA(x, y, (a << 24) | (b << 16) | (g << 8) | r);
			}
		}
		return img;
	}

	private static JsonObject getJson(String url) throws Exception {
		try (InputStream in = open(url).getInputStream()) {
			return GSON.fromJson(new String(com.origin.client.client.IoCompat.readAllBytes(in), StandardCharsets.UTF_8), JsonObject.class);
		}
	}

	private static byte[] getBytes(String url) throws Exception {
		try (InputStream in = open(url).getInputStream()) {
			return com.origin.client.client.IoCompat.readAllBytes(in);
		}
	}

	private static HttpURLConnection open(String url) throws Exception {
		HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
		conn.setRequestProperty("User-Agent", UA);
		conn.setConnectTimeout(10000);
		conn.setReadTimeout(30000);
		conn.setInstanceFollowRedirects(true);
		return conn;
	}
}
