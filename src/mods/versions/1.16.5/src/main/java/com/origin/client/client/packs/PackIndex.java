package com.origin.client.client.packs;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// The hub's own small store (config/originclient-packhub.json), keyed by the
// pack's filename inside THIS account's resourcepacks/ folder:
//   installed     which Modrinth project/version a file came from (for updates
//                 and attribution) — only files the hub downloaded or identified
//   presets       named lists of enabled pack filenames, top priority first
//   pendingDelete files the player uninstalled while Minecraft still had them
//                 open (Windows locks a loaded zip); deleted after the next
//                 resource reload or at the next start
// Pure JDK + Gson so it is unit-tested outside the game. Every method is
// synchronized: the download threads and the render thread both touch it.
public final class PackIndex {
	public static final class Entry {
		private final String projectId;
		private final String versionId;
		private final String title;
		private final String slug;
		private final String sha1;

		public Entry(String projectId, String versionId, String title, String slug, String sha1) {
			this.projectId = projectId;
			this.versionId = versionId;
			this.title = title;
			this.slug = slug;
			this.sha1 = sha1;
		}

		public String projectId() {
			return projectId;
		}

		public String versionId() {
			return versionId;
		}

		public String title() {
			return title;
		}

		public String slug() {
			return slug;
		}

		public String sha1() {
			return sha1;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof Entry)) {
				return false;
			}
			Entry x = (Entry) o;
			return java.util.Objects.equals(projectId, x.projectId)
					&& java.util.Objects.equals(versionId, x.versionId)
					&& java.util.Objects.equals(title, x.title)
					&& java.util.Objects.equals(slug, x.slug)
					&& java.util.Objects.equals(sha1, x.sha1);
		}

		@Override
		public int hashCode() {
			return java.util.Arrays.hashCode(new Object[] {projectId, versionId, title, slug, sha1});
		}
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private final Path file;
	private final Map<String, Entry> installed = new LinkedHashMap<>();
	private final Map<String, List<String>> presets = new LinkedHashMap<>();
	private final Set<String> pendingDelete = new LinkedHashSet<>();

	public PackIndex(Path file) {
		this.file = file;
		load();
	}

	private synchronized void load() {
		try {
			if (!Files.isRegularFile(file)) {
				return;
			}
			JsonObject root = new JsonParser().parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).getAsJsonObject();
			JsonObject inst = root.getAsJsonObject("installed");
			if (inst != null) {
				for (Map.Entry<String, JsonElement> e : inst.entrySet()) {
					JsonObject o = e.getValue().getAsJsonObject();
					installed.put(e.getKey(), new Entry(ModrinthPacks.str(o, "project"), ModrinthPacks.str(o, "version"),
							ModrinthPacks.str(o, "title"), ModrinthPacks.str(o, "slug"), ModrinthPacks.str(o, "sha1")));
				}
			}
			JsonObject pre = root.getAsJsonObject("presets");
			if (pre != null) {
				for (Map.Entry<String, JsonElement> e : pre.entrySet()) {
					List<String> names = new ArrayList<>();
					for (JsonElement x : e.getValue().getAsJsonArray()) {
						names.add(x.getAsString());
					}
					presets.put(e.getKey(), names);
				}
			}
			JsonArray del = root.getAsJsonArray("pendingDelete");
			if (del != null) {
				del.forEach(x -> pendingDelete.add(x.getAsString()));
			}
		} catch (Exception e) {
			// a corrupt store starts empty; the packs themselves are untouched
			installed.clear();
			presets.clear();
			pendingDelete.clear();
		}
	}

	public synchronized void save() {
		JsonObject root = new JsonObject();
		JsonObject inst = new JsonObject();
		installed.forEach((name, e) -> {
			JsonObject o = new JsonObject();
			o.addProperty("project", e.projectId());
			o.addProperty("version", e.versionId());
			o.addProperty("title", e.title());
			o.addProperty("slug", e.slug());
			o.addProperty("sha1", e.sha1());
			inst.add(name, o);
		});
		root.add("installed", inst);
		JsonObject pre = new JsonObject();
		presets.forEach((name, list) -> {
			JsonArray a = new JsonArray();
			list.forEach(a::add);
			pre.add(name, a);
		});
		root.add("presets", pre);
		JsonArray del = new JsonArray();
		pendingDelete.forEach(del::add);
		root.add("pendingDelete", del);
		try {
			Files.createDirectories(file.getParent());
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.write(tmp, (GSON.toJson(root)).getBytes(StandardCharsets.UTF_8));
			PackFiles.moveInto(tmp, file);
		} catch (IOException e) {
			// moveInto refuses to replace: fall back to a plain overwrite of our own store
			try {
				Files.write(file, (GSON.toJson(root)).getBytes(StandardCharsets.UTF_8));
			} catch (IOException ignored) {
				// unsaved metadata only costs update checks; never the packs
			}
		}
	}

	// ---- installed ----

	public synchronized Entry get(String fileName) {
		return installed.get(fileName);
	}

	public synchronized void put(String fileName, Entry e) {
		installed.put(fileName, e);
		save();
	}

	public synchronized void forget(String fileName) {
		if (installed.remove(fileName) != null) {
			save();
		}
	}

	/** The filename already holding this exact file, or null. */
	public synchronized String fileWithSha1(String sha1) {
		if (sha1 == null || sha1.isEmpty()) {
			return null;
		}
		for (Map.Entry<String, Entry> e : installed.entrySet()) {
			if (sha1.equalsIgnoreCase(e.getValue().sha1())) {
				return e.getKey();
			}
		}
		return null;
	}

	/** Filenames installed from this Modrinth project. */
	public synchronized List<String> filesOfProject(String projectId) {
		List<String> out = new ArrayList<>();
		installed.forEach((name, e) -> {
			if (e.projectId().equals(projectId)) {
				out.add(name);
			}
		});
		return out;
	}

	public synchronized Map<String, Entry> installed() {
		return PackFiles.copyOfMap(installed);
	}

	// ---- presets ----

	public synchronized List<String> presetNames() {
		return PackFiles.copyOf(presets.keySet());
	}

	public synchronized List<String> preset(String name) {
		List<String> p = presets.get(name);
		return p == null ? java.util.Collections.emptyList() : PackFiles.copyOf(p);
	}

	public synchronized void savePreset(String name, List<String> enabledTopFirst) {
		presets.put(name, PackFiles.copyOf(enabledTopFirst));
		save();
	}

	public synchronized void deletePreset(String name) {
		if (presets.remove(name) != null) {
			save();
		}
	}

	// ---- deletes that wait for Minecraft to let go of the file ----

	public synchronized void markForDelete(String fileName) {
		pendingDelete.add(fileName);
		save();
	}

	public synchronized boolean isPendingDelete(String fileName) {
		return pendingDelete.contains(fileName);
	}

	public synchronized void cancelDelete(String fileName) {
		if (pendingDelete.remove(fileName)) {
			save();
		}
	}

	/**
	 * Tries every pending delete inside dir; those that succeed (or are already
	 * gone) leave the list. Returns how many were removed.
	 */
	public synchronized int flushDeletes(Path dir) {
		int done = 0;
		java.util.Iterator<String> it = pendingDelete.iterator();
		while (it.hasNext()) {
			String name = it.next();
			try {
				Files.deleteIfExists(PackFiles.inside(dir, name));
				it.remove();
				installed.remove(name);
				done++;
			} catch (IOException e) {
				// still locked (or not ours to touch): try again after the next reload
			}
		}
		if (done > 0) {
			save();
		}
		return done;
	}
}
