package com.origin.client.client.packs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// The Browse tab's live result list: one query at a time, fetched a page at a
// time from Modrinth search on ONE background thread (so the hub can never
// burst past Modrinth's 300 requests/minute), merged without duplicate
// projects. The render thread only reads snapshot().
//
// Caching, so reopening the hub or flipping filters back is instant and the hub
// still works offline:
//   memory  last 48 page responses, fresh for 10 minutes
//   disk    every page response (cacheDir, newest 400 kept); used only when the
//           network fails, and the snapshot says it is showing cached results
// Pure JDK + Gson; unit-tested in tools/packhub-tests.
public final class PackCatalog {
	public static final int PAGE = 40;
	/** Results stop here even if Modrinth reports more (scrolling 1000 packs is plenty). */
	public static final int MAX_RESULTS = 1000;
	private static final long FRESH_MS = 10 * 60_000;
	private static final int MEM_PAGES = 48, DISK_FILES = 400;

	/**
	 * What the screen draws. error != null = the last page failed (retry() tries again);
	 * cached = at least one page came from the offline disk cache.
	 */
	public static final class Snapshot {
		private final List<ModrinthPacks.Hit> hits;
		private final int total;
		private final boolean loading;
		private final boolean done;
		private final String error;
		private final boolean cached;

		public Snapshot(List<ModrinthPacks.Hit> hits, int total, boolean loading, boolean done, String error, boolean cached) {
			this.hits = hits;
			this.total = total;
			this.loading = loading;
			this.done = done;
			this.error = error;
			this.cached = cached;
		}

		public List<ModrinthPacks.Hit> hits() {
			return hits;
		}

		public int total() {
			return total;
		}

		public boolean loading() {
			return loading;
		}

		public boolean done() {
			return done;
		}

		public String error() {
			return error;
		}

