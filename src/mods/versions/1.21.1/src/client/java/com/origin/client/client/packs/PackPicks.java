package com.origin.client.client.packs;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

// The hub's hand-picked PvP / Bedwars packs (Will, 2026-11-03): the first thing
// the Resource Pack Hub shows, before live search. BUILT INTO THE JAR - the list
// changes only with a release.
//
// Every pick names the creator and the source it comes from, and how the hub may
// fetch it:
//   MODRINTH  installed through the normal Modrinth path (the build for THIS game
//             version, verified against Modrinth's own hash); project id pinned,
//             so a renamed slug can't point somewhere else.
//   DIRECT    the creator's own download (e.g. Faithful's GitHub release), only
//             where their terms allow automated downloads. The SHA-256 and size
//             are PINNED here at curation time; a different file is refused and
//             the tile offers the creator's page instead.
//   LINK      "Open page": the creator's / official page opens in the browser.
//             For sources whose terms forbid automated downloads (PvPRP) or that
//             sit behind ad / subscribe gates (MediaFire), and for packs built on
//             a site (Vanilla Tweaks). Never scraped, never fetched in game.
// Origin never re-hosts a pack.
//
// The data between the PICKS markers is GENERATED from tools/packs/picks.json by
// `python tools/packs/verify_picks.py --write` (which also checks every pick
// against the live sources in CI: .github/workflows/pack-picks.yml). Edit the
// JSON, never the table. Pure Java 8: this file is identical in every module.
public final class PackPicks {
	private PackPicks() {
	}

	public enum Source { MODRINTH, DIRECT, LINK }

	/** Style chips, in the order the hub shows them. */
	public enum Style {
		PVP("PvP"), BEDWARS("Bedwars"), CLEAN("Clean"), COLOURFUL("Colourful"), DARK_UI("Dark UI"), FPS("FPS"),
		LOW_FIRE("Low fire & swords"), CRYSTAL("Crystal PvP"), FAITHFUL("Faithful");

		public final String label;

		Style(String label) {
			this.label = label;
		}
	}

	public static final class Pick {
		public final String id, title, author, about, res, sourceName, pageUrl, credit;
		public final Source source;
		public final List<Style> styles;
		/** Exact game versions this pick really supports (Modrinth files / the creator's own builds). */
		public final List<String> versions;
		/** MODRINTH: pinned project id + the uploader's Modrinth username; icon/image on Modrinth's CDN. */
		public final String projectId, slug, owner, iconUrl, imageUrl;
		/** DIRECT: the official file, its pinned SHA-256 + size, and the hosts it may come from (after redirects). */
		public final String fileUrl, sha256, fileName;
		public final long size;
		public final List<String> hosts;
		/** Modrinth project ids of known re-uploads of this pack (hidden in Browse). */
		public final List<String> copies;

		Pick(String id, Source source, String title, String author, String about, String res, String sourceName,
				String pageUrl, String credit, List<Style> styles, List<String> versions, String projectId, String slug,
				String owner, String iconUrl, String imageUrl, String fileUrl, String sha256, String fileName, long size,
				List<String> hosts, List<String> copies) {
			this.id = id;
			this.source = source;
			this.title = title;
			this.author = author;
			this.about = about;
			this.res = res;
			this.sourceName = sourceName;
			this.pageUrl = pageUrl;
			this.credit = credit;
			this.styles = styles;
			this.versions = versions;
			this.projectId = projectId;
			this.slug = slug;
			this.owner = owner;
			this.iconUrl = iconUrl;
			this.imageUrl = imageUrl;
			this.fileUrl = fileUrl;
			this.sha256 = sha256;
			this.fileName = fileName;
			this.size = size;
			this.hosts = hosts;
			this.copies = copies;
		}

		/** The installer / index key: the Modrinth project id, or "pick:<id>" for a direct file. */
		public String key() {
			return source == Source.MODRINTH ? projectId : "pick:" + id;
		}

		public boolean supports(String gameVersion) {
			for (String v : family(gameVersion)) {
				if (versions.contains(v)) {
					return true;
				}
			}
			return false;
		}

		/** May this direct download (or a redirect of it) come from url? https + a pinned host only. */
		public boolean trustedFileUrl(String url) {
			try {
				URI u = URI.create(url);
				String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
				return "https".equalsIgnoreCase(u.getScheme()) && hosts.contains(host);
			} catch (RuntimeException e) {
				return false;
			}
		}
	}

	/**
	 * Versions one jar runs as one game: 26.1's module covers 26.1 - 26.1.2 (patch
	 * releases of one game), so a pick built for any of them shows on all three -
	 * the same rule ModrinthPacks.family uses for search on that module.
	 */
	static List<String> family(String gameVersion) {
		if ("26.1".equals(gameVersion) || "26.1.1".equals(gameVersion) || "26.1.2".equals(gameVersion)) {
			return Arrays.asList("26.1", "26.1.1", "26.1.2");
		}
		return Collections.singletonList(gameVersion);
	}

