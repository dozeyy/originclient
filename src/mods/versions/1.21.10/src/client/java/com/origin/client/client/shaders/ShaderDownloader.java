package com.origin.client.client.shaders;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
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
	 *
	 * SHADER-2: when it is the pack Iris has selected, shaders are switched off first,
	 * so the world stops rendering a pack that is gone, Iris's config stops naming it,
	 * and Iris lets go of the file. If the file still can't be deleted the row stays
	 * Installed (the truth) and removeNotice() tells the player what to do.
	 */
	public static void remove(String slug) {
		State s = state(slug);
		if (s.status() != Status.DONE) {
			return;
		}
		String fileName;
		try {
			// message() is the filename; strip any path parts defensively
			fileName = Path.of(s.message()).getFileName().toString();
		} catch (Throwable t) {
			return;
		}
		if (fileName.equals(IrisBridge.selectedPack())) {
			IrisBridge.apply(null);
		}
		try {
			Files.deleteIfExists(IrisBridge.shaderpacksDir().resolve(fileName));
		} catch (Throwable t) {
			com.origin.client.OriginClient.LOGGER.warn("Shader remove failed for {}", slug, t);
			removeNotice = "Couldn't remove " + fileName
					+ ": close the game and delete it from the shaderpacks folder";
			return;
		}
		removeNotice = null;
		STATES.remove(slug);
		writeIndex(slug);
	}

	// Why the last Remove left a pack installed (shown in the browser's header); null = nothing to say.
	private static volatile String removeNotice;

	public static String removeNotice() {
		return removeNotice;
	}

	/**
	 * Reconciles in-memory DONE states with the shaderpacks/ folder: if a pack's
	 * file was deleted outside the client (e.g. the player cleaned out the
	 * folder), drop it back to IDLE so the row shows Download again. Called when
	 * the browser screen (re)opens.
	 */
	public static void syncWithDisk() {
		removeNotice = null;
		Path dir = IrisBridge.shaderpacksDir();
		// Packs installed in an earlier session: the index remembers which file each
		// download wrote, so a row whose file is still there shows Installed again.
		for (var e : readIndex().entrySet()) {
			if (state(e.getKey()).status() != Status.IDLE) {
				continue;
			}
			try {
				String fileName = Path.of(e.getValue()).getFileName().toString();
				if (!fileName.isBlank() && Files.exists(dir.resolve(fileName))) {
					STATES.put(e.getKey(), new State(Status.DONE, 1, fileName));
				}
			} catch (Throwable ignored) {
				// unparseable entry -- the row just shows Download
			}
		}
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
		// No write here: a pack missing from THIS instance's folder may be installed in
		// another one sharing the list (Settings Sync); only Remove drops an entry.
	}

	// ---- installed-pack index (config/originclient-shaders.json: slug -> file name) ----
	// Without it the states were memory-only, so after a restart every downloaded pack
	// offered Download again. Reads and writes fail soft: a broken index only loses
	// the Installed badge, never the packs themselves.
	// What the file holds, as last read or written (null = not read yet). syncWithDisk
	// runs on the render thread every time the browser opens: it reads the file once
	// per session and never writes; a download adds an entry, Remove drops one.
	private static Map<String, String> indexCache = null;

	private static Path indexFile() {
		return FabricLoader.getInstance().getConfigDir().resolve("originclient-shaders.json");
	}

	private static synchronized Map<String, String> readIndex() {
		if (indexCache != null) {
			return indexCache;
		}
		Map<String, String> out = new java.util.HashMap<>();
		try {
			Path f = indexFile();
			if (Files.exists(f)) {
				JsonObject o = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), JsonObject.class);
				if (o != null) {
					for (var e : o.entrySet()) {
						if (e.getValue().isJsonPrimitive()) {
							out.put(e.getKey(), e.getValue().getAsString());
						}
					}
				}
			}
		} catch (Throwable t) {
			com.origin.client.OriginClient.LOGGER.warn("Could not read the installed shader list", t);
		}
		indexCache = out;
		return out;
	}

	/** Saves the list: every entry already in it (other instances' packs included),
	 *  plus this session's installs, minus `removedSlug` (a Remove; null otherwise). */
	private static synchronized void writeIndex(String removedSlug) {
		Map<String, String> now = new java.util.HashMap<>(readIndex());
		for (var e : STATES.entrySet()) {
			if (e.getValue().status() == Status.DONE) {
				now.put(e.getKey(), e.getValue().message());
			}
		}
		if (removedSlug != null) {
			now.remove(removedSlug);
		}
		if (now.equals(readIndex())) {
			return;   // nothing installed or removed since the file was last read/written
		}
		try {
			JsonObject o = new JsonObject();
			now.forEach(o::addProperty);
			Path f = indexFile();
			Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
			Files.writeString(tmp, GSON.toJson(o), StandardCharsets.UTF_8);
			try {
				Files.move(tmp, f, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException ignored) {
				Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
			}
			indexCache = now;
		} catch (Throwable t) {
			com.origin.client.OriginClient.LOGGER.warn("Could not save the installed shader list", t);
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

	/**
	 * A pack the player just downloaded is switched on (JG-7: Download used to stop at
	 * Installed, and the pack then had to be found in the shader list and applied by hand).
	 * On the game thread, where the shader pipeline is rebuilt. Fail-soft: when shaders
	 * can't take it (no shader mod, a pack the loader refuses) the pack simply stays
	 * installed and the shader list still offers it; the pack loader's own checks and
	 * Origin's compatibility warning still run on it.
	 */
	private static void use(String name) {
		net.minecraft.client.Minecraft.getInstance().execute(() -> {
			if (IrisBridge.installed() && !IrisBridge.apply(name)) {
				com.origin.client.OriginClient.LOGGER.warn("Origin: couldn't switch on the downloaded shader pack {}", name);
			}
			// SHADER-4: Iris's screen, if open, would write its old state back on Done.
			IrisBridge.refreshIrisScreen();
		});
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
			// SEC-4: only Modrinth's own https hosts, and only bytes whose SHA-512 Modrinth's API
			// published (the pack hub, 1.8.9's pinned packs and the launcher all check theirs).
			String sha512 = sha512Of(file);
			if (!trustedUrl(url) || sha512 == null) {
				STATES.put(slug, new State(Status.ERROR, 0, "Download not verified"));
				return;
			}
			// Treat API metadata as untrusted input. Only the final path component
			// may be written beneath shaderpacks/.
			String raw = file.get("filename").getAsString();
			String name = java.nio.file.Paths.get(raw).getFileName().toString();
			if (name.trim().isEmpty() || !name.equals(raw) || name.equals(".") || name.equals("..")) {
				throw new IllegalArgumentException("Shader response contained an unsafe filename");
			}

			Path dir = IrisBridge.shaderpacksDir();
			Files.createDirectories(dir);
			Path dest = dir.resolve(name);
			if (Files.exists(dest)) {
				STATES.put(slug, new State(Status.DONE, 1, name));
				use(name);   // switch it on
				writeIndex(null);
				return;
			}

			HttpURLConnection conn = open(url);
			int code = conn.getResponseCode();
			if (code < 200 || code >= 300 || !trustedUrl(conn.getURL().toString())) {
				conn.disconnect();
				throw new IllegalStateException("Shader download refused: HTTP " + code + " from " + conn.getURL().getHost());
			}
			long total = conn.getContentLengthLong();
			java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-512");
			Path tmp = dir.resolve(name + ".part");
			long read = 0;
			try (InputStream in = conn.getInputStream(); OutputStream out = Files.newOutputStream(tmp)) {
				byte[] buf = new byte[16384];
				int n;
				while ((n = in.read(buf)) > 0) {
					out.write(buf, 0, n);
					md.update(buf, 0, n);
					read += n;
					// Show progress even when the server sends no Content-Length: still
					// flip the row to "Downloading…" (progress 0) so it never freezes on
					// "Resolving…" (bug-audit #8). A known length drives the exact bar.
					STATES.put(slug, new State(Status.WORKING,
							total > 0 ? (float) ((double) read / total) : 0f, "Downloading..."));
				}
			} catch (Throwable t) {
				// Never leave a half-written <pack>.part lingering in shaderpacks/ on a
				// mid-stream failure (network drop) — delete it, then rethrow to the
				// outer handler which reports ERROR. (Bug-audit #8.)
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
			if (!hex(md.digest()).equalsIgnoreCase(sha512)) {
				Files.deleteIfExists(tmp);   // never let bytes Modrinth didn't publish into shaderpacks/
				com.origin.client.OriginClient.LOGGER.warn("Shader download for {} did not match Modrinth's SHA-512", slug);
				STATES.put(slug, new State(Status.ERROR, 0, "Download damaged - try again"));
				return;
			}
			try {
				Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException ignored) {
				Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
			}
			STATES.put(slug, new State(Status.DONE, 1, name));
			use(name);   // switch it on
			writeIndex(null);
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

	/** https on modrinth.com or one of its subdomains (cdn.modrinth.com serves every file). */
	private static boolean trustedUrl(String url) {
		try {
			URI u = URI.create(url);
			String host = u.getHost() == null ? "" : u.getHost().toLowerCase(java.util.Locale.ROOT);
			return "https".equalsIgnoreCase(u.getScheme()) && (host.equals("modrinth.com") || host.endsWith(".modrinth.com"));
		} catch (RuntimeException e) {
			return false;
		}
	}

	/** The file's SHA-512 from Modrinth's API (hex), or null when it published none: such a file is refused. */
	private static String sha512Of(JsonObject file) {
		try {
			JsonObject h = file.getAsJsonObject("hashes");
			String v = h == null || !h.has("sha512") || h.get("sha512").isJsonNull() ? null : h.get("sha512").getAsString();
			return v != null && v.matches("[0-9a-fA-F]{128}") ? v : null;
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static String hex(byte[] b) {
		StringBuilder sb = new StringBuilder(b.length * 2);
		for (byte x : b) {
			sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
		}
		return sb.toString();
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
