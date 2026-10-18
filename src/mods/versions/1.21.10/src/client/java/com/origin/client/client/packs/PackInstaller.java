package com.origin.client.client.packs;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

// Downloads + installs one Modrinth resource pack into resourcepacks/:
//   resolve the newest build for THIS game version -> download to a .part file
//   (never a .zip name, so Minecraft's folder scan ignores it) with live progress
//   and cancel -> verify Modrinth's sha512/sha1 -> check it is a loadable pack
//   (PackFiles.inspect) -> move it in under a name that does not exist yet.
// Existing packs are never overwritten or deleted here; an identical file that
// is already installed is recognised by hash instead of being downloaded twice.
// No Minecraft classes, so the whole pipeline is unit-tested with a local
// server (tools/packhub-tests). The UI polls state() every frame.
public final class PackInstaller {
	public enum Status { IDLE, WORKING, DONE, ERROR, CANCELLED }

	/**
	 * fileName = the installed file (DONE); replaces = the file an update supersedes;
	 * oldFormat = the pack's pack.mcmeta targets another game version (Minecraft
	 * will still load it, but list it as made for an older/newer version).
	 */
	public record State(Status status, float progress, String message, String fileName, String replaces,
			boolean oldFormat) {
		static final State IDLE = new State(Status.IDLE, 0, "", null, null, false);

		static State working(float p, String msg) {
			return new State(Status.WORKING, p, msg, null, null, false);
		}

		static State error(String msg) {
			return new State(Status.ERROR, 0, msg, null, null, false);
		}
	}

	/** Hard cap on one pack download (the biggest 512x packs are ~400 MB). */
	static final long MAX_BYTES = 1L << 30;

	private final Map<String, State> states = new ConcurrentHashMap<>();
	private final Map<String, AtomicBoolean> cancels = new ConcurrentHashMap<>();
	// at most three downloads at once; more queue (each shows "Waiting...")
	private final ExecutorService pool = Executors.newFixedThreadPool(3, r -> {
		Thread t = new Thread(r, "origin-pack-download");
		t.setDaemon(true);
		return t;
	});
	private final PackIndex index;
	/** Test seam: downloads are only allowed from Modrinth unless a test widens it. */
	boolean allowAnyHost = false;

	public PackInstaller(PackIndex index) {
		this.index = index;
	}

	public State state(String projectId) {
		return states.getOrDefault(projectId, State.IDLE);
	}

	/** Forget a finished/failed row so it can be started again. */
	public void clear(String projectId) {
		State s = state(projectId);
		if (s.status() != Status.WORKING) {
			states.remove(projectId);
		}
	}

	public void cancel(String projectId) {
		AtomicBoolean c = cancels.get(projectId);
		if (c != null) {
			c.set(true);
		}
	}

	/**
	 * Starts an install (replaces == null) or an update of the file `replaces`.
	 * No-op while that project is already working.
	 */
	public void start(String projectId, String title, String slug, String gameVersion, int gameFormat, Path dir,
			String replaces) {
		if (state(projectId).status() == Status.WORKING) {
			return;
		}
		AtomicBoolean cancel = new AtomicBoolean();
		cancels.put(projectId, cancel);
		states.put(projectId, State.working(0, "Waiting..."));
		pool.execute(() -> {
			try {
				states.put(projectId, run(projectId, title, slug, gameVersion, gameFormat, dir, replaces, cancel));
			} catch (Throwable t) {
				states.put(projectId, State.error(cancel.get() ? "Cancelled" : "Download failed"));
			} finally {
				cancels.remove(projectId);
			}
		});
	}

	/** Test entry: run one install synchronously. */
	State runNow(String projectId, String title, String slug, String gameVersion, int gameFormat, Path dir,
			String replaces) throws IOException {
		return run(projectId, title, slug, gameVersion, gameFormat, dir, replaces, new AtomicBoolean());
	}

	private State run(String projectId, String title, String slug, String gameVersion, int gameFormat, Path dir,
			String replaces, AtomicBoolean cancel) throws IOException {
		states.put(projectId, State.working(0, "Resolving..."));
		String listing;
		try {
			listing = ModrinthPacks.get(ModrinthPacks.versionsUrl(projectId, gameVersion));
		} catch (ModrinthPacks.RateLimited e) {
			return State.error("Modrinth is busy, try again soon");
		} catch (IOException e) {
			return State.error("Can't reach Modrinth");
		}
		ModrinthPacks.VersionFile file = listing == null ? null : ModrinthPacks.pickFile(listing);
		if (file == null) {
			return State.error("No " + gameVersion + " version");
		}
		return install(file, title, slug, gameFormat, dir, replaces, cancel, projectId);
	}

