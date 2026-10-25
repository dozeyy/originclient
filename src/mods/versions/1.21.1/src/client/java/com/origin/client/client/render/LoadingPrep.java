package com.origin.client.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;

import java.io.InputStream;

/**
 * B-30: the startup loading picture's first frame must not decode or bake anything.
 * Its art (the official logo, box-filtered to the loading size, and Minecraft's own
 * ascii.png for the boot-font caption) and the classes it runs through are prepared
 * here on one background thread, started from the client initializer, long before
 * the loading overlay's first frame. The render thread then only uploads (one small
 * texture each).
 *
 * <p>Order-proof and fail-soft: every taker returns null when the work isn't ready
 * (or failed), and the caller then does it itself on the render thread, exactly as
 * before this existed. Each result is handed over once (the taker owns it); a result
 * nobody takes is closed when a taker gives up on it, so nothing leaks.
 *
 * <p>Pure CPU work only on the worker (PNG decode, a box filter, a width scan,
 * class loading): no GL, no Minecraft state beyond reading the window's height.
 */
public final class LoadingPrep {
	private static final String ASCII = "/assets/minecraft/textures/font/ascii.png";
	/** How long the worker waits for the game window to exist (it needs its height). */
	private static final long WINDOW_WAIT_MS = 4000;
	/** The classes the loading picture's first frame runs through: loaded (and mixin-
	 *  transformed) here instead of inside that frame. Missing ones are skipped. */
	private static final String[] CLASSES = {
			"com.origin.client.client.render.OriginScreenRenderer",
			"com.origin.client.client.render.LoadingLook",
			"com.origin.client.client.render.LoadBar",
			"com.origin.client.client.render.RingField",
			"com.origin.client.client.render.RingField$Mood",
			"com.origin.client.client.OriginAssets",
			"com.origin.client.client.OriginHandoff",
			"com.origin.client.client.StartupTransition",
			"com.origin.client.client.mixin.loading.LoadingOverlayAccessor",
			"net.minecraft.client.renderer.texture.DynamicTexture",
	};

	private static NativeImage logoSrc, logoBaked, sheet;
	private static int logoBakedPs;
	private static int[] widths;
	private static boolean started;

	private LoadingPrep() {
	}

	/** Starts the background preparation (once). Never throws. */
	public static synchronized void start() {
		if (started) {
			return;
		}
		started = true;
		try {
			Thread t = new Thread(LoadingPrep::run, "Origin loading prep");
			t.setDaemon(true);
			t.start();
		} catch (Throwable ignored) {
			// No thread: the render thread decodes on first use, as it always could.
		}
	}

	private static void run() {
		ClassLoader cl = LoadingPrep.class.getClassLoader();
		for (String c : CLASSES) {
			try {
				Class.forName(c, false, cl);
			} catch (Throwable ignored) {
				// Only a head start: the class loads on first use either way.
			}
		}
		try {
			NativeImage s = read(ASCII);
			int[] w = bootWidths(s);
			synchronized (LoadingPrep.class) {
				sheet = s;
				widths = w;
			}
		} catch (Throwable ignored) {
			// The render thread reads the sheet itself.
		}
		try {
			NativeImage src = read(com.origin.client.client.gui.OriginUi.LOGO_PATH);
			synchronized (LoadingPrep.class) {
				logoSrc = src;
			}
			int ps = waitForLogoPhys();
			if (ps > 0) {
				NativeImage baked = boxFilter(src, ps, ps);
				synchronized (LoadingPrep.class) {
					logoBaked = baked;
					logoBakedPs = ps;
				}
			}
		} catch (Throwable ignored) {
			// The render thread decodes and bakes the logo itself.
		}
	}

	/** The loading logo's size in physical px once the window exists; 0 if it never showed. */
	private static int waitForLogoPhys() throws InterruptedException {
		long end = System.currentTimeMillis() + WINDOW_WAIT_MS;
		while (System.currentTimeMillis() < end) {
			Minecraft mc = Minecraft.getInstance();
			if (mc != null && mc.getWindow() != null && mc.getWindow().getHeight() > 0) {
				return LoadingLook.logoPhys();
			}
			Thread.sleep(5);
		}
		return 0;
	}

	/** The decoded logo master, or null (not ready / failed). The caller owns it. */
	static synchronized NativeImage takeLogoSrc() {
		NativeImage s = logoSrc;
		logoSrc = null;
		return s;
	}

