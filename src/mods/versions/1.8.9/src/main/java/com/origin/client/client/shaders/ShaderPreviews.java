package com.origin.client.client.shaders;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.origin.client.client.gui.Gfx;
import com.origin.client.client.net.OriginHttp;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ResourceLocation;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

// Lazy per-pack preview thumbnails from Modrinth (featured gallery image, else
// the first gallery image, else the project icon). Fetched + decoded on a small
// worker pool (3 threads, short queue -- it was one thread per pack, 35+ at once),
// uploaded to GL on the client thread, then cached: at most 48 textures, the least
// recently drawn released. A failed load (offline, bad image) is tried again after
// a minute instead of never. Only visible rows call get(). Fully guarded -- a
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

	private static final int MAX_TEXTURES = 48;
	private static final int MAX_IMAGE_BYTES = 8 << 20;
	private static final int MAX_DECODE_EDGE = 16384, MAX_DECODE_PIXELS = 4 << 20;
	/** A failed preview is not asked for again for this long (offline: it comes back on its own). */
	private static final long RETRY_MS = 60000;

	// client thread only (get() and the upload task)
	private static final Map<String, Preview> READY = new LinkedHashMap<String, Preview>(64, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Preview> e) {
			if (size() > MAX_TEXTURES) {
				Minecraft.getMinecraft().getTextureManager().deleteTexture(e.getValue().id());
				return true;
			}
			return false;
		}
	};
	private static final Set<String> INFLIGHT =
			Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
	private static final Map<String, Long> FAILED = new ConcurrentHashMap<String, Long>();
	private static final ThreadPoolExecutor POOL = new ThreadPoolExecutor(3, 3, 30, TimeUnit.SECONDS,
			new ArrayBlockingQueue<Runnable>(12), new ThreadFactory() {
				@Override
				public Thread newThread(Runnable r) {
					Thread t = new Thread(r, "origin-shader-preview");
					t.setDaemon(true);
					return t;
				}
			});

	static {
		POOL.allowCoreThreadTimeOut(true);
	}
	private static final String UA = "OriginClient (will@willhenry.me)";

	/** The cached preview, else null while an async load runs (a failed one is retried after RETRY_MS). */
	public static Preview get(final String slug) {
		Preview p = READY.get(slug);
		if (p != null) {
			return p;
		}
		Long failedAt = FAILED.get(slug);
		if (failedAt != null && System.currentTimeMillis() - failedAt < RETRY_MS) {
			return null;
		}
		if (INFLIGHT.add(slug)) {
			try {
				POOL.execute(new Runnable() {
					@Override
					public void run() {
						load(slug);
					}
				});
			} catch (RejectedExecutionException full) {
				INFLIGHT.remove(slug);   // queue full: the row asks again next frame while it is on screen
			}
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
				fail(slug);
				return;
			}
			byte[] bytes = getBytes(url);
			BufferedImage bi = bytes == null ? null : decode(bytes);
			if (bi == null) {
				fail(slug);   // e.g. a WebP-only icon: the fallback tile stays
				return;
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
					} finally {
						INFLIGHT.remove(slug);   // READY has it now; after an LRU release it loads again
					}
				}
			});
		} catch (Throwable t) {
			// offline or a bad image: the fallback tile, and one more try after RETRY_MS
			fail(slug);
			com.origin.client.OriginClient.LOGGER.debug("preview load failed for " + slug, t);
		}
	}

	private static void fail(String slug) {
		FAILED.put(slug, System.currentTimeMillis());
		INFLIGHT.remove(slug);
	}

	/**
	 * Reads the header size first. raw_url is the original upload (often 4K): a big
	 * one is decoded subsampled (every n-th pixel, still at least twice MAX_W) so it
	 * never sits in the heap at full size; a header claiming an absurd size is not
	 * decoded at all.
	 */
	private static BufferedImage decode(byte[] bytes) throws java.io.IOException {
		try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
			if (in == null) {
				return null;
			}
			Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
			if (!readers.hasNext()) {
				return null;
			}
			ImageReader r = readers.next();
			try {
				r.setInput(in, true, true);
				long w = r.getWidth(0), h = r.getHeight(0);
				if (w <= 0 || h <= 0 || w > MAX_DECODE_EDGE || h > MAX_DECODE_EDGE) {
					return null;
				}
				int step = (int) Math.max(1, Math.max(w, h) / (MAX_W * 2));
				if ((w / step) * (h / step) > MAX_DECODE_PIXELS) {
					return null;
				}
				ImageReadParam param = r.getDefaultReadParam();
				param.setSourceSubsampling(step, step, 0, 0);
				return r.read(0, param);
			} finally {
				r.dispose();
			}
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
			return readCapped(in);
		}
	}

	/** The whole stream, or null past MAX_IMAGE_BYTES (a preview is never that big). */
	private static byte[] readCapped(InputStream in) throws java.io.IOException {
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		byte[] buf = new byte[16384];
		int n;
		while ((n = in.read(buf)) != -1) {
			out.write(buf, 0, n);
			if (out.size() > MAX_IMAGE_BYTES) {
				return null;
			}
		}
		return out.toByteArray();
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
