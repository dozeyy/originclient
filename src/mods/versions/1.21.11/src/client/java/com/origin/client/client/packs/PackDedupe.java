package com.origin.client.client.packs;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

// Browse hides re-uploaded copies (Will, 2026-11-03): Modrinth search is full of
// the same pack uploaded again under someone else's name ("Bombies 180k [16x]",
// "Bombies 180k edit", "bombies 180k 1.21"). Copies collapse into ONE row: the
// original, i.e. the hand-picked original when there is one, else the most
// downloaded upload.
//
// Two hits are the same pack when
//   * their titles match once resolution tags, version numbers, filler words
//     ("pack", "pvp", "edit", "reupload", ...) and punctuation are removed, or
//   * they carry the same long description (copies usually paste it).
// Guards against merging distinct packs:
//   * one group never holds two projects by the same uploader: copies are made by
//     OTHER people, so "Faithful 32x" and "Faithful 64x" by Faithful stay apart;
//   * a title that is only generic words ("Bedwars Pack", "PvP 16x") never merges
//     by title; sharing a word ("Prism" vs "Prism Purple") is not a match.
// Also hidden: known re-uploads of a pick (PackPicks copies list), and uploads
// that reuse a pick's exact title under another uploader (the pick is the original).
// Pure Java 8 (this file is identical in every module, 1.8.9 included);
// unit-tested in tools/packhub-tests.
public final class PackDedupe {
	private PackDedupe() {
	}

	/** What Browse shows plus how many copies it hid (said in the footer). */
	public static final class Result {
		public final List<ModrinthPacks.Hit> hits;
		public final int hidden;

		Result(List<ModrinthPacks.Hit> hits, int hidden) {
			this.hits = hits;
			this.hidden = hidden;
		}
	}

	private static final Pattern BRACKETS = Pattern.compile("[\\[(\\{<|]");
	/** 16x, 32 x 32, x16, 512x+, 8x-. */
	private static final Pattern RES = Pattern.compile("\\b\\d{1,4}\\s*x(\\s*\\d{1,4})?[+-]?(?=\\s|$)|\\bx\\d{1,4}\\b");
	/** 1.8.9, v1.2, 1.21.x, 26.1, v3, r5 (version / release tags). */
	private static final Pattern VERSION = Pattern.compile(
			"\\bv?\\d+(\\.(\\d+|x))+[a-z]?\\b|\\b[vr]\\d+\\b|\\b\\d+\\.x\\b");
	/** "by <uploader>" at the end ("Bombies 180k by Tory"). */
	private static final Pattern BY = Pattern.compile("\\s+by\\s+\\S+(\\s+\\S+)?$");
	private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");
	private static final Pattern MARKS = Pattern.compile("\\p{M}+");

	/** Words that say nothing about WHICH pack it is. Removed before comparing titles. */
	static final Set<String> FILLER = new HashSet<>(Arrays.asList(
			"pack", "packs", "texture", "textures", "texturepack", "resource", "resources", "resourcepack", "rp", "tp",
			"pvp", "edit", "edited", "edition", "remake", "remade", "reupload", "reuploaded", "re", "upload", "fixed",
			"fix", "port", "ported", "update", "updated", "version", "official", "original", "the", "a", "for", "and",
			"mc", "minecraft", "java", "release", "final", "new", "free", "download", "hd", "x", "ver"));
	/**
	 * A title made ONLY of these (after FILLER) is a description, not a name:
	 * many different packs are called "Bedwars" or "Low Fire" or "Dark UI".
	 */
	static final Set<String> GENERIC = new HashSet<>(Arrays.asList(
			"bedwars", "skywars", "uhc", "sumo", "duels", "bridge", "hypixel", "crystal", "default", "vanilla",
			"simple", "clean", "dark", "light", "minimal", "minimalist", "fps", "boost", "smooth", "short", "sword",
			"swords", "low", "fire", "shield", "totem", "ui", "gui", "mode", "red", "blue", "green", "purple", "pink",
			"black", "white", "gold", "yellow", "orange", "aqua", "cyan", "colorful", "colourful", "cute", "anime",
			"my", "best", "better", "good", "cool", "nice", "custom", "overlay", "overlays", "glass", "ores", "tweaks",
			"realistic", "classic", "modern", "legacy", "crosshair", "crosshairs", "hud", "sky", "particles",
			"particle", "totems", "hitbox", "hitboxes", "items", "blocks", "sounds", "font"));
	/** Descriptions shorter than this (normalized) are too generic to prove a copy. */
	static final int MIN_DESC = 60;

	/** Title key without resolution tags ("bombies 180k"); "" = too generic to compare. */
	public static String looseKey(String title) {
		return key(title, true);
	}

	/** Same, but resolution tags kept ("faithful 32x") - used when one uploader owns both. */
	public static String strictKey(String title) {
		return key(title, false);
	}

