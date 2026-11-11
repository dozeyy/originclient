package com.origin.client.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Dev-only (ad capture, branch claude/ad-capture): records the game's own finished frame (world + HUD + screens, before
 * it reaches the window, so the OS pointer is never in it) into an ffmpeg process as constant-frame-rate video.
 *
 * <p>Frames are wall-clock paced like a screen recorder: frame n of the file is the newest rendered frame at
 * {@code start + n / fps}. A frame is read back only when it starts a new slot (no wasted readbacks), through three
 * pixel-pack buffers so the read never stalls the GPU; the copy is mapped two frames later. A slot the game skipped
 * (a hitch) repeats the previous frame, so the clip keeps real time and its FPS readouts stay honest.
 * Called from DevAdMinecraftMixin right before the main target is blitted to the window.
 */
public final class OriginDevAdCapture {
	private static final int SLOTS = 3;

	private static Process ffmpeg;
	private static OutputStream pipe;
	private static Thread writer;
	private static BlockingQueue<byte[]> queue;
	private static final List<byte[]> pool = new ArrayList<>();
	private static int[] pbo;
	private static long[] pboTime;
	private static boolean[] pboFull;
	private static int w, h, fps, ring;
	private static long t0;
	private static long lastSlot = -1;
	private static long written;
	private static byte[] last;
	private static volatile boolean failed;
	private static String path;

	private OriginDevAdCapture() {
	}

	public static boolean recording() {
		return ffmpeg != null;
	}

	/** Seconds since the recording started (the clip's own clock). */
	public static double seconds() {
		return ffmpeg == null ? 0 : (System.nanoTime() - t0) / 1e9;
	}

	public static long framesWritten() {
		return written;
	}