	/** Picks for this game version, in curated order. */
	public static List<Pick> forVersion(String gameVersion) {
		List<Pick> out = new ArrayList<>();
		for (Pick p : ALL) {
			if (p.supports(gameVersion)) {
				out.add(p);
			}
		}
		return out;
	}

	/** Styles that at least one of these picks has, in chip order. */
	public static List<Style> styles(List<Pick> picks) {
		Set<Style> on = new HashSet<>();
		for (Pick p : picks) {
			on.addAll(p.styles);
		}
		List<Style> out = new ArrayList<>();
		for (Style s : Style.values()) {
			if (on.contains(s)) {
				out.add(s);
			}
		}
		return out;
	}

	public static Pick byKey(String key) {
		for (Pick p : ALL) {
			if (p.key().equals(key)) {
				return p;
			}
		}
		return null;
	}

	// ---- what Browse's copy filter (PackDedupe) needs ----

	/** Modrinth project ids that are a pick's original. */
	public static Set<String> originals() {
		Set<String> out = new HashSet<>();
		for (Pick p : ALL) {
			if (p.source == Source.MODRINTH) {
				out.add(p.projectId);
			}
		}
		return out;
	}

	/** Modrinth project ids of known re-uploads. */
	public static Set<String> copies() {
		Set<String> out = new HashSet<>();
		for (Pick p : ALL) {
			out.addAll(p.copies);
		}
		return out;
	}

	/**
	 * Pick title (PackDedupe loose key) -> the original uploader, lower case. A
	 * Modrinth hit with that title from anyone else is a re-upload. Picks whose
	 * title is too generic to compare (key "") are left out.
	 */
	public static Map<String, String> titleOwners() {
		Map<String, String> out = new HashMap<>();
		for (Pick p : ALL) {
			String k = PackDedupe.looseKey(p.title);
			if (!k.isEmpty()) {
				String who = p.owner.isEmpty() ? p.author : p.owner;
				out.put(k, who.toLowerCase(Locale.ROOT));
			}
		}
		return out;
	}

	// ---- table builders (used only by the generated table below) ----

	private static List<String> words(String s) {
		if (s == null || s.trim().isEmpty()) {
			return Collections.emptyList();
		}
		return Collections.unmodifiableList(Arrays.asList(s.trim().split("\\s+")));
	}

	private static List<Style> styleList(Style... s) {
		return Collections.unmodifiableList(new ArrayList<>(new LinkedHashSet<>(Arrays.asList(s))));
	}

	private static Pick modrinth(String id, String title, String author, String about, String res, String credit,
			String projectId, String slug, String owner, String iconUrl, String imageUrl, String versions,
			String copies, Style... styles) {
		return new Pick(id, Source.MODRINTH, title, author, about, res, "Modrinth",
				"https://modrinth.com/resourcepack/" + slug, credit, styleList(styles), words(versions), projectId, slug,
				owner, iconUrl, imageUrl, "", "", "", 0, Collections.<String>emptyList(), words(copies));
	}

	private static Pick direct(String id, String title, String author, String about, String res, String credit,
			String sourceName, String pageUrl, String fileUrl, String sha256, long size, String fileName, String hosts,
			String versions, String copies, Style... styles) {
		return new Pick(id, Source.DIRECT, title, author, about, res, sourceName, pageUrl, credit, styleList(styles),
				words(versions), "", "", "", "", "", fileUrl, sha256.toLowerCase(Locale.ROOT), fileName, size,
				words(hosts), words(copies));
	}

	private static Pick link(String id, String title, String author, String about, String res, String credit,
			String sourceName, String pageUrl, String versions, String copies, Style... styles) {
		return new Pick(id, Source.LINK, title, author, about, res, sourceName, pageUrl, credit, styleList(styles),
				words(versions), "", "", "", "", "", "", "", "", 0, Collections.<String>emptyList(), words(copies));
	}

	private static final Style PVP = Style.PVP, BEDWARS = Style.BEDWARS, CLEAN = Style.CLEAN,
			COLOURFUL = Style.COLOURFUL, DARK_UI = Style.DARK_UI, FPS = Style.FPS, LOW_FIRE = Style.LOW_FIRE,
			CRYSTAL = Style.CRYSTAL, FAITHFUL = Style.FAITHFUL;

	// BEGIN PICKS (generated by tools/packs/verify_picks.py --write from tools/packs/picks.json)
	public static final List<Pick> ALL = Collections.unmodifiableList(Arrays.asList(
	));
	// END PICKS
}
