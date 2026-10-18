package com.origin.client.client.packs;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;

// Lazy thumbnails for the hub: Modrinth gallery images / icons (Browse) and each
// installed pack's own pack.png (Installed). Only rows on screen ask, so a
// 1000-result list never loads 1000 images:
//   - 3 decode threads with a short queue; when it is full the request is simply
//     dropped and the still-visible row asks again next frame (fast scrolling
//     never builds a backlog of images nobody sees)
//   - images are downscaled to <= 320 px before upload
//   - at most 80 textures stay on the GPU; the least recently drawn is released
// Images come only from Modrinth's CDN over https (ModrinthPacks.trustedImage)
// and are decoded as images, nothing else. Same ImageIO + WebP path as the
// shader browser (Modrinth serves WebP, which Minecraft's STB can't read).
public final class PackPreviews {
	private PackPreviews() {
	}

	static {
		try {
			IIORegistry.getDefaultInstance().registerServiceProvider(
					new com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi());
		} catch (Throwable t) {
			com.origin.client.OriginClient.LOGGER.warn("WebP preview decoder unavailable; pack previews fall back", t);
		}
	}

	public record Preview(ResourceLocation id, int w, int h) {
	}

	private static final int MAX_EDGE = 320, MAX_TEXTURES = 80;
	private static final int MAX_IMAGE_BYTES = 8 << 20;
	private static final int MAX_DECODE_EDGE = 4096, MAX_DECODE_PIXELS = 16 << 20;
	/** A failed image is not retried for this long. */
	private static final long RETRY_MS = 5 * 60_000;

	private static final Set<String> INFLIGHT = ConcurrentHashMap.newKeySet();
	private static final Map<String, Long> FAILED = new ConcurrentHashMap<>();
	private static int serial = 0;
	// render thread only
	private static final Map<String, Preview> READY = new LinkedHashMap<>(128, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Preview> e) {
			if (size() > MAX_TEXTURES) {
				Minecraft.getInstance().getTextureManager().release(e.getValue().id());
				return true;
			}
			return false;
		}
	};
	private static final ThreadPoolExecutor POOL = new ThreadPoolExecutor(3, 3, 30, TimeUnit.SECONDS,
			new ArrayBlockingQueue<>(12), r -> {
				Thread t = new Thread(r, "origin-pack-preview");
				t.setDaemon(true);
				return t;
			});

	static {
		POOL.allowCoreThreadTimeOut(true);
	}

	/** A Modrinth image (render thread). Null while loading or if it can't load. */
	public static Preview remote(String url) {
		if (url == null || url.isEmpty() || !ModrinthPacks.trustedImage(url)) {
			return null;
		}
		return get("u:" + url, () -> fetch(url), false);
	}

	/**
	 * An installed pack's pack.png (render thread). key changes when the file does
	 * (callers pass name + size), so a replaced pack shows its new icon.
	 */
	public static Preview local(Path pack, boolean folder, String key) {
		return get("l:" + key, () -> folder ? readFolderIcon(pack) : readZipIcon(pack), true);
	}

	private interface Source {
		byte[] bytes() throws Exception;
	}

	private static Preview get(String key, Source src, boolean pixelArt) {
		Preview p = READY.get(key);
		if (p != null) {
			return p;
		}
		Long failedAt = FAILED.get(key);
		if (failedAt != null && System.currentTimeMillis() - failedAt < RETRY_MS) {
			return null;
		}
		if (INFLIGHT.add(key)) {
			try {
				POOL.execute(() -> load(key, src, pixelArt));
			} catch (RejectedExecutionException full) {
				INFLIGHT.remove(key); // asked again next frame if still on screen
			}
		}
		return null;
	}

	private static void load(String key, Source src, boolean pixelArt) {
		try {
			byte[] bytes = src.bytes();
			BufferedImage bi = bytes == null ? null : decode(bytes);
			if (bi == null) {
				fail(key);
				return;
			}
			NativeImage img = toNativeImage(downscale(bi));
			int w = img.getWidth(), h = img.getHeight();
			Minecraft.getInstance().execute(() -> {
				try {
					ResourceLocation id = ResourceLocation.fromNamespaceAndPath("originclient", "pack_preview/" + (serial++));
					DynamicTexture tex = new DynamicTexture(img);
					// pack.png is pixel art: keep it crisp; screenshots read better smoothed
					tex.setFilter(!pixelArt, false);
					Minecraft.getInstance().getTextureManager().register(id, tex);
					READY.put(key, new Preview(id, w, h));
				} catch (Throwable t) {
					img.close();
				} finally {
					INFLIGHT.remove(key);
				}
			});
		} catch (Throwable t) {
			fail(key);
		}
	}

	/**
	 * Decodes only images whose header declares a sane size: a few KB of PNG/WebP
	 * can claim 50000x50000 px and exhaust the heap (pack.png comes from any zip).
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
				if (w <= 0 || h <= 0 || w > MAX_DECODE_EDGE || h > MAX_DECODE_EDGE || w * h > MAX_DECODE_PIXELS) {
					return null;
				}
				return r.read(0);
			} finally {
				r.dispose();
			}
		}
	}

	private static void fail(String key) {
		FAILED.put(key, System.currentTimeMillis());
		INFLIGHT.remove(key);
	}

	private static byte[] fetch(String url) throws Exception {
		HttpURLConnection conn = ModrinthPacks.open(url);
		conn.setRequestProperty("Accept", "image/*");
		if (conn.getResponseCode() != 200 || !ModrinthPacks.trustedImage(conn.getURL().toString())) {
			conn.disconnect();
			return null;
		}
		try (InputStream in = conn.getInputStream()) {
			byte[] b = in.readNBytes(MAX_IMAGE_BYTES + 1);
			return b.length > MAX_IMAGE_BYTES ? null : b;
		}
	}

	private static byte[] readZipIcon(Path zip) throws Exception {
		try (ZipFile zf = new ZipFile(zip.toFile())) {
			var e = zf.getEntry("pack.png");
			if (e == null || e.getSize() > MAX_IMAGE_BYTES) {
				return null;
			}
			try (InputStream in = zf.getInputStream(e)) {
				byte[] b = in.readNBytes(MAX_IMAGE_BYTES + 1);
				return b.length > MAX_IMAGE_BYTES ? null : b;
			}
		}
	}

	private static byte[] readFolderIcon(Path dir) throws Exception {
		Path p = dir.resolve("pack.png");
		return Files.isRegularFile(p) && Files.size(p) <= MAX_IMAGE_BYTES ? Files.readAllBytes(p) : null;
	}

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

	// ImageIO's ARGB straight into NativeImage: setPixel takes ARGB in this era
	// (1.21.2+; setPixelABGR is private), the same as this module's ShaderPreviews.
	private static NativeImage toNativeImage(BufferedImage bi) {
		int w = bi.getWidth(), h = bi.getHeight();
		NativeImage img = new NativeImage(NativeImage.Format.RGBA, w, h, false);
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				img.setPixel(x, y, bi.getRGB(x, y));
			}
		}
		return img;
	}
}
