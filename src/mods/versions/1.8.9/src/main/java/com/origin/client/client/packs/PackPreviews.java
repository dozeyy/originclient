package com.origin.client.client.packs;

import com.origin.client.client.gui.Gfx;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ResourceLocation;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
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
import java.util.zip.ZipEntry;
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
// and are decoded as images, nothing else.
// 1.8.9 port: a BufferedImage goes straight into a DynamicTexture through
// Gfx.registerTexture (no NativeImage), uploads run via addScheduledTask, and
// release is TextureManager.deleteTexture. 1.8.9 bundles no WebP decoder (the
// shader browser has the same limit), so a WebP-only image keeps the Origin-mark
// tile -- the reader is registered only if some other mod ships TwelveMonkeys.
public final class PackPreviews {
	private PackPreviews() {
	}

	static {
		try {
			Class<?> spi = Class.forName("com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi");
			javax.imageio.spi.IIORegistry.getDefaultInstance().registerServiceProvider(spi.newInstance());
		} catch (Throwable t) {
			// expected on 1.8.9: PNG / JPEG previews only
		}
	}

	/** A decoded preview (1.8.9: Java 8 final class, was a record). */
	public static final class Preview {
		private final ResourceLocation id;
		private final int w;
		private final int h;

		Preview(ResourceLocation id, int w, int h) {
			this.id = id;
			this.w = w;
			this.h = h;
		}

		public ResourceLocation id() {
			return id;
		}

		public int w() {
			return w;
		}

		public int h() {
			return h;
		}
	}

	private static final int MAX_EDGE = 320, MAX_TEXTURES = 80;
	private static final int MAX_IMAGE_BYTES = 8 << 20;
	private static final int MAX_DECODE_EDGE = 4096, MAX_DECODE_PIXELS = 16 << 20;
	/** A failed image is not retried for this long. */
	private static final long RETRY_MS = 5 * 60_000;

	private static final Set<String> INFLIGHT = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
	private static final Map<String, Long> FAILED = new ConcurrentHashMap<>();
	private static int serial = 0;
	// render thread only
	private static final Map<String, Preview> READY = new LinkedHashMap<String, Preview>(128, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Preview> e) {
			if (size() > MAX_TEXTURES) {
				Minecraft.getMinecraft().getTextureManager().deleteTexture(e.getValue().id());
				return true;
			}
			return false;
		}
	};
	private static final ThreadPoolExecutor POOL = new ThreadPoolExecutor(3, 3, 30, TimeUnit.SECONDS,
			new ArrayBlockingQueue<Runnable>(12), new ThreadFactory() {
				@Override
				public Thread newThread(Runnable r) {
					Thread t = new Thread(r, "origin-pack-preview");
					t.setDaemon(true);
					return t;
				}
			});

	static {
		POOL.allowCoreThreadTimeOut(true);
	}

	/** A Modrinth image (render thread). Null while loading or if it can't load. */
	public static Preview remote(final String url) {
		if (url == null || url.isEmpty() || !ModrinthPacks.trustedImage(url)) {
			return null;
		}
		return get("u:" + url, new Source() {
			@Override
			public byte[] bytes() throws Exception {
				return fetch(url);
			}
		}, false);
	}

	/**
	 * An installed pack's pack.png (render thread). key changes when the file does
	 * (callers pass name + size), so a replaced pack shows its new icon.
	 */
	public static Preview local(final Path pack, final boolean folder, String key) {
		return get("l:" + key, new Source() {
			@Override
			public byte[] bytes() throws Exception {
				return folder ? readFolderIcon(pack) : readZipIcon(pack);
			}
		}, true);
	}

	private interface Source {
		byte[] bytes() throws Exception;
	}

	private static Preview get(final String key, final Source src, final boolean pixelArt) {
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
				POOL.execute(new Runnable() {
					@Override
					public void run() {
						load(key, src, pixelArt);
					}
				});
			} catch (RejectedExecutionException full) {
				INFLIGHT.remove(key); // asked again next frame if still on screen
			}
		}
		return null;
	}

	private static void load(final String key, Source src, final boolean pixelArt) {
		try {
			byte[] bytes = src.bytes();
			BufferedImage bi = bytes == null ? null : decode(bytes);
			if (bi == null) {
				fail(key);
				return;
			}
			final BufferedImage img = toArgb(downscale(bi));
			Minecraft.getMinecraft().addScheduledTask(new Runnable() {
				@Override
				public void run() {
					try {
						ResourceLocation id = new ResourceLocation("originclient", "pack_preview/" + (serial++));
						// pack.png is pixel art: keep it crisp (NEAREST); screenshots read better smoothed
						if (Gfx.registerTexture(id, img, !pixelArt) != null) {
							READY.put(key, new Preview(id, img.getWidth(), img.getHeight()));
						} else {
							FAILED.put(key, System.currentTimeMillis());
						}
					} catch (Throwable t) {
						FAILED.put(key, System.currentTimeMillis());
					} finally {
						INFLIGHT.remove(key);
					}
				}
			});
		} catch (Throwable t) {
			fail(key);
		}
	}

	/**
	 * Decodes only images whose header declares a sane size: a few KB of PNG can
	 * claim 50000x50000 px and exhaust the heap (pack.png comes from any zip).
	 */
	private static BufferedImage decode(byte[] bytes) throws java.io.IOException {
		ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes));
		if (in == null) {
			return null;
		}
		try {
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
		} finally {
			in.close(); // 1.8.9: Java 8's ImageInputStream is not AutoCloseable
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
			byte[] b = PackFiles.readUpTo(in, MAX_IMAGE_BYTES + 1);
			return b.length > MAX_IMAGE_BYTES ? null : b;
		}
	}

	private static byte[] readZipIcon(Path zip) throws Exception {
		try (ZipFile zf = new ZipFile(zip.toFile())) {
			ZipEntry e = zf.getEntry("pack.png");
			if (e == null || e.getSize() > MAX_IMAGE_BYTES) {
				return null;
			}
			try (InputStream in = zf.getInputStream(e)) {
				byte[] b = PackFiles.readUpTo(in, MAX_IMAGE_BYTES + 1);
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

	/** 1.8.9: DynamicTexture(BufferedImage) reads getRGB, so any image type works; ARGB keeps alpha exact. */
	private static BufferedImage toArgb(BufferedImage bi) {
		if (bi.getType() == BufferedImage.TYPE_INT_ARGB) {
			return bi;
		}
		BufferedImage out = new BufferedImage(bi.getWidth(), bi.getHeight(), BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		g.drawImage(bi, 0, 0, null);
		g.dispose();
		return out;
	}
}
