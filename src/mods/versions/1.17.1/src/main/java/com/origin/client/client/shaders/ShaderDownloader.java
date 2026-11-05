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
		updateIndex(slug, null);
	}

	/**
	 * Reconciles in-memory DONE states with the shaderpacks/ folder: if a pack's
	 * file was deleted outside the client (e.g. the player cleaned out the
	 * folder), drop it back to IDLE so the row shows Download again. Called when
	 * the browser screen (re)opens.
	 */
	public static void syncWithDisk() {
		Path dir = IrisBridge.shaderpacksDir();
		// Packs installed in an earlier session: the index remembers which file each
		// download wrote, so a row whose file is still there shows Installed again.
		for (Map.Entry<String, String> e : index().entrySet()) {
			if (state(e.getKey()).status() != Status.IDLE) {
				continue;
			}
			try {
				String fileName = java.nio.file.Paths.get(e.getValue()).getFileName().toString();
				if (!fileName.trim().isEmpty() && Files.exists(dir.resolve(fileName))) {
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
	}

	// ---- installed-pack index (config/originclient-shaders.json: slug -> file name) ----
	// Without it the states were memory-only, so after a restart every downloaded pack
	// offered Download again. Settings Sync carries this file to every instance, so it
	// only ever grows from what one instance sees: an entry whose pack isn't in THIS
	// instance's shaderpacks/ is kept (another instance may have it), and an entry is
	// dropped only by Remove. Read once per session; a change re-reads the file and edits
	// just that one entry, and nothing is written unless the entry really changed. Reads
	// and writes fail soft: a broken index only loses the Installed badge, never packs.
	private static Map<String, String> index;

	private static Path indexFile() {
		return FabricLoader.getInstance().getConfigDir().resolve("originclient-shaders.json");
	}

	private static synchronized Map<String, String> index() {
		if (index == null) {
			Map<String, String> read = readIndex();
			index = read != null ? read : new java.util.HashMap<String, String>();
		}
		return new java.util.HashMap<>(index);
	}

	/** The file's entries; empty if there's no file yet; null if it couldn't be read. */
	private static Map<String, String> readIndex() {
		Map<String, String> out = new java.util.HashMap<>();
		try {
			Path f = indexFile();
			if (Files.exists(f)) {
				JsonObject o = GSON.fromJson(new String(Files.readAllBytes(f), StandardCharsets.UTF_8), JsonObject.class);
				if (o != null) {
					for (Map.Entry<String, com.google.gson.JsonElement> e : o.entrySet()) {
						if (e.getValue().isJsonPrimitive()) {
							out.put(e.getKey(), e.getValue().getAsString());
						}
					}
				}
			}
		} catch (Throwable t) {
			com.origin.client.OriginClient.LOGGER.warn("Could not read the installed shader list", t);
			return null;
		}
		return out;
	}

	/** name = the file a download wrote; null = Remove. Writes only on a real change. */
	private static synchronized void updateIndex(String slug, String name) {
		Map<String, String> m = readIndex();
		if (m == null) {
			return;   // unreadable right now: never overwrite what we can't see
		}
		boolean same = name == null ? !m.containsKey(slug) : name.equals(m.get(slug));
		if (name == null) {
			m.remove(slug);
		} else {
			m.put(slug, name);
		}
		index = m;
		if (same) {
			return;
		}
		try {
			JsonObject o = new JsonObject();
			for (Map.Entry<String, String> e : m.entrySet()) {
				o.addProperty(e.getKey(), e.getValue());
			}
			Path f = indexFile();
			Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
			Files.write(tmp, GSON.toJson(o).getBytes(StandardCharsets.UTF_8));
			try {
				Files.move(tmp, f, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException ignored) {
				Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
			}
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
			// Treat API metadata as untrusted input. Only the final path component
			// may be written beneath shaderpacks/.
			String name = Path.of(file.get("filename").getAsString()).getFileName().toString();
			if (name.isBlank()) {
				throw new IllegalArgumentException("Shader response contained an empty filename");
			}

			Path dir = IrisBridge.shaderpacksDir();
			Files.createDirectories(dir);
			Path dest = dir.resolve(name);
			if (Files.exists(dest)) {
				STATES.put(slug, new State(Status.DONE, 1, name));
				use(name);   // switch it on
				updateIndex(slug, name);
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
			try {
				Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException ignored) {
				Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
			}
			STATES.put(slug, new State(Status.DONE, 1, name));
			use(name);   // switch it on
			updateIndex(slug, name);
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
		if (versions == null || versions.size() == 0) {
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
		if (files == null || files.size() == 0) {
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
