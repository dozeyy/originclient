package com.origin.client.client.shaders;

import java.io.File;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

// Which shader packs Origin offers on 1.8.9, and which installed packs it warns
// about. Pure Java (no Minecraft types) so tools/check-shader-allowlist.java can
// compile and test it on its own.
//
// WHY AN ALLOWLIST: Modrinth tags a pack "1.8.9 + optifine" when its zip merely
// loads there, so the old browser (every 1.8.9-tagged pack) offered Complementary
// r5.9.3, which on OptiFine HD U M5 logs hundreds of "ambiguous shader option"
// warnings, takes ~12 s to load and lags hard (Will's 2026-10-20 log).
//
// THE RULE a pack must pass to get a row below (checked 2026-10-20 on the exact
// zip pinned here, by unzipping it):
//   1. Modrinth lists it as a release with the optifine loader, and its author's
//      own version tags reach back to the 1.7/1.8 era (made for old OptiFine,
//      not just allowed to load on it).
//   2. Every core program (.fsh/.vsh) declares GLSL #version 120 or 130 -- the
//      era OptiFine 1.8.9 targets. (LIGHT's four #version 330 programs are its
//      Physics Mod ocean files, which never load on 1.8.9.)
//   3. Not the Complementary engine family (130 + ~190 .glsl includes: the pack
//      that lagged), and no #version 150+/400 programs.
// Rejected on this rule, with the reason, so nobody re-adds them by hand:
//   Complementary Reimagined/Unbound, Spooklementary, PixelCraft, Snowimagined
//     (Complementary engine); Mellow, Hysteria (#version 400);
//   DrDestens, Stracciatella (#version 150); BVS, Cursed Fog (130, colortex-only
//     naming, unverified on M5); BSL 10 (full) (heavy; BSL Classic is its old-GPU
//     edition); Insanity (author recommends Iris); Tea (Dev build).
//
// Each row pins ONE exact Modrinth file: version id, filename, CDN url, size and
// SHA-256 (the download is refused on a mismatch). Nothing is resolved "latest".
public final class ShaderCompat {
	private ShaderCompat() {
	}

	/** The game version this table is for. */
	public static final String GAME_VERSION = "1.8.9";

	/** The one-line plain-language warning (Will's wording). */
	public static final String WARNING =
			"This pack is made for newer Minecraft and will lag or not work on " + GAME_VERSION + ".";

	/** One allowlisted pack = one pinned file. */
	public static final class Pack {
		public final String id;          // unique row key (download state, UI)
		public final String name;
		public final String desc;
		public final String slug;        // Modrinth project, for the preview image
		public final String versionId;   // Modrinth version id (documentation)
		public final String fileName;    // exact name written into shaderpacks/
		public final String url;         // pinned CDN url
		public final long size;
		public final String sha256;

		Pack(String id, String name, String desc, String slug, String versionId,
				String fileName, String url, long size, String sha256) {
			this.id = id;
			this.name = name;
			this.desc = desc;
			this.slug = slug;
			this.versionId = versionId;
			this.fileName = fileName;
			this.url = url;
			this.size = size;
			this.sha256 = sha256;
		}
	}

