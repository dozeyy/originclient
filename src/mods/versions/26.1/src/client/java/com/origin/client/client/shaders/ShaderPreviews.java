package com.origin.client.client.shaders;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

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

	public record Preview(Identifier id, int w, int h) {
	}

	private static final Map<String, Preview> READY = new ConcurrentHashMap<>();
	private static final Set<String> INFLIGHT = ConcurrentHashMap.newKeySet();
	private static final Gson GSON = new Gson();
	private static final String UA = "OriginClient (will@willhenry.me)";

	/** The cached preview, kicking off a one-time async load on first miss. */
	public static Preview get(String slug) {
		Preview p = READY.get(slug);
		if (p != null) {
			return p;
		}
		if (INFLIGHT.add(slug)) {
			Thread t = new Thread(() -> load(slug), "origin-shader-preview-" + slug);
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
			if (gallery != null && !gallery.isEmpty()) {
				for (var el : gallery) {
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
					Identifier id = Identifier.fromNamespaceAndPath(
							"originclient", "shader_preview/" + slug.replaceAll("[^a-z0-9/._-]", "_"));
					DynamicTexture tex = new DynamicTexture(() -> "originclient:shader_preview", img);
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
				img.setPixelABGR(x, y, (a << 24) | (b << 16) | (g << 8) | r);
			}
		}
		return img;
	}

	private static JsonObject getJson(String url) throws Exception {
		try (InputStream in = open(url).getInputStream()) {
			return GSON.fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8), JsonObject.class);
		}
	}

	private static byte[] getBytes(String url) throws Exception {
		try (InputStream in = open(url).getInputStream()) {
			return in.readAllBytes();
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
