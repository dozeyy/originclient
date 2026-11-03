package com.origin.client.client.shaders;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.spi.IIORegistry;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

// Lazy per-pack preview thumbnails from Modrinth (featured gallery image, else
// the project icon). Fetched + decoded on a small worker pool, uploaded to GL on
// the render thread, then cached. Only visible rows call get(). Same limits as
// PackPreviews: gallery images are often 1920x1080 to 4K and are drawn at 100x56,
// so each is downscaled to <= 320 px before upload (it held 8-33 MB of texture
// per pack before, 35+ packs, for the whole session); 3 threads with a short
// queue instead of one thread per pack; at most 48 textures, the least recently
// drawn released; a failed load (offline, bad image) is tried again after a
// minute instead of never. Fully guarded -- a missing/undecodable image just
// shows the fallback tile.
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

	private static final int MAX_EDGE = 320, MAX_TEXTURES = 48;
	private static final int MAX_IMAGE_BYTES = 8 << 20;
	private static final int MAX_DECODE_EDGE = 16384, MAX_DECODE_PIXELS = 4 << 20;
	/** A failed preview is not asked for again for this long (offline: it comes back on its own). */
	private static final long RETRY_MS = 60_000;

	// render thread only (get() and the upload task)
	private static final Map<String, Preview> READY = new LinkedHashMap<String, Preview>(64, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Preview> e) {
			if (size() > MAX_TEXTURES) {
				Minecraft.getInstance().getTextureManager().release(e.getValue().id());
				return true;
			}
			return false;
		}
	};
	private static final Set<String> INFLIGHT = ConcurrentHashMap.newKeySet();
	private static final Map<String, Long> FAILED = new ConcurrentHashMap<>();
	private static final ThreadPoolExecutor POOL = new ThreadPoolExecutor(3, 3, 30, TimeUnit.SECONDS,
			new ArrayBlockingQueue<Runnable>(12), r -> {
				Thread t = new Thread(r, "origin-shader-preview");
				t.setDaemon(true);
				return t;
			});

	static {
		POOL.allowCoreThreadTimeOut(true);
	}
	// Loads not finished yet (fetch + decode + the render-thread upload). Only the dev
	// shots read it: they wait on it so a capture never races a run's first fetch.
	private static final java.util.concurrent.atomic.AtomicInteger LOADING = new java.util.concurrent.atomic.AtomicInteger();

	/** True while any preview is still downloading, decoding or waiting for its GL upload. */
	public static boolean loading() {
		return LOADING.get() > 0;
	}
	private static final Gson GSON = new Gson();
	private static final String UA = "OriginClient (will@willhenry.me)";

	/** The cached preview, else null while an async load runs (a failed one is retried after RETRY_MS). */
	public static Preview get(String slug) {
		Preview p = READY.get(slug);
		if (p != null) {
			return p;
		}
		Long failedAt = FAILED.get(slug);
		if (failedAt != null && System.currentTimeMillis() - failedAt < RETRY_MS) {
			return null;
		}
		if (INFLIGHT.add(slug)) {
			LOADING.incrementAndGet();
			try {
				POOL.execute(() -> {
					try {
						load(slug);
					} finally {
						// Queued behind load()'s own upload task (same FIFO queue), so it counts the upload too.
						Minecraft.getInstance().execute(LOADING::decrementAndGet);
					}
				});
			} catch (RejectedExecutionException full) {
				// queue full: dropped; the row asks again next frame while it is on screen
				INFLIGHT.remove(slug);
				LOADING.decrementAndGet();
			}
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
				fail(slug);
				return;
			}
			byte[] bytes = getBytes(url);
			BufferedImage bi = bytes == null ? null : decode(bytes);
			if (bi == null) {
				fail(slug);
				return;
			}
			NativeImage img = toNativeImage(downscale(bi));
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
				} finally {
					INFLIGHT.remove(slug);   // READY has it now; after an LRU release it loads again
				}
			});
		} catch (Throwable t) {
			// offline or a bad image: the fallback tile, and one more try after RETRY_MS
			fail(slug);
			com.origin.client.OriginClient.LOGGER.debug("preview load failed for {}", slug, t);
		}
	}

	private static void fail(String slug) {
		FAILED.put(slug, System.currentTimeMillis());
		INFLIGHT.remove(slug);
	}

	/**
	 * Reads the header size first. A big gallery image is decoded subsampled (every
	 * n-th pixel, still at least twice the final 320 px) so a 4K upload never sits in
	 * memory at full size; a header claiming an absurd size (a few KB of PNG/WebP can
	 * claim 50000x50000 px) is not decoded at all.
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
				int step = (int) Math.max(1, Math.max(w, h) / (MAX_EDGE * 2));
				if ((w / step) * (h / step) > MAX_DECODE_PIXELS) {
					return null;
				}
				javax.imageio.ImageReadParam param = r.getDefaultReadParam();
				param.setSourceSubsampling(step, step, 0, 0);
				return r.read(0, param);
			} finally {
				r.dispose();
			}
		}
	}

	// The tile is 100x56 GUI px; 320 px keeps it sharp at any GUI scale for a fraction of the memory.
	private static BufferedImage downscale(BufferedImage bi) {
		int w = bi.getWidth(), h = bi.getHeight();
		if (Math.max(w, h) <= MAX_EDGE) {
			return bi;
		}
		float k = MAX_EDGE / (float) Math.max(w, h);
		int nw = Math.max(1, Math.round(w * k)), nh = Math.max(1, Math.round(h * k));
		BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.drawImage(bi, 0, 0, nw, nh, null);
		g.dispose();
		return out;
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
			return readCapped(in);
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