	/** The allowlist: data, one row per pack. */
	public static final Pack[] ALLOWED = {
			new Pack("sildurs-vibrant-lite", "Sildur's Vibrant Lite",
					"The classic, built for OptiFine. Light on any hardware",
					"sildurs-vibrant-shaders", "cianYi38",
					"Sildur's Vibrant Shaders v2.02 Lite.zip",
					"https://cdn.modrinth.com/data/z8EjLYqN/versions/cianYi38/Sildur%27s%20Vibrant%20Shaders%20v2.02%20Lite.zip",
					251380L, "16746ee127e4c0b07f7e62018589100845338e633eea6bed8cf8c09939aa9eea"),
			new Pack("sildurs-vibrant-medium", "Sildur's Vibrant Medium",
					"The classic with more shadows and glow",
					"sildurs-vibrant-shaders", "EisvxIzu",
					"Sildur's Vibrant Shaders v2.02 Medium.zip",
					"https://cdn.modrinth.com/data/z8EjLYqN/versions/EisvxIzu/Sildur%27s%20Vibrant%20Shaders%20v2.02%20Medium.zip",
					251385L, "01e00ab86fbfb889e016a8ce533bfced1b6781346b125f9f613c3696128d1520"),
			new Pack("light-shaders", "LIGHT Shaders",
					"High FPS with painting-like quality",
					"light-shaders", "QeFC9NtQ",
					"LIGHT-Shaders-1.9.0.zip",
					"https://cdn.modrinth.com/data/UuwCHIS3/versions/QeFC9NtQ/LIGHT-Shaders-1.9.0.zip",
					176615L, "1fc0b190885b5dfa8b7a9326e26b4f707b429b2116d55d8817e5e21ce122161f"),
			new Pack("miniature-shader", "Miniature Shader",
					"Ultra-light with shadows and reflections",
					"miniature-shader", "LWmZ94RG",
					"miniature-shader-2.19.zip",
					"https://cdn.modrinth.com/data/UaS8ROxa/versions/LWmZ94RG/miniature-shader-2.19.zip",
					43913L, "5f224e907657f3ffd95d37e37c27e555deef64fa56da1e13374f2c295241d7f0"),
			new Pack("redhat-shaders", "RedHat Shaders",
					"Based on classic Chocapic13 shaders",
					"redhat-shaders", "Ca8OxUZj",
					"RedHat_v34.3.1.zip",
					"https://cdn.modrinth.com/data/ZvitaqQh/versions/Ca8OxUZj/RedHat_v34.3.1.zip",
					194767L, "8185bc8ecbba05f0f12c70bbe45531b3de3afb4491fc79e651006df2d45c2611"),
			new Pack("lux-v1", "Lux V1",
					"High-fidelity pack based on BSL",
					"lux-v1", "olEssKhO",
					"Lux_v1.3.zip",
					"https://cdn.modrinth.com/data/DiGQENDV/versions/olEssKhO/Lux_v1.3.zip",
					294036L, "e75a5577f54b1c5d9317ad829c23adb249018f12a13f8aec58943a127c553d2f"),
			new Pack("bsl-shaders-classic", "BSL Shaders Classic",
					"Vanilla-oriented classic BSL edition",
					"bsl-shaders-classic", "a05qYRrG",
					"BSLClassic_v10.0.zip",
					"https://cdn.modrinth.com/data/pgzM6RBY/versions/a05qYRrG/BSLClassic_v10.0.zip",
					1121691L, "5dea97cb74ddc66af82f0a7d1405556757fa85eb9cbacab22a8ed0612722d11b"),
	};

	/** Packs known to be built for newer Minecraft (matched in the pack's name). */
	private static final String[] KNOWN_MODERN = {
			"complementary", "spooklementary", "reimagined", "unbound", "photon", "solas",
			"euphoria", "rethinking", "bliss", "mellow", "hysteria", "snowimagined",
			"pixelcraft", "drdesten", "stracciatella",
	};

	/** Highest GLSL #version OptiFine 1.8.9 era packs use. Newer = built for modern OptiFine/Iris. */
	public static final int MAX_LEGACY_GLSL = 130;

	public enum Verdict { OK, INCOMPATIBLE, UNKNOWN }

	/** The pack rows to show for a game version: the allowlist on 1.8.9, nothing otherwise. */
	public static Pack[] offeredFor(String gameVersion) {
		return GAME_VERSION.equals(gameVersion) ? ALLOWED : new Pack[0];
	}

