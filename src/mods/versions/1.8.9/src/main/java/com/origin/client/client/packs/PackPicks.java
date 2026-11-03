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
	public static final List<Pick> ALL = Collections.unmodifiableList(Arrays.<Pick>asList(
			link("bombies-180k", "Bombies 180k", "Tory", "The default-edit Bedwars pack most players start with: clean, readable, close to vanilla.", "16x", "Download on PvPRP", "PvPRP", "https://pvprp.com/pack?p=9566", "1.8.9", "", BEDWARS, PVP, CLEAN),
			link("bombies-80k", "Bombies 80k", "Tory", "Tory's earlier teal take on default with short swords, a Bedwars favourite.", "16x", "Download on PvPRP", "PvPRP", "https://pvprp.com/pack?p=345", "1.8.9", "q7GszAK0", BEDWARS, PVP, COLOURFUL),
			link("atlas-purple", "Atlas (Purple Original)", "Apexay", "A purple PvP pack with a calm custom look, a long-time Hypixel staple.", "16x", "Download on PvPRP", "PvPRP", "https://pvprp.com/pack?p=237", "1.8.9", "", PVP, COLOURFUL),
			modrinth("looshy", "looshy", "looshy", "A different take on default, simple and clean, made for PvP.", "", "All rights reserved", "pbipt9Sm", "looshy", "looshy", "https://cdn.modrinth.com/data/pbipt9Sm/204815b5134234f1bb13c59541b825d93c4c5660_96.webp", "https://cdn.modrinth.com/data/pbipt9Sm/images/d4df9d9a3076ba5730f0a8e15e6f369a95fd6a84.png", "26.2 26.1 26.1.1 26.1.2 1.21.1 1.21 1.21.2 1.21.3 1.21.4 1.21.6 1.21.7 1.21.8 1.21.10 1.21.11 1.8.9", "", PVP, CLEAN),
			modrinth("prism-original", "prism - original", "looshy", "looshy's vibrant blue pack with a custom sky, a Bedwars staple.", "", "All rights reserved", "o5FS3VdX", "prism-original", "looshy", "https://cdn.modrinth.com/data/o5FS3VdX/6f85e4be1b93bbc74bee501164edf4e1786f8751_96.webp", "https://cdn.modrinth.com/data/o5FS3VdX/images/98f2443677d9c4e78d893033e8bd80f62e3283a1_350.webp", "1.21.1 1.21 1.21.2 1.21.3 1.21.4 1.21.6 1.21.7 1.21.8 1.21.10 1.8.9", "", BEDWARS, PVP, COLOURFUL),
			modrinth("defrosted", "defrosted", "looshy", "A clean, cool-toned look for PvP from looshy.", "", "All rights reserved", "NPzwNDRa", "defrosted_pack", "looshy", "https://cdn.modrinth.com/data/NPzwNDRa/d03b8d5eb2cec7ad45b5c9e49e220da76fd58e0c_96.webp", "https://cdn.modrinth.com/data/NPzwNDRa/images/a95f6348dc7b233bce377547764e66ad2aeb373e_350.webp", "26.2 26.1 26.1.1 26.1.2 1.21.1 1.21 1.21.2 1.21.3 1.21.4 1.21.6 1.21.7 1.21.8 1.21.10 1.21.11 1.8.9", "", PVP, CLEAN, COLOURFUL),
			modrinth("better-bedwars", "Better Bedwars.", "ColinMcCloud", "Simplifies Bedwars while keeping the original look and feel of Minecraft.", "", "All rights reserved", "2v0hBlZE", "betterbedwars", "ColinMcCloud", "https://cdn.modrinth.com/data/2v0hBlZE/b1c6e4aecfea9d8d4046c7a43a88f16d1840ee40.png", "https://cdn.modrinth.com/data/2v0hBlZE/images/b162afae031d5f288f44f49e00a83a1b5181dc13_350.webp", "1.8.9", "", BEDWARS, CLEAN),
			modrinth("simple-bedwars", "Simple Bedwars", "Cat_125", "Simple textures for Bedwars: clear wool, blocks and items.", "", "GPL-3.0", "U3iw4v7n", "simple-bedwars", "Cat_125", "https://cdn.modrinth.com/data/U3iw4v7n/44b8089d1efa4a5659abde9a65c1ffa3eebb7d3c.png", "https://cdn.modrinth.com/data/U3iw4v7n/images/a68a4ea5bff2db20e252923c3655610e7e2c9599_350.webp", "1.21.1 1.21 1.21.2 1.21.3 1.21.4 1.21.6 1.21.7 1.21.8 1.21.10 1.21.11 1.20 1.20.1 1.20.2 1.20.4 1.8.9", "", BEDWARS, CLEAN),
			modrinth("bedwars-plus", "BedWars+ (Hypixel)", "DMgaming", "Gives Hypixel Bedwars items and shop entries their own clear textures.", "", "All rights reserved", "aAtMYuHh", "bedwars-plus", "DMgaming", "https://cdn.modrinth.com/data/aAtMYuHh/1fef071dd9ebe64b206f48014f70d8b4b30af712.png", "https://cdn.modrinth.com/data/aAtMYuHh/images/9d1a49fc3a290278326263906d69cf705fe5ec23_350.webp", "26.3 26.2 26.1 26.1.1 26.1.2 1.8.9", "", BEDWARS),
			modrinth("bedwars-tp", "Bedwars TP", "LostGPU", "Clean Bedwars pack with short swords, a tidy GUI, low fire and clear blocks.", "", "All rights reserved", "Wylp0efr", "bedwarstp", "LostGPU", "https://cdn.modrinth.com/data/Wylp0efr/63b1bc6a614ba46b98fc4f8be05580073fe48401_96.webp", "https://cdn.modrinth.com/data/Wylp0efr/images/b2b6a87b4ecfa113f1c0b2a1f1ecb21eb88dbbdb_350.webp", "26.3 26.2 26.1 26.1.1 26.1.2 1.21.1 1.21 1.21.2 1.21.3 1.21.4 1.21.6 1.21.7 1.21.8 1.21.10 1.21.11 1.20 1.20.1 1.20.2 1.20.4 1.19.4 1.19.3 1.19.2 1.18.2 1.17.1 1.16.5 1.8.9", "", BEDWARS, LOW_FIRE),
			modrinth("ez-bedwars", "EZ Bedwars", "Polr", "A modern-version Bedwars pack built for sweaty games.", "", "All rights reserved", "6DvvPRlC", "pack-bedwars", "Polr", "https://cdn.modrinth.com/data/6DvvPRlC/90a7f67b1cdc56fb3bd152db3e27eb3fe471b376_96.webp", "https://cdn.modrinth.com/data/6DvvPRlC/images/bbe70947f398bcbf86e99aeaaf58765685d822e2_350.webp", "26.1 26.1.1 26.1.2 1.21.2 1.21.3 1.21.4 1.21.6 1.21.7 1.21.8 1.21.10 1.21.11", "", BEDWARS, PVP),
			modrinth("aerox", "AEROX PVP 16x", "swarties", "A clean 16x PvP pack for 1.8 / 1.7.", "", "MIT", "Or7JWpp6", "aerox-16x", "swarties", "https://cdn.modrinth.com/data/Or7JWpp6/d7ac66587b231dce82cca7977cf58a88770d5c1f.png", "https://cdn.modrinth.com/data/Or7JWpp6/images/12fbfbbb91b2613959a9feb9867bd3c0c165eb70.webp", "1.8.9", "", PVP, CLEAN),
			modrinth("spunky-pvp", "Spunky PVP Texture Pack", "spunkyinsaan", "Spunky Insaan's custom PvP pack, one of the most downloaded on modern versions.", "", "All rights reserved", "I1HDemew", "spunky-pvp-texture-pack", "spunkyinsaan", "https://cdn.modrinth.com/data/I1HDemew/6c6f9cd115b65efd455b0cb03257ce987a7238f5_96.webp", "https://cdn.modrinth.com/data/I1HDemew/images/f79078089eec4cd95907364b359561954def49f1_350.webp", "26.3 26.2 26.1 26.1.1 26.1.2 1.21.1 1.21 1.21.2 1.21.3 1.21.4 1.21.6 1.21.7 1.21.8 1.21.10 1.21.11 1.20 1.20.1 1.20.2 1.20.4 1.19.4 1.19.3 1.19.2 1.18.2 1.17.1 1.16.5", "", PVP, COLOURFUL),
			modrinth("apex-pvp", "Apex PvP", "sniffercraft34", "A clean, vanilla-style PvP pack that the creator says boosts FPS.", "", "MIT", "BrYURyEb", "apex-pvp", "sniffercraft34", "https://cdn.modrinth.com/data/BrYURyEb/49b7f0fc875758bbb6b9428f7cfb050d6e0d8278_96.webp", "https://cdn.modrinth.com/data/BrYURyEb/images/8cf7948fa444e6cd1f7214748217143381836ec9_350.webp", "26.2 26.1 26.1.1 26.1.2 1.21.1 1.21 1.21.2 1.21.3 1.21.4 1.21.6 1.21.7 1.21.8 1.21.10 1.21.11 1.20 1.20.1 1.20.2 1.20.4 1.19.4 1.19.3 1.19.2 1.18.2 1.8.9", "", PVP, CLEAN, FPS),
			modrinth("huh-8x", "Huh 8x", "HuhResourcePacks", "An 8x8 pixel PvP pack for Bedwars and duels, light on your graphics card.", "8x", "MIT", "gQguz52y", "huh-8x", "HuhResourcePacks", "https://cdn.modrinth.com/data/gQguz52y/0634da766dd035e1ab5a918193b686bc46999d33.png", "https://cdn.modrinth.com/data/gQguz52y/images/0634da766dd035e1ab5a918193b686bc46999d33.png", "1.8.9", "", FPS, PVP, BEDWARS),
			modrinth("bare-bones", "Bare Bones", "RobotPants", "Minecraft in the flat, simple style of its trailers.", "", "All rights reserved, no re-uploads", "rox3U8B6", "bare-bones", "RobotPants", "https://cdn.modrinth.com/data/rox3U8B6/b502bd8b2a6048f635230da8e8a66eeb4383874b_96.webp", "https://cdn.modrinth.com/data/rox3U8B6/images/08d508fd38c9ed1dd73e20f2b69c2b709eef8524_350.webp", "1.21.1 1.21 1.21.2 1.21.3 1.21.4 1.21.6 1.21.7 1.21.8 1.21.10 1.21.11 1.20 1.20.1 1.20.2 1.20.4 1.19.4 1.19.3 1.19.2 1.18.2 1.17.1 1.16.5 1.8.9", "", CLEAN, FPS),
			modrinth("faithful-32x", "Faithful 32x", "Faithful", "The original Minecraft look at double the resolution.", "32x", "Faithful License: credit Faithful", "w0TnApzs", "faithful-32x", "Faithful-Resource-Pack", "https://cdn.modrinth.com/data/w0TnApzs/e8403d1fb2f55321ae74402c1e8c90a3a5670856.png", "https://cdn.modrinth.com/data/w0TnApzs/images/8326664336340fe1919056f86d8d6009d06ad2e8_350.webp", "26.3 26.2 26.1 26.1.1 26.1.2 1.21.1 1.21 1.21.2 1.21.3 1.21.4 1.21.6 1.21.7 1.21.8 1.21.10 1.21.11 1.20 1.20.1 1.20.2 1.20.4 1.19.4 1.19.3 1.19.2 1.18.2 1.17.1 1.16.5 1.8.9", "", FAITHFUL),
			modrinth("faithful-64x", "Faithful 64x", "Faithful", "Faithful at four times the resolution, even more detail.", "64x", "Faithful License: credit Faithful", "r4GILswZ", "faithful-64x", "Faithful-Resource-Pack", "https://cdn.modrinth.com/data/r4GILswZ/9ff7d3c66d6a263c158c2ef034b0361fdfbf354b.png", "https://cdn.modrinth.com/data/r4GILswZ/images/336b7507a4e27b3abb29ecb678bdf3055b3f7015_350.webp", "26.3 26.2 26.1 1.21.1 1.21 1.21.2 1.21.3 1.21.4 1.21.7 1.21.8 1.21.10 1.21.11 1.20.1 1.20.2 1.20.4 1.19.4 1.19.3 1.19.2", "", FAITHFUL),
			modrinth("low-on-fire", "Low On Fire", "Haikis", "Lowers the fire on your screen so you can see while burning.", "", "All rights reserved", "RRxvWKNC", "low-on-fire", "Haikis", "https://cdn.modrinth.com/data/RRxvWKNC/06a7e7691a6da41798c255108a8563d7ffea171d.png", "https://cdn.modrinth.com/data/RRxvWKNC/images/67e073e6149cfb9dd46c7a53cf9b8a995f774595_350.webp", "26.3 26.2 26.1 26.1.1 26.1.2 1.21.1 1.21 1.21.2 1.21.3 1.21.4 1.21.6 1.21.7 1.21.8 1.21.10 1.21.11 1.20 1.20.1 1.20.2 1.20.4 1.19.4 1.19.2 1.18.2 1.17.1 1.16.5 1.8.9", "", LOW_FIRE),
			modrinth("simple-short-swords", "Simple Short Swords", "sniffercraft34", "Short swords that still look like they belong in the game.", "", "MIT", "J5FbJC7Z", "simple-short-swords", "sniffercraft34", "https://cdn.modrinth.com/data/J5FbJC7Z/6e8100c48d879f367ef16a9982c051f50227c3a9_96.webp", "https://cdn.modrinth.com/data/J5FbJC7Z/images/220f6e7239b32238c2a56d3dd1190f204f22157c_350.webp", "26.2 26.1 1.21.1 1.21 1.21.2 1.21.3 1.21.4 1.21.6 1.21.7 1.21.8 1.21.10 1.21.11 1.20 1.20.1 1.20.2 1.20.4 1.19.4 1.19.3 1.19.2 1.18.2 1.8.9", "", LOW_FIRE),
			modrinth("low-shield", "Low Shield", "Oculie", "Lowers your shield for a clearer view while blocking.", "", "All rights reserved", "CZrLuVQo", "low-shield-pack", "Oculie", "https://cdn.modrinth.com/data/CZrLuVQo/4ae626dba10d5bcc73f9a7b0a50c22e572075b6b.png", "https://cdn.modrinth.com/data/CZrLuVQo/images/d5c7c07ef008e0a82f5786a17eb45ca30a1d5f3e_350.webp", "26.3 26.2 26.1 26.1.1 26.1.2 1.21.1 1.21 1.21.2 1.21.3 1.21.4 1.21.6 1.21.7 1.21.8 1.21.10 1.21.11 1.20 1.20.1 1.20.2 1.20.4", "", LOW_FIRE),
			modrinth("short-swords", "Short Swords", "Oculie", "Shorter swords in your hand so they block less of the fight.", "", "CC BY 4.0", "GExZOLXP", "short-swords-pack", "Oculie", "https://cdn.modrinth.com/data/GExZOLXP/fe97fdfe85bb61087300652ea1479c173d2419e7_96.webp", "https://cdn.modrinth.com/data/GExZOLXP/images/59f12907a278ec63057e1472213d0216c30b155d_350.webp", "26.3 26.2 26.1 26.1.1 26.1.2 1.21.1 1.21 1.21.2 1.21.3 1.21.4 1.21.6 1.21.7 1.21.8 1.21.10 1.21.11 1.20 1.20.1 1.20.2 1.20.4", "", LOW_FIRE),
			modrinth("small-shield-totem", "Small Shield & Totem", "blobh", "A smaller shield and totem in your hand for a clearer view.", "", "CC BY-NC-ND 4.0", "LSBNL0rO", "small-shield-totem", "blobh", "https://cdn.modrinth.com/data/LSBNL0rO/7b77259d80a72e4f6c9650a47ffd427b5c14aeb5_96.webp", "https://cdn.modrinth.com/data/LSBNL0rO/images/9184e05063ea41e4173cccb31964ffa046db35ad.gif", "26.3 26.2 26.1 26.1.1 26.1.2 1.21.1 1.21 1.21.2 1.21.3 1.21.4 1.21.6 1.21.7 1.21.8 1.21.10 1.21.11 1.20 1.20.1 1.20.2 1.20.4 1.19.4 1.19.3 1.19.2 1.18.2 1.17.1 1.16.5", "", CRYSTAL, LOW_FIRE),
			modrinth("dripped-pvp", "Dripped PvP", "CapTommy", "A Crystal PvP pack: get dripped out for your next fight.", "", "MIT", "r1IbSpu7", "dripped-pvp", "CapTommy", "https://cdn.modrinth.com/data/r1IbSpu7/e3fe3460c47e3f858b9feab981bf6ab2ddbd7646_96.webp", "https://cdn.modrinth.com/data/r1IbSpu7/images/cde3ce45992ee65752dbc73e87d58a91e9b87b4f.gif", "26.3 26.2 26.1 26.1.1 26.1.2 1.21.1 1.21 1.21.2 1.21.3 1.21.4 1.21.6 1.21.7 1.21.8 1.21.10 1.21.11 1.20 1.20.1", "", CRYSTAL, COLOURFUL),
			modrinth("vertexvirtual", "VertexVirtual", "CarbonO2", "A PvP pack for 1.21 and newer: Crystal and sword PvP, small totems, short swords.", "64x", "All rights reserved", "SW9oThlk", "vertexvirtual", "CarbonO2", "", "https://cdn.modrinth.com/data/SW9oThlk/images/5aca9bd44ff92f4c5fae6e285f7d4141a80a0cfc_350.webp", "26.3 26.2 26.1 26.1.1 26.1.2 1.21.1 1.21 1.21.2 1.21.3 1.21.4 1.21.6 1.21.7 1.21.8 1.21.10 1.21.11", "", CRYSTAL, PVP),
			modrinth("cpvp-essentials", "CPVP Essentials ( Crystal PvP )", "agent-drl3x2015", "Crystal PvP essentials: clearer crystals, anchors, totems and more.", "", "All rights reserved", "ih5VCK3n", "crystal-pvp-lt3-essentials", "agent-drl3x2015", "https://cdn.modrinth.com/data/ih5VCK3n/6bd43f541a03d9a9824f536301451c9604c2ab82.png", "https://cdn.modrinth.com/data/ih5VCK3n/images/6569f660eb9a9197e8c8edeae1c62394c4bd5a90_350.webp", "26.2 26.1 26.1.1 26.1.2 1.21.1 1.21 1.21.2 1.21.3 1.21.4 1.21.6 1.21.7 1.21.8 1.21.10 1.21.11 1.20 1.20.1 1.20.2 1.20.4 1.19.4 1.19.3 1.19.2 1.18.2 1.17.1 1.16.5", "", CRYSTAL, PVP),
			modrinth("crystal-pvp-tweaks", "Crystal PvP Tweaks", "Gamdev4", "Optimized, clear, precise tweaks for Crystal PvP.", "", "CC BY-NC-SA 4.0", "3UNyeKHE", "crystal-pvp-tweaks", "Gamdev4", "https://cdn.modrinth.com/data/3UNyeKHE/cd87d2c7682cf5f9a9d097ea7affaaaf800ebad2_96.webp", "https://cdn.modrinth.com/data/3UNyeKHE/images/c4d6a2459c50ac947f8d5e4c8b1a9a98c2968645_350.webp", "1.21.1 1.21 1.21.2 1.21.3 1.21.4 1.21.6 1.21.7 1.21.8 1.21.10 1.21.11 1.20 1.20.1 1.20.2 1.20.4 1.19.4 1.19.2 1.18.2 1.17.1 1.16.5", "", CRYSTAL, FPS),
			modrinth("bluxy-crystal", "Bluxy-Crystal (Crystal PvP)", "Bluxy", "A modern, smooth, sleek Crystal PvP pack.", "", "All rights reserved", "gL7bDgo2", "bluxycrystal", "Bluxy", "https://cdn.modrinth.com/data/gL7bDgo2/4aaf77c36492a3f239780a3e859a74e41f409ba2_96.webp", "https://cdn.modrinth.com/data/gL7bDgo2/images/b84643fe24436b4bee39be05e7423efcf322f5ac.gif", "1.21.1 1.21 1.21.2 1.21.3 1.21.4 1.21.6 1.21.7 1.21.8 1.21.10 1.21.11 1.20.2 1.20.4", "", CRYSTAL, COLOURFUL),
			modrinth("default-dark-mode", "Default Dark Mode", "nebulr", "Dark menus and inventories in Minecraft's own style.", "", "CC BY-NC-SA 4.0", "6SLU7tS5", "default-dark-mode", "nebulr", "https://cdn.modrinth.com/data/6SLU7tS5/f90001e4ebed860e7ae913f34c85d745f516a89b.png", "https://cdn.modrinth.com/data/6SLU7tS5/images/72dad114b1d88964780d933d83e31e382c37cc48_350.webp", "26.3 26.2 26.1 26.1.1 26.1.2 1.21.1 1.21 1.21.2 1.21.3 1.21.4 1.21.6 1.21.7 1.21.8 1.21.10 1.21.11 1.20 1.20.1 1.20.2 1.20.4 1.19.4 1.19.3 1.19.2 1.18.2 1.17.1 1.16.5 1.8.9", "", DARK_UI),
			modrinth("mandala-dark", "Mandala's GUI - Dark mode", "CesarZorak", "An elegant dark theme for every menu and inventory.", "", "All rights reserved", "h6zxsNVF", "mandalas-gui-dark-mode", "CesarZorak", "https://cdn.modrinth.com/data/h6zxsNVF/458fb1c1a7c10c19f7a4a58e99defb73e54fbc93.png", "https://cdn.modrinth.com/data/h6zxsNVF/images/7dde2c7aef393e1076ca8815ffa94145c65937cd_350.webp", "1.21.1 1.21 1.21.2 1.21.3 1.21.4 1.21.6 1.21.7 1.21.8 1.21.10 1.21.11 1.20 1.20.1 1.20.2 1.20.4 1.19.4 1.19.3 1.19.2 1.18.2 1.17.1 1.16.5", "", DARK_UI),
			link("vanilla-tweaks", "Vanilla Tweaks", "Vanilla Tweaks team", "Build your own pack on their site: lower fire, shorter swords, clearer UI and more.", "", "Built on vanillatweaks.net", "Vanilla Tweaks", "https://vanillatweaks.net/picker/resource-packs/", "1.16.5 1.17.1 1.18.2 1.19.2 1.19.3 1.19.4 1.20 1.20.1 1.20.2 1.20.4 1.21 1.21.1 1.21.2 1.21.3 1.21.4 1.21.6 1.21.7 1.21.8 1.21.10 1.21.11", "", CLEAN, LOW_FIRE)
	));
	// END PICKS
}
