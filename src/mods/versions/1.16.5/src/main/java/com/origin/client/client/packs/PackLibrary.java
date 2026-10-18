package com.origin.client.client.packs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// The Installed tab's view of resourcepacks/: every zip and pack folder, what
// its pack.mcmeta says, and - for zips - which Modrinth project it is and
// whether a newer build exists for this game version. Packs the player added by
// hand are recognised by file hash (Modrinth's /version_files), so they get
// updates too; packs Modrinth doesn't know just show as local.
// Scans run on one background thread; the screen reads snapshot(). Reading only:
// nothing here writes to resourcepacks/. Pure JDK + Gson; unit-tested.
public final class PackLibrary {
	/** Network lookups (identify + update check) are reused for this long. */
	private static final long NET_FRESH_MS = 10 * 60_000;
	/** One request covers at most this many files (keeps the POST small). */
	private static final int MAX_HASHES = 500;

	/**
	 * folder = an unzipped pack directory (listed and orderable, never deleted by
	 * the hub); entry = the Modrinth identity, null when unknown; update = a newer
	 * build of the same project for this game version, null when up to date.
	 */
	public static final class Local {
		private final String name;
		private final boolean folder;
		private final long size;
		private final PackFiles.PackInfo info;
		private final String sha1;
		private final PackIndex.Entry entry;
		private final ModrinthPacks.VersionFile update;

		public Local(String name, boolean folder, long size, PackFiles.PackInfo info, String sha1, PackIndex.Entry entry, ModrinthPacks.VersionFile update) {
			this.name = name;
			this.folder = folder;
			this.size = size;
			this.info = info;
			this.sha1 = sha1;
			this.entry = entry;
			this.update = update;
		}

		public String name() {
			return name;
		}

		public boolean folder() {
			return folder;
		}

		public long size() {
			return size;
		}

		public PackFiles.PackInfo info() {
			return info;
		}

		public String sha1() {
			return sha1;
		}

		public PackIndex.Entry entry() {
			return entry;
		}