		public boolean cached() {
			return cached;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof Snapshot)) {
				return false;
			}
			Snapshot x = (Snapshot) o;
			return java.util.Objects.equals(hits, x.hits)
					&& total == x.total
					&& loading == x.loading
					&& done == x.done
					&& java.util.Objects.equals(error, x.error)
					&& cached == x.cached;
		}

		@Override
		public int hashCode() {
			return java.util.Arrays.hashCode(new Object[] {hits, total, loading, done, error, cached});
		}
	}

	private static final class Mem {
		private final String json;
		private final long at;

		private Mem(String json, long at) {
			this.json = json;
			this.at = at;
		}

		public String json() {
			return json;
		}

		public long at() {
			return at;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof Mem)) {
				return false;
			}
			Mem x = (Mem) o;
			return java.util.Objects.equals(json, x.json)
					&& at == x.at;
		}

		@Override
		public int hashCode() {
			return java.util.Arrays.hashCode(new Object[] {json, at});
		}
	}

	private final Path cacheDir;
	private final String gameVersion;
	private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "origin-pack-catalog");
		t.setDaemon(true);
		return t;
	});
	private final Map<String, Mem> mem = new LinkedHashMap<String, Mem>(64, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Mem> e) {
			return size() > MEM_PAGES;
		}
	};

	// guarded by this
	private ModrinthPacks.Query query;
	private int generation = 0;
	private final List<ModrinthPacks.Hit> hits = new ArrayList<>();
	private final Set<String> seen = new HashSet<>();
	private int total = -1;
	private boolean loading = false, cached = false;
	private String error = null;
	private volatile Snapshot snapshot = new Snapshot(java.util.Collections.emptyList(), -1, false, false, null, false);

	public PackCatalog(Path cacheDir, String gameVersion) {
		this.cacheDir = cacheDir;
		this.gameVersion = gameVersion;
	}

	public Snapshot snapshot() {
		return snapshot;
	}

	public synchronized ModrinthPacks.Query query() {
		return query;
	}

	/** Switch to a new query (a no-op when it is the current one); loads its first page. */
	public synchronized void setQuery(ModrinthPacks.Query q) {
		if (q.equals(query)) {
			return;
		}
		query = q;
		generation++;
		hits.clear();
		seen.clear();
		total = -1;
		loading = false;
		cached = false;
		offsetExhausted = false;
		error = null;
		publish();
		loadMore();
	}

	/** Fetch the next page unless one is in flight, the list is complete, or the last one failed. */
	public synchronized void loadMore() {
		if (query == null || loading || error != null || isDone()) {
			return;
		}
		loading = true;
		publish();
		final int gen = generation, offset = hits.size();
		final ModrinthPacks.Query q = query;
		worker.execute(() -> fetch(gen, q, offset));
	}

	public synchronized void retry() {
		error = null;
		loadMore();
	}

	private boolean isDone() {
		return total >= 0 && (hits.size() >= Math.min(total, MAX_RESULTS) || offsetExhausted);
	}

	// Modrinth can report total_hits beyond what paging actually returns; an
	// empty page ends the list either way.
	private boolean offsetExhausted = false;

	private void fetch(int gen, ModrinthPacks.Query q, int offset) {
		String url = ModrinthPacks.searchUrl(q, gameVersion, offset, PAGE);
		String json = null;
		String err = null;
		boolean fromDisk = false;
		Mem m;
		synchronized (this) {
			m = mem.get(url);
		}
		if (m != null && System.currentTimeMillis() - m.at() < FRESH_MS) {
			json = m.json();
		} else {
			try {
				json = ModrinthPacks.get(url);
				if (json != null) {
					synchronized (this) {
						mem.put(url, new Mem(json, System.currentTimeMillis()));
					}
					writeDisk(url, json);
				}
			} catch (ModrinthPacks.RateLimited e) {
				err = "Modrinth is busy, retry in " + Math.max(1, (e.retryAtMs - System.currentTimeMillis()) / 1000) + "s";
			} catch (IOException | RuntimeException e) {
				err = "Can't reach Modrinth";
			}
			if (json == null) {
				json = readDisk(url);
				fromDisk = json != null;
				if (json == null && err == null) {
					err = "Modrinth returned nothing";
				}
			}
		}
		ModrinthPacks.Page page = null;
		if (json != null) {
			try {
				page = ModrinthPacks.parseSearch(json);
			} catch (RuntimeException e) {
				err = "Modrinth sent an unreadable answer";
			}
		}
		synchronized (this) {
			if (gen != generation) {
				return; // the query changed while this page was loading
			}
			loading = false;
			if (page == null) {
				error = err == null ? "Something went wrong" : err;
			} else {
				error = null;
				cached |= fromDisk;
				total = page.total();
				if (page.hits().isEmpty()) {
					offsetExhausted = true;
				}
				for (ModrinthPacks.Hit h : page.hits()) {
					if (seen.add(h.projectId()) && hits.size() < MAX_RESULTS) {
						hits.add(h);
					}
				}
			}
			publish();
		}
	}

	private void publish() {
		snapshot = new Snapshot(PackFiles.copyOf(hits), total, loading, isDone(), error, cached);
	}

	@Override
	public synchronized String toString() {
		return "PackCatalog[" + query + ", " + hits.size() + "/" + total + "]";
	}

	// ---- disk cache ----

	private Path diskFile(String url) {
		try {
			java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
			return cacheDir.resolve(PackFiles.hex(md.digest(url.getBytes(StandardCharsets.UTF_8))) + ".json");
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private void writeDisk(String url, String json) {
		try {
			Files.createDirectories(cacheDir);
			Files.write(diskFile(url), (json).getBytes(StandardCharsets.UTF_8));
			prune();
		} catch (IOException ignored) {
			// cache is best-effort
		}
	}

	private String readDisk(String url) {
		try {
			Path f = diskFile(url);
			return Files.isRegularFile(f) ? new String(Files.readAllBytes(f), StandardCharsets.UTF_8) : null;
		} catch (IOException e) {
			return null;
		}
	}

	private void prune() throws IOException {
		List<Path> files = new ArrayList<>();
		try (DirectoryStream<Path> ds = Files.newDirectoryStream(cacheDir, "*.json")) {
			ds.forEach(files::add);
		}
		if (files.size() <= DISK_FILES) {
			return;
		}
		files.sort(Comparator.comparingLong(p -> {
			try {
				return Files.getLastModifiedTime(p).toMillis();
			} catch (IOException e) {
				return 0L;
			}
		}));
		for (int i = 0; i < files.size() - DISK_FILES; i++) {
			Files.deleteIfExists(files.get(i));
		}
	}
}