	/** The logo box-filtered to {@code ps} physical px, or null (not ready, failed or another size). The caller owns it. */
	static synchronized NativeImage takeLogoBaked(int ps) {
		NativeImage b = logoBaked;
		logoBaked = null;
		if (b != null && logoBakedPs != ps) {
			b.close();   // the window changed size before the first frame: bake again on demand
			return null;
		}
		return b;
	}

	/** Minecraft's ascii.png, decoded, or null. The caller owns it; {@link #takeWidths} pairs with it. */
	static synchronized NativeImage takeSheet() {
		NativeImage s = sheet;
		sheet = null;
		return s;
	}

	/** The size the worker baked the logo at (0 = none yet); dev frame log only. */
	static synchronized int preparedLogoPs() {
		return logoBakedPs;
	}

	static synchronized int[] takeWidths() {
		int[] w = widths;
		widths = null;
		return w;
	}

	static NativeImage read(String classpathResource) throws Exception {
		try (InputStream in = LoadingPrep.class.getResourceAsStream(classpathResource)) {
			if (in == null) {
				throw new java.io.FileNotFoundException("Missing asset: " + classpathResource);
			}
			return NativeImage.read(in);
		}
	}

	/**
	 * Per char code 0-255: the boot font's glyph width in sheet px, or -1 for a code
	 * the sheet has no glyph for (drawn as nothing, advances nothing).
	 *
	 * <p>A glyph's width is its rightmost column with any opaque pixel, the rule vanilla's
	 * bitmap provider uses. Only printable ASCII (0x21-0x7E) maps 1:1 onto ascii.png's
	 * cells in 1.21.1 (include/default.json: rows 2-7); rows 0-1 and 8+ are other
	 * characters, so those codes count as missing. The space has an empty cell: vanilla
	 * gives it its own 4 px advance (font/include/space.json, " ": 4), so its width here
	 * is 3 (a glyph advances width + 1). Without that the caption read "Loadingresources".
	 */
	static int[] bootWidths(NativeImage image) {
		int cell = image.getWidth() / 16;
		int[] widths = new int[256];
		java.util.Arrays.fill(widths, -1);
		for (int c = 0x21; c <= 0x7E; c++) {
			int cx = (c % 16) * cell;
			int cy = (c / 16) * cell;
			int w = 0;
			for (int x = cell - 1; x >= 0 && w == 0; x--) {
				for (int y = 0; y < cell; y++) {
					if ((image.getPixelRGBA(cx + x, cy + y) >>> 24) != 0) {
						w = x + 1;
						break;
					}
				}
			}
			widths[c] = w;
		}
		widths[' '] = 4 * cell / 8 - 1;   // vanilla's 4 px advance, in sheet px
		return widths;
	}

	/** Premultiplied 4x4 box-filter downsample of {@code src} to dw x dh (a new image; CPU only). */
	static NativeImage boxFilter(NativeImage src, int dw, int dh) {
		int sw = src.getWidth(), sh = src.getHeight();
		NativeImage dst = new NativeImage(dw, dh, true);
		for (int y = 0; y < dh; y++) {
			for (int x = 0; x < dw; x++) {
				double aSum = 0, rSum = 0, gSum = 0, bSum = 0;
				for (int sy = 0; sy < 4; sy++) {
					for (int sx = 0; sx < 4; sx++) {
						int tx = Math.min(sw - 1, (int) ((x + (sx + 0.5) / 4.0) * sw / dw));
						int ty = Math.min(sh - 1, (int) ((y + (sy + 0.5) / 4.0) * sh / dh));
						int c = src.getPixelRGBA(tx, ty);
						double a = ((c >>> 24) & 0xFF) / 255.0;
						aSum += a;
						rSum += a * ((c & 0xFF) / 255.0);
						gSum += a * (((c >> 8) & 0xFF) / 255.0);
						bSum += a * (((c >> 16) & 0xFF) / 255.0);
					}
				}
				double a = aSum / 16.0;
				int r = a > 0 ? (int) Math.round(Math.min(1.0, rSum / aSum) * 255) : 255;
				int gg = a > 0 ? (int) Math.round(Math.min(1.0, gSum / aSum) * 255) : 255;
				int b = a > 0 ? (int) Math.round(Math.min(1.0, bSum / aSum) * 255) : 255;
				// NativeImage ABGR: alpha, blue, green, red.
				dst.setPixelRGBA(x, y, ((int) Math.round(a * 255) << 24) | (b << 16) | (gg << 8) | r);
			}
		}
		return dst;
	}
}