	/** Download + verify + move in one resolved file. Package-private for tests. */
	State install(ModrinthPacks.VersionFile file, String title, String slug, int gameFormat, Path dir,
			String replaces, AtomicBoolean cancel, String stateKey) throws IOException {
		if (!allowAnyHost && !ModrinthPacks.trustedDownload(file.url())) {
			return State.error("Untrusted download link");
		}
		if (file.size() > MAX_BYTES) {
			return State.error("Pack is too large");
		}
		Files.createDirectories(dir);

		// Already here? (same bytes under any name) -> done, nothing written.
		String existing = existingCopy(dir, file.sha1());
		if (existing != null) {
			// the player wants this file: if an earlier uninstall queued it, keep it
			index.cancelDelete(existing);
			index.put(existing, new PackIndex.Entry(file.projectId(), file.versionId(), title, slug, file.sha1()));
			PackFiles.PackInfo info = PackFiles.inspect(PackFiles.inside(dir, existing));
			return new State(Status.DONE, 1, "Installed", existing, replaces,
					info.ok() && gameFormat > 0 && !info.supports(gameFormat));
		}

		String safe = PackFiles.safeFileName(file.filename());
		Path tmp = PackFiles.inside(dir, safe.substring(0, safe.length() - 4) + "."
				+ Long.toHexString(System.nanoTime()) + ".part");
		try {
			download(file, tmp, cancel, stateKey);
			if (cancel.get()) {
				return new State(Status.CANCELLED, 0, "Cancelled", null, null, false);
			}
			if (!hashMatches(file, tmp)) {
				return State.error("Download was corrupted");
			}
			PackFiles.PackInfo info = PackFiles.inspect(tmp);
			if (!info.ok()) {
				return State.error(info.problem());
			}
			Path dest = PackFiles.uniqueDestination(dir, safe);
			PackFiles.moveInto(tmp, dest);
			String name = dest.getFileName().toString();
			index.put(name, new PackIndex.Entry(file.projectId(), file.versionId(), title, slug, file.sha1()));
			return new State(Status.DONE, 1, "Installed", name, replaces, gameFormat > 0 && !info.supports(gameFormat));
		} finally {
			// whatever happened, never leave a partial download behind
			Files.deleteIfExists(tmp);
		}
	}

	private void download(ModrinthPacks.VersionFile file, Path tmp, AtomicBoolean cancel, String key)
			throws IOException {
		HttpURLConnection conn = ModrinthPacks.open(file.url());
		conn.setRequestProperty("Accept", "*/*");
		int code = conn.getResponseCode();
		if (code < 200 || code >= 300) {
			conn.disconnect();
			throw new IOException("HTTP " + code);
		}
		// a redirect must not take us off Modrinth's hosts
		if (!allowAnyHost && !ModrinthPacks.trustedDownload(conn.getURL().toString())) {
			conn.disconnect();
			throw new IOException("Redirected off Modrinth");
		}
		long total = file.size() > 0 ? file.size() : conn.getContentLengthLong();
		long read = 0, lastReport = 0;
		try (InputStream in = conn.getInputStream(); OutputStream out = Files.newOutputStream(tmp)) {
			byte[] buf = new byte[65536];
			int n;
			while ((n = in.read(buf)) > 0) {
				if (cancel.get()) {
					return;
				}
				read += n;
				if (read > MAX_BYTES || (file.size() > 0 && read > file.size())) {
					throw new IOException("Download larger than announced");
				}
				out.write(buf, 0, n);
				if (read - lastReport > 256 * 1024) {
					lastReport = read;
					states.put(key, State.working(total > 0 ? (float) ((double) read / total) : 0f, "Downloading..."));
				}
			}
		}
		if (total > 0 && read != total) {
			throw new IOException("Incomplete download: " + read + " of " + total);
		}
		states.put(key, State.working(1f, "Verifying..."));
	}

	private static boolean hashMatches(ModrinthPacks.VersionFile file, Path tmp) throws IOException {
		if (!file.sha512().isEmpty()) {
			return file.sha512().equalsIgnoreCase(PackFiles.sha512(tmp));
		}
		if (!file.sha1().isEmpty()) {
			return file.sha1().equalsIgnoreCase(PackFiles.sha1(tmp));
		}
		// Modrinth always publishes hashes; a file without any is not trusted
		return false;
	}

	/** A file in dir that is byte-identical (by sha1) to the one we'd download, or null. */
	private String existingCopy(Path dir, String sha1) {
		if (sha1 == null || sha1.isEmpty()) {
			return null;
		}
		String known = index.fileWithSha1(sha1);
		if (known != null) {
			try {
				Path p = PackFiles.inside(dir, known);
				if (Files.isRegularFile(p) && sha1.equalsIgnoreCase(PackFiles.sha1(p))) {
					return known;
				}
			} catch (IOException ignored) {
				// fall through to the folder scan
			}
		}
		try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.zip")) {
			for (Path p : ds) {
				if (Files.isRegularFile(p) && sha1.equalsIgnoreCase(PackHashes.sha1(p))) {
					return p.getFileName().toString();
				}
			}
		} catch (IOException ignored) {
			// unreadable folder: download normally (uniqueDestination still never overwrites)
		}
		return null;
	}
}