		public ModrinthPacks.VersionFile update() {
			return update;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof Local)) {
				return false;
			}
			Local x = (Local) o;
			return java.util.Objects.equals(name, x.name)
					&& folder == x.folder
					&& size == x.size
					&& java.util.Objects.equals(info, x.info)
					&& java.util.Objects.equals(sha1, x.sha1)
					&& java.util.Objects.equals(entry, x.entry)
					&& java.util.Objects.equals(update, x.update);
		}

		@Override
		public int hashCode() {
			return java.util.Arrays.hashCode(new Object[] {name, folder, size, info, sha1, entry, update});
		}
	}

	/** netError != null = the last identify/update lookup failed (local list still valid). */
	public static final class Snapshot {
		private final List<Local> packs;
		private final boolean scanning;
		private final boolean scanned;
		private final String netError;

		public Snapshot(List<Local> packs, boolean scanning, boolean scanned, String netError) {
			this.packs = packs;
			this.scanning = scanning;
			this.scanned = scanned;
			this.netError = netError;
		}

		public List<Local> packs() {
			return packs;
		}

		public boolean scanning() {
			return scanning;
		}

		public boolean scanned() {
			return scanned;
		}

		public String netError() {
			return netError;
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
			return java.util.Objects.equals(packs, x.packs)
					&& scanning == x.scanning
					&& scanned == x.scanned
					&& java.util.Objects.equals(netError, x.netError);
		}

		@Override
		public int hashCode() {
			return java.util.Arrays.hashCode(new Object[] {packs, scanning, scanned, netError});
		}
	}

	private final PackIndex index;
	private final String gameVersion;
	private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "origin-pack-library");
		t.setDaemon(true);
		return t;
	});

	// guarded by this
	private boolean running = false, again = false, againNetwork = false;
	private Path againDir;
	private long netAt = 0;
	private final Map<String, ModrinthPacks.VersionFile> updates = new HashMap<>();
	private volatile Snapshot snapshot = new Snapshot(java.util.Collections.emptyList(), false, false, null);

	public PackLibrary(PackIndex index, String gameVersion) {
		this.index = index;
		this.gameVersion = gameVersion;
	}

	public Snapshot snapshot() {
		return snapshot;
	}

	/** Rescan dir; network = also identify + check updates (reused for 10 min unless forced). */
	public void rescan(Path dir, boolean network) {
		rescan(dir, network, false);
	}

	public void rescan(Path dir, boolean network, boolean forceNetwork) {
		synchronized (this) {
			if (forceNetwork) {
				netAt = 0;
			}
			if (running) {
				// coalesce: one more scan after the current one, with the widest ask
				again = true;
				againNetwork |= network;
				againDir = dir;
				return;
			}
			running = true;
			Snapshot s = snapshot;
			snapshot = new Snapshot(s.packs(), true, s.scanned(), s.netError());
		}
		worker.execute(() -> loop(dir, network));
	}

	/** Forget a just-applied update so the row stops offering it before the next scan. */
	public synchronized void dropUpdate(String sha1) {
		updates.remove(sha1);
	}

	private void loop(Path dir, boolean network) {
		while (true) {
			// a local-only or failed scan keeps the last network message
			String netError = snapshot.netError();
			List<Local> packs;
			try {
				packs = scanLocal(dir);
				if (network) {
					netError = lookUp(packs);
				}
				packs = withIdentity(packs);
			} catch (Throwable t) {
				packs = snapshot.packs();
			}
			synchronized (this) {
				if (!again) {
					running = false;
					snapshot = new Snapshot(packs, false, true, netError);
					return;
				}
				snapshot = new Snapshot(packs, true, true, netError);
				again = false;
				network = againNetwork;
				againNetwork = false;
				dir = againDir;
			}
		}
	}

	// ---- local ----

	/** Every pack in dir (zips + folders with a pack.mcmeta), A-Z. */
	public static List<Local> scanLocal(Path dir) throws IOException {
		List<Local> out = new ArrayList<>();
		if (!Files.isDirectory(dir)) {
			return out;
		}
		try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
			for (Path p : ds) {
				String name = p.getFileName().toString();
				if (name.startsWith(".")) {
					continue;
				}
				try {
					if (Files.isRegularFile(p) && name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
						out.add(new Local(name, false, Files.size(p), PackFiles.inspect(p), PackHashes.sha1(p), null, null));
					} else if (Files.isDirectory(p) && Files.isRegularFile(p.resolve("pack.mcmeta"))) {
						PackFiles.PackInfo info;
						Path meta = p.resolve("pack.mcmeta");
						if (Files.size(meta) > 1 << 20) {
							info = new PackFiles.PackInfo(0, 0, 0, "", "pack.mcmeta is too large");
						} else {
							info = PackFiles.parseMcmeta(new String(Files.readAllBytes(meta), StandardCharsets.UTF_8));
						}
						out.add(new Local(name, true, 0, info, "", null, null));
					}
				} catch (IOException | RuntimeException e) {
					// unreadable entry: list it so the player can still see and remove it
					out.add(new Local(name, Files.isDirectory(p), 0,
							new PackFiles.PackInfo(0, 0, 0, "", "Can't read this pack"), "", null, null));
				}
			}
		}
		out.sort(Comparator.comparing(l -> l.name().toLowerCase(Locale.ROOT)));
		return out;
	}

	// ---- network: identify hand-added packs, find updates ----

	/** Returns a short error for the UI, or null. Results land in the index / updates map. */
	private String lookUp(List<Local> packs) {
		synchronized (this) {
			if (System.currentTimeMillis() - netAt < NET_FRESH_MS) {
				return null;
			}
		}
		try {
			// 1. zips the index doesn't know (or whose bytes changed): ask Modrinth what they are
			List<String> unknown = new ArrayList<>();
			Map<String, String> nameOf = new HashMap<>();
			for (Local l : packs) {
				if (l.folder() || l.sha1().isEmpty()) {
					continue;
				}
				nameOf.put(l.sha1(), l.name());
				PackIndex.Entry e = index.get(l.name());
				if ((e == null || !l.sha1().equalsIgnoreCase(e.sha1())) && unknown.size() < MAX_HASHES) {
					unknown.add(l.sha1());
				}
			}
			if (!unknown.isEmpty()) {
				String json = ModrinthPacks.post(ModrinthPacks.API + "/version_files", ModrinthPacks.identifyBody(unknown));
				Map<String, ModrinthPacks.VersionFile> found = json == null ? java.util.Collections.emptyMap() : ModrinthPacks.parseHashMap(json);
				Set<String> projects = new HashSet<>();
				found.values().forEach(v -> projects.add(v.projectId()));
				Map<String, String[]> meta = projects.isEmpty() ? java.util.Collections.emptyMap()
						: parseOrEmpty(ModrinthPacks.get(ModrinthPacks.projectsUrl(new ArrayList<>(projects))));
				for (String h : unknown) {
					String name = nameOf.get(h);
					ModrinthPacks.VersionFile v = found.get(h);
					if (v == null) {
						// not (or no longer) a Modrinth file: drop a stale identity
						if (index.get(name) != null) {
							index.forget(name);
						}
						continue;
					}
					String[] m = meta.getOrDefault(v.projectId(), new String[]{name, "", ""});
					index.put(name, new PackIndex.Entry(v.projectId(), v.versionId(), m[0], m[2], h));
				}
			}
			// 2. every known zip: is there a newer build for this game version?
			List<String> known = new ArrayList<>();
			for (Local l : packs) {
				PackIndex.Entry e = l.folder() ? null : index.get(l.name());
				if (e != null && l.sha1().equalsIgnoreCase(e.sha1()) && known.size() < MAX_HASHES) {
					known.add(l.sha1());
				}
			}
			Map<String, ModrinthPacks.VersionFile> latest = new HashMap<>();
			if (!known.isEmpty()) {
				String json = ModrinthPacks.post(ModrinthPacks.API + "/version_files/update",
						ModrinthPacks.updateBody(known, gameVersion));
				if (json != null) {
					latest.putAll(ModrinthPacks.parseHashMap(json));
				}
			}
			synchronized (this) {
				updates.clear();
				for (Map.Entry<String, ModrinthPacks.VersionFile> e : latest.entrySet()) {
					if (!e.getValue().sha1().equalsIgnoreCase(e.getKey())) {
						updates.put(e.getKey(), e.getValue());
					}
				}
				netAt = System.currentTimeMillis();
			}
			return null;
		} catch (ModrinthPacks.RateLimited e) {
			return "Modrinth is busy - update check skipped";
		} catch (IOException | RuntimeException e) {
			return "Offline - can't check for updates";
		}
	}

	private static Map<String, String[]> parseOrEmpty(String json) {
		try {
			return json == null ? java.util.Collections.emptyMap() : ModrinthPacks.parseProjects(json);
		} catch (RuntimeException e) {
			return java.util.Collections.emptyMap();
		}
	}

	private List<Local> withIdentity(List<Local> packs) {
		List<Local> out = new ArrayList<>(packs.size());
		synchronized (this) {
			for (Local l : packs) {
				PackIndex.Entry e = l.folder() ? null : index.get(l.name());
				if (e != null && !l.sha1().equalsIgnoreCase(e.sha1())) {
					e = null; // the file was swapped by hand; the old identity no longer applies
				}
				out.add(new Local(l.name(), l.folder(), l.size(), l.info(), l.sha1(), e,
						e == null ? null : updates.get(l.sha1())));
			}
		}
		return out;
	}
}
