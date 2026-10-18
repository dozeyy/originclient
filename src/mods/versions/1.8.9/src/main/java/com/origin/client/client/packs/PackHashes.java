package com.origin.client.client.packs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.Map;

// SHA-1 of local pack files, remembered by (path, size, last-modified) so the
// duplicate check and the "updates available" scan don't re-read a 300 MB
// pack every time the hub opens. Bounded LRU; a changed file re-hashes.
public final class PackHashes {
	private PackHashes() {
	}

	private static final class Key {
		private final String path;
		private final long size;
		private final long mtime;

		private Key(String path, long size, long mtime) {
			this.path = path;
			this.size = size;
			this.mtime = mtime;
		}

		public String path() {
			return path;
		}

		public long size() {
			return size;
		}

		public long mtime() {
			return mtime;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof Key)) {
				return false;
			}
			Key x = (Key) o;
			return java.util.Objects.equals(path, x.path)
					&& size == x.size
					&& mtime == x.mtime;
		}

		@Override
		public int hashCode() {
			return java.util.Arrays.hashCode(new Object[] {path, size, mtime});
		}
	}

	private static final int MAX = 512;
	private static final Map<Key, String> CACHE = new LinkedHashMap<Key, String>(64, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Key, String> e) {
			return size() > MAX;
		}
	};

	public static String sha1(Path file) throws IOException {
		BasicFileAttributes a = Files.readAttributes(file, BasicFileAttributes.class);
		Key k = new Key(file.toAbsolutePath().normalize().toString(), a.size(), a.lastModifiedTime().toMillis());
		synchronized (CACHE) {
			String hit = CACHE.get(k);
			if (hit != null) {
				return hit;
			}
		}
		String h = PackFiles.sha1(file);
		synchronized (CACHE) {
			CACHE.put(k, h);
		}
		return h;
	}
}
