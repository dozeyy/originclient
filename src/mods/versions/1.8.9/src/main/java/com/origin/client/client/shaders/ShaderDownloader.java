package com.origin.client.client.shaders;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.origin.client.client.net.OriginHttp;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// In-game shaderpack downloads: resolves the correct build for the RUNNING
// game version (+ the shader loader) from Modrinth's API and streams it straight
// into shaderpacks/ with live progress -- no browser, no version picking.
// All work happens on daemon threads; the UI polls state() every frame.
// 1.8.9: the loader is OptiFine (Modrinth tags OptiFine builds "optifine"),
// shaderpacks/ is the folder OptiFine scans, HTTPS goes through OriginHttp
// (jre-legacy's CA store predates the roots Modrinth's CDN chains to), and JSON
// is parsed with Gson 2.2.4's JsonParser.
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
	/** Modrinth's loader tag for OptiFine shader builds. */
	private static final String LOADER = "optifine";

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

	/** Kicks off resolve+download for a pack; safe to call repeatedly. */
	public static void start(final String slug, final String gameVersion) {
		State s = state(slug);
		if (s.status() == Status.WORKING || s.status() == Status.DONE) {
			return;
		}
		STATES.put(slug, new State(Status.WORKING, 0, "Resolving..."));
		Thread t = new Thread(new Runnable() {
			@Override
			public void run() {
				ShaderDownloader.run(slug, gameVersion);
			}
		}, "origin-shader-dl-" + slug);
		t.setDaemon(true);
		t.start();
	}

	private static void run(String slug, String gameVersion) {
		try {
			// resolve: prefer an OptiFine build for this exact game version, fall
			// back to any-loader for the version (some packs tag loosely)
			JsonObject file = resolveFile(slug, gameVersion, true);
			if (file == null) {
				file = resolveFile(slug, gameVersion, false);
			}
			if (file == null) {
				STATES.put(slug, new State(Status.ERROR, 0, "No " + gameVersion + " build"));
				return;
			}
			String url = file.get("url").getAsString();
			// Treat API metadata as untrusted input. Only the final path component
			// may be written beneath shaderpacks/.
			String name = java.nio.file.Paths.get(file.get("filename").getAsString()).getFileName().toString();
			if (name.trim().isEmpty()) {
				throw new IllegalArgumentException("Shader response contained an empty filename");
			}

			Path dir = IrisBridge.shaderpacksDir();
			Files.createDirectories(dir);
			Path dest = dir.resolve(name);
			if (Files.exists(dest)) {
				STATES.put(slug, new State(Status.DONE, 1, name));
				return;
			}

			HttpURLConnection conn = open(url);
			long total = conn.getContentLengthLong();
			Path tmp = dir.resolve(name + ".part");
			long read = 0;
			try (InputStream in = conn.getInputStream(); OutputStream out = Files.newOutputStream(tmp)) {
				byte[] buf = new byte[16384];
				int n;
				while ((n = in.read(buf)) > 0) {
					out.write(buf, 0, n);
					read += n;
					// Progress even without a Content-Length: still flip the row to
					// "Downloading..." so it never freezes on "Resolving...".
					STATES.put(slug, new State(Status.WORKING,
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
			try {
				Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException ignored) {
				Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
			}
			STATES.put(slug, new State(Status.DONE, 1, name));
		} catch (Throwable t) {
			com.origin.client.OriginClient.LOGGER.warn("Shader download failed for " + slug, t);
			STATES.put(slug, new State(Status.ERROR, 0, "Download failed"));
		}
	}

	private static JsonObject resolveFile(String slug, String gameVersion, boolean loaderOnly) throws Exception {
		String url = "https://api.modrinth.com/v2/project/" + slug + "/version"
				+ "?game_versions=" + URLEncoder.encode("[\"" + gameVersion + "\"]", "UTF-8")
				+ (loaderOnly ? "&loaders=" + URLEncoder.encode("[\"" + LOADER + "\"]", "UTF-8") : "");
		HttpURLConnection conn = open(url);
		JsonArray versions;
		try (InputStream in = conn.getInputStream()) {
			versions = new JsonParser().parse(OriginHttp.readBody(in)).getAsJsonArray();
		}
		if (versions == null || versions.size() == 0) {
			return null;
		}
		// newest release first; fall back to the newest of any type
		JsonObject pick = null;
		for (JsonElement el : versions) {
			JsonObject v = el.getAsJsonObject();
			if (v.get("version_type").getAsString().equals("release")) {
				pick = v;
				break;
			}
		}
		if (pick == null) {
			pick = versions.get(0).getAsJsonObject();
		}
		JsonArray files = pick.getAsJsonArray("files");
		if (files == null || files.size() == 0) {
			return null;
		}
		for (JsonElement el : files) {
			JsonObject f = el.getAsJsonObject();
			if (f.get("primary").getAsBoolean()) {
				return f;
			}
		}
		return files.get(0).getAsJsonObject();
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