	/** True when a Modrinth project/file may be offered or installed on this game version. */
	public static boolean isAllowedFile(String fileName, String gameVersion) {
		if (!GAME_VERSION.equals(gameVersion)) {
			return true; // other versions are not this table's business
		}
		String n = stem(fileName);
		for (Pack p : ALLOWED) {
			if (stem(p.fileName).equals(n)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Classifies an installed/selected pack. probedMaxVersion is the highest core
	 * #version found inside it (0 = not probed / unreadable). Only OK (our own
	 * allowlisted file) and INCOMPATIBLE (known-modern name, or a core program
	 * newer than GLSL 130) are definite; anything else is UNKNOWN and gets no
	 * warning, because it may be a legitimate pack the player brought.
	 */
	public static Verdict classify(String packName, int probedMaxVersion, String gameVersion) {
		if (!GAME_VERSION.equals(gameVersion) || packName == null || packName.trim().isEmpty()) {
			return Verdict.UNKNOWN;
		}
		if (isAllowedFile(packName, gameVersion)) {
			return Verdict.OK;
		}
		String lower = packName.toLowerCase(Locale.ROOT);
		for (String k : KNOWN_MODERN) {
			if (lower.contains(k)) {
				return Verdict.INCOMPATIBLE;
			}
		}
		return probedMaxVersion > MAX_LEGACY_GLSL ? Verdict.INCOMPATIBLE : Verdict.UNKNOWN;
	}

	/** The warning for a pack, or null when it needs none. Never touches the pack's files. */
	public static String warningFor(String packName, int probedMaxVersion, String gameVersion) {
		return classify(packName, probedMaxVersion, gameVersion) == Verdict.INCOMPATIBLE ? WARNING : null;
	}

	private static String stem(String fileName) {
		String s = fileName == null ? "" : fileName.trim().toLowerCase(Locale.ROOT);
		return s.endsWith(".zip") ? s.substring(0, s.length() - 4) : s;
	}

	// ---- reading a pack (read-only) ----

	private static final Pattern VERSION = Pattern.compile("#version\\s+(\\d+)");

	/**
	 * Highest GLSL #version declared by the pack's core programs (.fsh/.vsh, not
	 * include fragments and not the Physics Mod ocean files). 0 when the pack
	 * can't be read. Reads only; never writes or deletes.
	 */
	public static int probeMaxVersion(File pack) {
		int max = 0;
		try {
			if (pack.isDirectory()) {
				File[] files = new File(pack, "shaders").listFiles();
				if (files != null) {
					for (File f : files) {
						if (isProgram(f.getName())) {
							max = Math.max(max, versionOf(java.nio.file.Files.readAllBytes(f.toPath())));
						}
					}
				}
			} else {
				try (ZipFile z = new ZipFile(pack)) {
					Enumeration<? extends ZipEntry> en = z.entries();
					while (en.hasMoreElements()) {
						ZipEntry e = en.nextElement();
						String n = e.getName();
						// only the top-level shaders/ folder; sub-folders hold includes
						if (e.isDirectory() || !n.startsWith("shaders/") || n.indexOf('/', 8) >= 0
								|| !isProgram(n.substring(8))) {
							continue;
						}
						try (InputStream in = z.getInputStream(e)) {
							max = Math.max(max, versionOf(readHead(in)));
						}
					}
				}
			}
		} catch (Throwable t) {
			return 0;
		}
		return max;
	}

	private static boolean isProgram(String name) {
		String l = name.toLowerCase(Locale.ROOT);
		return (l.endsWith(".fsh") || l.endsWith(".vsh")) && !l.startsWith("physics_ocean");
	}

	private static byte[] readHead(InputStream in) throws java.io.IOException {
		byte[] buf = new byte[4096];
		int n = in.read(buf);
		return n <= 0 ? new byte[0] : java.util.Arrays.copyOf(buf, n);
	}

	private static int versionOf(byte[] head) {
		Matcher m = VERSION.matcher(new String(head, java.nio.charset.StandardCharsets.ISO_8859_1));
		return m.find() ? Integer.parseInt(m.group(1)) : 0;
	}

	// ---- the selected pack, checked off the game thread ----

	private static volatile String pendingWarning;
	private static volatile boolean checkStarted;

	/**
	 * Starts (once per session) a background check of the pack OptiFine has
	 * selected; the result is picked up with takeWarning(). Daemon thread, so the
	 * render/tick thread never reads a zip.
	 */
	public static void startSelectedCheck(final String selectedName, final File packsDir) {
		if (checkStarted || selectedName == null) {
			return;
		}
		checkStarted = true;
		Thread t = new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					int probed = 0;
					if (classify(selectedName, 0, GAME_VERSION) == Verdict.UNKNOWN) {
						probed = probeMaxVersion(new File(packsDir, selectedName));
					}
					pendingWarning = warningFor(selectedName, probed, GAME_VERSION);
				} catch (Throwable ignored) {
					// no warning is better than a crash
				}
			}
		}, "origin-shader-compat");
		t.setDaemon(true);
		t.start();
	}

	/** The warning once the background check found one (returned a single time), else null. */
	public static String takeWarning() {
		String w = pendingWarning;
		pendingWarning = null;
		return w;
	}
}