	private static String key(String title, boolean dropRes) {
		if (title == null) {
			return "";
		}
		String s = MARKS.matcher(Normalizer.normalize(title, Normalizer.Form.NFKD)).replaceAll("")
				.toLowerCase(Locale.ROOT);
		s = BRACKETS.matcher(s).replaceAll(" ").replace(']', ' ').replace(')', ' ').replace('}', ' ').replace('>', ' ');
		s = BY.matcher(s.trim()).replaceAll("");
		s = VERSION.matcher(s).replaceAll(" ");
		if (dropRes) {
			s = RES.matcher(s).replaceAll(" ");
		} else {
			// "32 x" -> "32x" so both spellings agree
			s = s.replaceAll("\\b(\\d{1,4})\\s*x\\b", "$1x");
		}
		List<String> words = new ArrayList<>();
		boolean named = false;
		for (String w : NON_WORD.split(s)) {
			if (w.isEmpty() || FILLER.contains(w)) {
				continue;
			}
			words.add(w);
			if (!GENERIC.contains(w) && !w.matches("\\d+x")) {
				named = true;
			}
		}
		return named ? String.join(" ", words) : "";
	}

	public static String descKey(String description) {
		if (description == null) {
			return "";
		}
		String s = NON_WORD.matcher(description.toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
		return s.length() >= MIN_DESC ? s : "";
	}

	/**
	 * Collapses copies. Order follows the raw list (the player's sort): a group sits
	 * where its first member arrived and shows its best member.
	 *
	 * @param originals Modrinth project ids that are the curated original of their pack
	 * @param copies    Modrinth project ids known to be re-uploads (always hidden)
	 * @param pickTitles loose title key -> the original's uploader (lower case), for
	 *                   picks whose original may not be in this result list at all
	 */
	public static Result collapse(List<ModrinthPacks.Hit> raw, Set<String> originals, Set<String> copies,
			Map<String, String> pickTitles) {
		int n = raw.size();
		int[] parent = new int[n];
		List<Set<String>> authors = new ArrayList<>(n);
		boolean[] drop = new boolean[n];
		for (int i = 0; i < n; i++) {
			parent[i] = i;
			Set<String> a = new HashSet<>();
			a.add(author(raw.get(i)));
			authors.add(a);
			ModrinthPacks.Hit h = raw.get(i);
			if (copies.contains(h.projectId())) {
				drop[i] = true;
				continue;
			}
			String owner = pickTitles.get(looseKey(h.title()));
			if (owner != null && !originals.contains(h.projectId()) && !owner.equals(author(h))) {
				drop[i] = true; // someone else's upload of a pick, under the pick's own name
			}
		}
		// strict title first, so an uploader's 32x and 64x claim their own copies,
		// then loose title, then description
		joinBy(raw, parent, authors, drop, 0);
		joinBy(raw, parent, authors, drop, 1);
		joinBy(raw, parent, authors, drop, 2);

		Map<Integer, Integer> best = new HashMap<>();
		for (int i = 0; i < n; i++) {
			if (drop[i]) {
				continue;
			}
			int r = find(parent, i);
			Integer b = best.get(r);
			if (b == null || better(raw.get(i), raw.get(b), originals)) {
				best.put(r, i);
			}
		}
		List<ModrinthPacks.Hit> out = new ArrayList<>();
		Set<Integer> placed = new HashSet<>();
		for (int i = 0; i < n; i++) {
			if (drop[i]) {
				continue;
			}
			int r = find(parent, i);
			if (placed.add(r)) {
				out.add(raw.get(best.get(r)));
			}
		}
		return new Result(Collections.unmodifiableList(out), n - out.size());
	}

	/** Same as collapse with no picks (tests, and a module without a picks list). */
	public static Result collapse(List<ModrinthPacks.Hit> raw) {
		return collapse(raw, Collections.<String>emptySet(), Collections.<String>emptySet(),
				Collections.<String, String>emptyMap());
	}

	private static void joinBy(List<ModrinthPacks.Hit> raw, int[] parent, List<Set<String>> authors, boolean[] drop,
			int mode) {
		Map<String, List<Integer>> buckets = new HashMap<>();
		for (int i = 0; i < raw.size(); i++) {
			if (drop[i]) {
				continue;
			}
			ModrinthPacks.Hit h = raw.get(i);
			String k = mode == 0 ? strictKey(h.title()) : mode == 1 ? looseKey(h.title()) : descKey(h.description());
			if (k.isEmpty()) {
				continue;
			}
			List<Integer> b = buckets.get(k);
			if (b == null) {
				b = new ArrayList<>();
				buckets.put(k, b);
			}
			for (int j : b) {
				union(parent, authors, i, j);
			}
			b.add(i);
		}
	}

	/** Joins the groups of i and j unless that would put one uploader in a group twice. */
	private static void union(int[] parent, List<Set<String>> authors, int i, int j) {
		int a = find(parent, i), b = find(parent, j);
		if (a == b) {
			return;
		}
		for (String x : authors.get(a)) {
			if (authors.get(b).contains(x)) {
				return;
			}
		}
		parent[b] = a;
		authors.get(a).addAll(authors.get(b));
	}

	private static int find(int[] parent, int i) {
		while (parent[i] != i) {
			parent[i] = parent[parent[i]];
			i = parent[i];
		}
		return i;
	}

	/** Is a a better representative than b? Curated original, then downloads. */
	private static boolean better(ModrinthPacks.Hit a, ModrinthPacks.Hit b, Set<String> originals) {
		boolean ao = originals.contains(a.projectId()), bo = originals.contains(b.projectId());
		if (ao != bo) {
			return ao;
		}
		return a.downloads() > b.downloads();
	}

	private static String author(ModrinthPacks.Hit h) {
		return h.author() == null ? "" : h.author().toLowerCase(Locale.ROOT);
	}
}
