package com.origin.client.client.shaders;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.origin.client.client.IoCompat;
import com.origin.client.client.gui.Gfx;
import com.origin.client.client.net.OriginHttp;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ResourceLocation;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// Lazy per-pack preview thumbnails from Modrinth (featured gallery image, else
// the first gallery image, else the project icon). Fetched + decoded on a daemon
// thread, uploaded to GL on the client thread, then cached. Only visible rows
// call get(), so at most a handful load at once. Fully guarded -- a
// missing/undecodable image just shows the fallback tile.
//
// 1.8.9: Modrinth serves its gallery `url`s and icons as lossless WebP, which
// 1.16.5 decodes with TwelveMonkeys' ImageIO plugin; that plugin is not on
// 1.8.9's classpath (no build.gradle change in this port). Every gallery image
// also carries a `raw_url` -- the original upload, PNG or JPEG -- which plain
// ImageIO reads, so previews use that and are scaled down to thumbnail size
// before the upload (raw uploads can be 4K). A pack whose only image is a WebP
// icon keeps the fallback tile.
public final class ShaderPreviews {
	private ShaderPreviews() {
	}

	// a decoded preview image (Java-8 final class, was a record).
	public static final class Preview {
		private final ResourceLocation id; private final int w; private final int h;
		public Preview(ResourceLocation id, int w, int h) { this.id = id; this.w = w; this.h = h; }
		public ResourceLocation id() { return id; }
		public int w() { return w; }
		public int h() { return h; }
	}

	/** Thumbnails are drawn ~100 design px wide; 400 px keeps them sharp on 4K. */
	private static final int MAX_W = 400;

	private static final Map<String, Preview> READY = new ConcurrentHashMap<>();
	private static final Set<String> INFLIGHT =
			Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
	private static final String UA = "OriginClient (will@willhenry.me)";

	/** The cached preview, kicking off a one-time async load on first miss. */
	public static Preview get(final String slug) {
		Preview p = READY.get(slug);
		if (p != null) {
			return p;
		}
		if (INFLIGHT.add(slug)) {
			Thread t = new Thread(new Runnable() {
				@Override
				public void run() {
					load(slug);
				}
			}, "origin-shader-preview-" + slug);
			t.setDaemon(true);
			t.start();
		}
		return null;
	}

	private static void load(final String slug) {
		try {
			JsonObject proj = getJson("https://api.modrinth.com/v2/project/" + slug);
			String url = null;
			JsonArray gallery = proj.getAsJsonArray("gallery");
			if (gallery != null && gallery.size() > 0) {
				JsonObject pick = null;
				for (JsonElement el : gallery) {
					JsonObject gi = el.getAsJsonObject();
					if (gi.has("featured") && gi.get("featured").getAsBoolean()) {
						pick = gi;
						break;
					}
				}
				if (pick == null) {
					pick = gallery.get(0).getAsJsonObject();
				}
				url = imageUrl(pick);
			}
			if (url == null && proj.has("icon_url") && !proj.get("icon_url").isJsonNull()) {
				url = proj.get("icon_url").getAsString();
			}
			if (url == null) {
				return;
			}
			BufferedImage bi = ImageIO.read(new ByteArrayInputStream(getBytes(url)));
			if (bi == null) {
				return;   // e.g. a WebP-only icon: the fallback tile stays
			}
			final BufferedImage img = downscale(bi);
			Minecraft.getMinecraft().addScheduledTask(new Runnable() {
				@Override
				public void run() {
					try {
						ResourceLocation id = new ResourceLocation(
								"originclient", "shader_preview/" + slug.replaceAll("[^a-z0-9/._-]", "_"));
						if (Gfx.registerTexture(id, img, true) != null) {
							READY.put(slug, new Preview(id, img.getWidth(), img.getHeight()));
						}
					} catch (Throwable ignored) {
						// fallback tile
					}
				}
			});
		} catch (Throwable t) {
			// leave unavailable; INFLIGHT keeps it from retrying in a tight loop
			com.origin.client.OriginClient.LOGGER.debug("preview load failed for " + slug, t);
		}
	}

	/** raw_url (PNG/JPEG original) first, else url (WebP thumbnail). */
	private static String imageUrl(JsonObject galleryItem) {
		for (String key : new String[]{"raw_url", "url"}) {
			if (galleryItem.has(key) && !galleryItem.get(key).isJsonNull()) {
				return galleryItem.get(key).getAsString();
			}
		}
		return null;
	}

	/** ARGB copy no wider than MAX_W (bilinear), the shape DynamicTexture uploads. */
	private static BufferedImage downscale(BufferedImage src) {
		int w = src.getWidth(), h = src.getHeight();
		if (w > MAX_W) {
			h = Math.max(1, Math.round(h * (MAX_W / (float) w)));
			w = MAX_W;
		}
		BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.drawImage(src, 0, 0, w, h, null);
		} finally {
			g.dispose();
		}
		return out;
	}

	private static JsonObject getJson(String url) throws Exception {
		try (InputStream in = open(url).getInputStream()) {
			return new JsonParser().parse(OriginHttp.readBody(in)).getAsJsonObject();
		}
	}

	private static byte[] getBytes(String url) throws Exception {
		try (InputStream in = open(url).getInputStream()) {
			return IoCompat.readAllBytes(in);
		}
	}

	private static HttpURLConnection open(String url) throws Exception {
		HttpURLConnection conn = OriginHttp.open(url);
		conn.setRequestProperty("User-Agent", UA);
		conn.setConnectTimeout(10000);
		conn.setReadTimeout(30000);
		conn.setInstanceFollowRedirects(true);
		return conn;
	}
}
