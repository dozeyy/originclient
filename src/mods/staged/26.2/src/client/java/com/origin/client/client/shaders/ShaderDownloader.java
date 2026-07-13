package com.origin.client.client.shaders;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// In-game shaderpack downloads: resolves the correct build for the RUNNING
// game version (+ iris loader) from Modrinth's API and streams it straight
// into shaderpacks/ with live progress — no browser, no version picking.
// All work happens on daemon threads; the UI polls state() every frame.
public final class ShaderDownloader {
	private ShaderDownloader() {
	}

	public enum Status { IDLE, WORKING, DONE, ERROR }

	public record State(Status status, float progress, String message) {
	}

	private static final State IDLE_STATE = new State(Status.IDLE, 0, "");
	private static final Map<String, State> STATES = new ConcurrentHashMap<>();
	private static final Gson GSON = new Gson();
	private static final String UA = "OriginClient (will@willhenry.me)";

	public static State state(String slug) {
		return STATES.getOrDefault(slug, IDLE_STATE);
	}

	/**
	 * Deletes an installed pack's file from shaderpacks/ and resets it to IDLE
	 * (so the row shows Download again). No-op unless the pack is DONE — its
	 * State.message() holds the downloaded filename.
	 */
	public static void remove(String slug) {
		State s = state(slug);
		if (s.status() != Status.DONE) {
			return;
		}
		try {
			// message() is the filename; strip any path parts defensively
			String fileName = Path.of(s.message()).getFileName().toString();
			Files.deleteIfExists(IrisBridge.shaderpacksDir().resolve(fileName));
		} catch (Throwable t) {
			com.origin.client.OriginClient.LOGGER.warn("Shader remove failed for {}", slug, t);
		}
		STATES.remove(slug);
	}

	/**
	 * Reconciles in-memory DONE states with the shaderpacks/ folder: if a pack's
	 * file was deleted outside the client (e.g. the player cleaned out the
	 * folder), drop it back to IDLE so the row shows Download again. Called when
	 * the browser screen (re)opens.
	 */
	public static void syncWithDisk() {
		Path dir = IrisBridge.shaderpacksDir();
		for (var e : STATES.entrySet()) {
			if (e.getValue().status() != Status.DONE) {
				continue;
			}
			try {
				String fileName = Path.of(e.getValue().message()).getFileName().toString();
				if (!Files.exists(dir.resolve(fileName))) {
					STATES.remove(e.getKey());
				}
			} catch (Throwable ignored) {
				// unparseable filename — leave the state as-is
			}
		}
	}

	/** Kicks off resolve+download for a pack; safe to call repeatedly. */
	public static void start(String slug, String gameVersion) {
		State s = state(slug);
		if (s.status() == Status.WORKING || s.status() == Status.DONE) {
			return;
		}
		STATES.put(slug, new State(Status.WORKING, 0, "Resolving..."));
		Thread t = new Thread(() -> run(slug, gameVersion), "origin-shader-dl-" + slug);
		t.setDaemon(true);
		t.start();
	}

	private static void run(String slug, String gameVersion) {
		try {
			// resolve: prefer an iris build for this exact game version, fall
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
			String name = file.get("filename").getAsString();

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
					if (total > 0) {
						STATES.put(slug, new State(Status.WORKING, (float) ((double) read / total), "Downloading..."));
					}
				}
			}
			Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
			STATES.put(slug, new State(Status.DONE, 1, name));
		} catch (Throwable t) {
			com.origin.client.OriginClient.LOGGER.warn("Shader download failed for {}", slug, t);
			STATES.put(slug, new State(Status.ERROR, 0, "Download failed"));
		}
	}

	private static JsonObject resolveFile(String slug, String gameVersion, boolean irisOnly) throws Exception {
		String url = "https://api.modrinth.com/v2/project/" + slug + "/version"
				+ "?game_versions=" + URLEncoder.encode("[\"" + gameVersion + "\"]", StandardCharsets.UTF_8)
				+ (irisOnly ? "&loaders=" + URLEncoder.encode("[\"iris\"]", StandardCharsets.UTF_8) : "");
		HttpURLConnection conn = open(url);
		JsonArray versions;
		try (InputStream in = conn.getInputStream()) {
			versions = GSON.fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8), JsonArray.class);
		}
		if (versions == null || versions.isEmpty()) {
			return null;
		}
		// newest release first; fall back to the newest of any type
		JsonObject pick = null;
		for (var el : versions) {
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
		if (files == null || files.isEmpty()) {
			return null;
		}
		for (var el : files) {
			JsonObject f = el.getAsJsonObject();
			if (f.get("primary").getAsBoolean()) {
				return f;
			}
		}
		return files.get(0).getAsJsonObject();
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