	/** Starts ffmpeg writing {@code out} (H.264, near-lossless; the edit re-encodes). */
	public static void start(String out, int framesPerSecond) throws IOException {
		RenderTarget rt = Minecraft.getInstance().getMainRenderTarget();
		w = rt.width;
		h = rt.height;
		fps = framesPerSecond;
		path = out;
		new File(out).getParentFile().mkdirs();
		// ORIGIN_AD_LOSSLESS=1: RGB lossless (no chroma subsampling), for clips keyed off a flat background later
		List<String> enc = "1".equals(System.getenv("ORIGIN_AD_LOSSLESS"))
				? List.of("-c:v", "libx264rgb", "-preset", "ultrafast", "-qp", "0", "-pix_fmt", "bgr0")
				: List.of("-c:v", "libx264", "-preset", "veryfast", "-crf", "12", "-pix_fmt", "yuv420p");
		List<String> cmd = new java.util.ArrayList<>(List.of("ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
				"-f", "rawvideo", "-pix_fmt", "bgra", "-s", w + "x" + h, "-r", String.valueOf(fps), "-i", "-", "-vf", "vflip"));
		cmd.addAll(enc);
		cmd.addAll(List.of("-movflags", "+faststart", out));
		ffmpeg = new ProcessBuilder(cmd).redirectErrorStream(true)
				.redirectOutput(new File(out + ".ffmpeg.log")).start();
		pipe = ffmpeg.getOutputStream();
		queue = new ArrayBlockingQueue<>(8);
		pool.clear();
		failed = false;
		writer = new Thread(OriginDevAdCapture::drain, "Origin ad capture writer");
		writer.setDaemon(true);
		writer.start();
		pbo = new int[SLOTS];
		pboTime = new long[SLOTS];
		pboFull = new boolean[SLOTS];
		for (int i = 0; i < SLOTS; i++) {
			pbo[i] = GL15.glGenBuffers();
			GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo[i]);
			GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, (long) w * h * 4, GL15.GL_STREAM_READ);
		}
		GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
		ring = 0;
		lastSlot = -1;
		written = 0;
		last = null;
		t0 = System.nanoTime();
		OriginDevAd.log("capture: recording " + w + "x" + h + " @" + fps + " -> " + out);
	}

	/** Render thread, once per frame, with the frame fully drawn into the main target. */
	public static void onFrame() {
		if (ffmpeg == null) {
			return;
		}
		RenderTarget rt = Minecraft.getInstance().getMainRenderTarget();
		if (rt.width != w || rt.height != h) {
			OriginDevAd.log("capture: target is " + rt.width + "x" + rt.height + ", expected " + w + "x" + h + "; frame skipped");
			return;
		}
		long now = System.nanoTime();
		long slot = (long) Math.floor((now - t0) / 1e9 * fps);
		// Map the oldest buffer first (it was read two frames ago), then read this frame into it.
		int i = ring;
		if (pboFull[i]) {
			emit(i);
		}
		if (slot <= lastSlot) {
			return;   // still inside the previous frame slot: nothing to read
		}
		lastSlot = slot;
		int prevRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
		GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, rt.frameBufferId);
		GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
		GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo[i]);
		GL11.glReadPixels(0, 0, w, h, org.lwjgl.opengl.GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, 0L);
		GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
		GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
		pboTime[i] = slot;
		pboFull[i] = true;
		ring = (ring + 1) % SLOTS;
	}

	/** Copies buffer i out and queues it for every frame slot up to the one it was read in. */
	private static void emit(int i) {
		GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo[i]);
		ByteBuffer mapped = GL15.glMapBuffer(GL21.GL_PIXEL_PACK_BUFFER, GL15.GL_READ_ONLY);
		if (mapped != null) {
			byte[] frame = take();
			mapped.get(frame);
			GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
			// Slots between the previous written frame and this one were skipped by the game: repeat the previous frame.
			while (written < pboTime[i] && last != null) {
				put(last);
				written++;
			}
			put(frame);
			written = pboTime[i] + 1;
			last = frame;
		}
		GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
		pboFull[i] = false;
	}

	private static byte[] take() {
		synchronized (pool) {
			if (!pool.isEmpty()) {
				return pool.remove(pool.size() - 1);
			}
		}
		return new byte[w * h * 4];
	}

	private static void put(byte[] frame) {
		if (failed) {
			return;
		}
		try {
			queue.put(frame);   // back-pressure: a full queue slows the game, never drops a frame
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static void drain() {
		byte[] prev = null;
		try {
			while (true) {
				byte[] f = queue.take();
				if (f.length == 0) {
					break;
				}
				pipe.write(f);
				// A frame may be queued twice in a row (a repeat); only recycle it once it is no longer the newest.
				if (prev != null && prev != f && prev != last) {
					synchronized (pool) {
						if (pool.size() < 6) {
							pool.add(prev);
						}
					}
				}
				prev = f;
			}
		} catch (Throwable t) {
			failed = true;
			OriginDevAd.log("capture: writer failed: " + t);
		}
	}

	/** Flushes the buffers still in flight, closes ffmpeg and waits for the file. */
	public static void stop() {
		if (ffmpeg == null) {
			return;
		}
		for (int k = 0; k < SLOTS; k++) {
			int i = (ring + k) % SLOTS;
			if (pboFull[i]) {
				emit(i);
			}
		}
		for (int id : pbo) {
			GL15.glDeleteBuffers(id);
		}
		try {
			queue.put(new byte[0]);
			writer.join(60_000);
			pipe.close();
			boolean ok = ffmpeg.waitFor(120, java.util.concurrent.TimeUnit.SECONDS);
			OriginDevAd.log("capture: stopped, " + written + " frames (" + String.format(java.util.Locale.ROOT, "%.2f", written / (double) fps)
					+ " s), ffmpeg " + (ok ? "exit " + ffmpeg.exitValue() : "still running") + (failed ? ", WRITER FAILED" : "") + " -> " + path);
		} catch (Exception e) {
			OriginDevAd.log("capture: stop failed: " + e);
		}
		ffmpeg = null;
		pipe = null;
	}
}
