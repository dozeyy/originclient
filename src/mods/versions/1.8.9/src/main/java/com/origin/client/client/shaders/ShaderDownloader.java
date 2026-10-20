package com.origin.client.client.shaders;

import com.origin.client.client.net.OriginHttp;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// In-game shaderpack downloads: streams one pinned, allowlisted file
// (ShaderCompat.ALLOWED) straight into shaderpacks/ with live progress and a
// SHA-256 check -- no browser, no version picking.
// All work happens on daemon threads; the UI polls state() every frame.
// 1.8.9: shaderpacks/ is the folder OptiFine scans and HTTPS goes through
// OriginHttp (jre-legacy's CA store predates the roots Modrinth's CDN chains to).
// Only packs known to run on OptiFine 1.8.9 are downloadable (ShaderCompat).
public final class ShaderDownloader {
	private ShaderDownloader() {
	}

	public enum Status { IDLE, WORKING, DONE, ERROR }

	// download state snapshot (Java-8 final class, was a record).
	public static final class State {
		private final Status status; private final float progress; private final String message;
		public State(Status status, float progress, String message) { this.status = status; this.progress = progress; this.message = message; }
		public Status status() { return status; }
		public float progress() { return progress; }
		public String message() { return message; }
	}

	private static final State IDLE_STATE = new State(Status.IDLE, 0, "");
	private static final Map<String, State> STATES = new ConcurrentHashMap<>();
	private static final String UA = "OriginClient (will@willhenry.me)";
	public static State state(String slug) {
		return STATES.getOrDefault(slug, IDLE_STATE);
	}

	/**
	 * Deletes an installed pack's file from shaderpacks/ and resets it to IDLE
	 * (so the row shows Download again). No-op unless the pack is DONE -- its
	 * State.message() holds the downloaded filename.
	 */
	public static void remove(String slug) {
		State s = state(slug);
		if (s.status() != Status.DONE) {
			return;
		}
		try {
			// message() is the filename; strip any path parts defensively
			String fileName = java.nio.file.Paths.get(s.message()).getFileName().toString();
			Files.deleteIfExists(IrisBridge.shaderpacksDir().resolve(fileName));
		} catch (Throwable t) {
			com.origin.client.OriginClient.LOGGER.warn("Shader remove failed for " + slug, t);
		}
		STATES.remove(slug);
	}

	/**
	 * Reconciles in-memory DONE states with the shaderpacks/ folder: a pack whose
	 * file was deleted outside the client drops back to IDLE. Called when the
	 * browser screen (re)opens.
	 */
	public static void syncWithDisk() {
		Path dir = IrisBridge.shaderpacksDir();
		for (Map.Entry<String, State> e : STATES.entrySet()) {
			if (e.getValue().status() != Status.DONE) {
				continue;
			}
			try {
				String fileName = java.nio.file.Paths.get(e.getValue().message()).getFileName().toString();
				if (!Files.exists(dir.resolve(fileName))) {
					STATES.remove(e.getKey());
				}
			} catch (Throwable ignored) {
				// unparseable filename -- leave the state as-is
			}
		}
	}

	/**
	 * Kicks off the download of one allowlisted pack; safe to call repeatedly.
	 * Only ShaderCompat.ALLOWED rows can be started: the file, URL and SHA-256 are
	 * pinned there, nothing is resolved from "latest".
	 */
	public static void start(final ShaderCompat.Pack pack) {
		State s = state(pack.id);
		if (s.status() == Status.WORKING || s.status() == Status.DONE) {
			return;
		}
		STATES.put(pack.id, new State(Status.WORKING, 0, "Starting..."));
		Thread t = new Thread(new Runnable() {
			@Override
			public void run() {
				ShaderDownloader.run(pack);
			}
		}, "origin-shader-dl-" + pack.id);
		t.setDaemon(true);
		t.start();
	}

	private static void run(ShaderCompat.Pack pack) {
		String id = pack.id;
		try {
			// The name is ours (pinned), but keep only the last path component anyway.
			String name = java.nio.file.Paths.get(pack.fileName).getFileName().toString();
			Path dir = IrisBridge.shaderpacksDir();
			Files.createDirectories(dir);
			Path dest = dir.resolve(name);
			if (Files.exists(dest)) {
				STATES.put(id, new State(Status.DONE, 1, name));
				return;
			}

			HttpURLConnection conn = open(pack.url);
			long total = conn.getContentLengthLong();
			Path tmp = dir.resolve(name + ".part");
			long read = 0;
			MessageDigest sha = MessageDigest.getInstance("SHA-256");
			try (InputStream in = conn.getInputStream(); OutputStream out = Files.newOutputStream(tmp)) {
				byte[] buf = new byte[16384];
				int n;
				while ((n = in.read(buf)) > 0) {
					out.write(buf, 0, n);
					sha.update(buf, 0, n);
					read += n;
					STATES.put(id, new State(Status.WORKING,
							total > 0 ? (float) ((double) read / total) : 0f, "Downloading..."));
				}
			} catch (Throwable t) {
				// Never leave a half-written <pack>.part in shaderpacks/.
				try {
					Files.deleteIfExists(tmp);
				} catch (Throwable ignored) {
				}
				throw t;
			}
			if (total > 0 && read != total) {
				Files.deleteIfExists(tmp);
				throw new IllegalStateException("Incomplete shader download: " + read + " of " + total + " bytes");
			}
			// Pinned file: the bytes must be exactly the ones that were checked.
			String got = hex(sha.digest());
			if (!got.equalsIgnoreCase(pack.sha256)) {
				Files.deleteIfExists(tmp);
				throw new IllegalStateException("Shader checksum mismatch for " + id + ": " + got);
			}
			try {
				Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException ignored) {
				Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
			}
			STATES.put(id, new State(Status.DONE, 1, name));
		} catch (Throwable t) {
			com.origin.client.OriginClient.LOGGER.warn("Shader download failed for " + id, t);
			STATES.put(id, new State(Status.ERROR, 0, "Download failed"));
		}
	}

	private static String hex(byte[] b) {
		StringBuilder sb = new StringBuilder(b.length * 2);
		for (byte x : b) {
			sb.append(String.format("%02x", x & 0xff));
		}
		return sb.toString();
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
