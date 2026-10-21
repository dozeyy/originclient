import com.origin.client.client.hud.FrameStats;
import com.origin.client.client.hud.FrameStatsProbe;
import com.origin.client.client.packs.ModrinthPacks;
import com.origin.client.client.packs.PackFiles;
import com.origin.client.client.packs.PackIndex;
import com.origin.client.client.packs.PackInstallerProbe;
import com.origin.client.client.packs.PackLibrary;
import com.origin.client.client.packs.PackOrder;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

// Unit tests for the Resource Pack Hub's Minecraft-free logic (packs/ModrinthPacks,
// PackFiles, PackIndex, PackInstaller, PackOrder). Run: python tools/packhub-tests/run.py
// Plain main() + checks so it needs nothing but a JDK and Gson.
public final class PackHubTests {
	private static int passed = 0, failed = 0;

	private static void check(boolean ok, String what) {
		if (ok) {
			passed++;
		} else {
			failed++;
			System.out.println("FAIL: " + what);
		}
	}

	private static void eq(Object want, Object got, String what) {
		check(java.util.Objects.equals(want, got), what + " (want " + want + ", got " + got + ")");
	}

	public static void main(String[] args) throws Exception {
		fileNames();
		mcmeta();
		zips();
		search();
		versions();
		index();
		order();
		installer();
		library();
		frameStats();
		System.out.println(passed + " passed, " + failed + " failed");
		if (failed > 0) {
			System.exit(1);
		}
	}

	static void fileNames() throws Exception {
		eq("Faithful 32x.zip", PackFiles.safeFileName("Faithful 32x.zip"), "plain name kept");
		eq("evil.zip", PackFiles.safeFileName("../../evil.zip"), "traversal stripped");
		eq("evil.zip", PackFiles.safeFileName("..\\..\\evil.zip"), "windows traversal stripped");
		eq("a_b_.zip", PackFiles.safeFileName("a:b?"), "illegal chars + .zip added");
		eq("pack_CON.zip", PackFiles.safeFileName("CON.zip"), "reserved device name");
		eq("pack.zip", PackFiles.safeFileName(""), "empty name");
		eq("pack.zip", PackFiles.safeFileName("..."), "dots only");
		check(PackFiles.safeFileName("x".repeat(500)).length() <= 120, "length capped");
		Path dir = Files.createTempDirectory("ph");
		try {
			PackFiles.inside(dir, "../x.zip");
			check(false, "inside() must refuse ..");
		} catch (java.io.IOException expected) {
			check(true, "inside() refuses ..");
		}
		Files.writeString(dir.resolve("a.zip"), "x");
		eq("a (2).zip", PackFiles.uniqueDestination(dir, "a.zip").getFileName().toString(), "unique name");
		eq("b.zip", PackFiles.uniqueDestination(dir, "b.zip").getFileName().toString(), "free name kept");
	}

	static void mcmeta() {
		var a = PackFiles.parseMcmeta("{\"pack\":{\"pack_format\":34,\"description\":\"§aHi\"}}");
		check(a.ok() && a.format() == 34 && a.supports(34) && !a.supports(48), "plain format");
		eq("Hi", a.description(), "formatting codes stripped");
		var b = PackFiles.parseMcmeta("{\"pack\":{\"pack_format\":15,\"supported_formats\":[15,34]}}");
		check(b.supports(34) && b.supports(22) && !b.supports(35), "supported_formats array");
		var c = PackFiles.parseMcmeta("{\"pack\":{\"pack_format\":32,\"supported_formats\":{\"min_inclusive\":30,\"max_inclusive\":40}}}");
		check(c.supports(34) && !c.supports(41), "supported_formats object");
		var d = PackFiles.parseMcmeta("﻿{\"pack\":{\"pack_format\":34,\"description\":{\"text\":\"A\",\"extra\":[{\"text\":\"B\"}]}}}");
		check(d.ok(), "BOM tolerated");
		eq("AB", d.description(), "component description");
		check(!PackFiles.parseMcmeta("{}").ok(), "missing pack section");
		check(!PackFiles.parseMcmeta("nope").ok(), "not json");
	}

	static byte[] zip(String... nameThenContent) throws Exception {
		ByteArrayOutputStream bo = new ByteArrayOutputStream();
		try (ZipOutputStream z = new ZipOutputStream(bo)) {
			for (int i = 0; i < nameThenContent.length; i += 2) {
				z.putNextEntry(new ZipEntry(nameThenContent[i]));
				z.write(nameThenContent[i + 1].getBytes(StandardCharsets.UTF_8));
				z.closeEntry();
			}
		}
		return bo.toByteArray();
	}

	static final String META = "{\"pack\":{\"pack_format\":34,\"description\":\"test\"}}";

	static void zips() throws Exception {
		Path dir = Files.createTempDirectory("phz");
		Path good = dir.resolve("good.zip");
		Files.write(good, zip("pack.mcmeta", META, "assets/minecraft/textures/a.png", "x"));
		check(PackFiles.inspect(good).ok(), "good pack");
		Path nested = dir.resolve("nested.zip");
		Files.write(nested, zip("Folder/pack.mcmeta", META));
		check(!PackFiles.inspect(nested).ok() && PackFiles.inspect(nested).problem().contains("extra folder"), "nested pack");
		Path evil = dir.resolve("evil.zip");
		Files.write(evil, zip("pack.mcmeta", META, "../../evil.txt", "x"));
		check(!PackFiles.inspect(evil).ok(), "zip-slip entry refused");
		Path notZip = dir.resolve("html.zip");
		Files.writeString(notZip, "<html>rate limited</html>");
		check(!PackFiles.inspect(notZip).ok(), "html masquerading as zip");
	}

	static final String SEARCH = """
			{"hits":[
			 {"project_id":"AAA","slug":"faithful-32x","title":"Faithful 32x","description":"d","author":"Faithful",
			  "downloads":1200000,"follows":5,"icon_url":"https://cdn.modrinth.com/i.png",
			  "featured_gallery":null,"gallery":["https://cdn.modrinth.com/g1.png"],
			  "categories":["32x","vanilla-like"],"display_categories":["vanilla-like","combat"],
			  "versions":["1.21.1"],"license":"LicenseRef-Faithful","date_modified":"2026-01-01"},
			 {"project_id":"","title":"broken"},
			 {"project_id":"BBB","title":"No extras"}
			],"offset":0,"limit":40,"total_hits":2}
			""";

	static void search() {
		var q = new ModrinthPacks.Query(" pvp ", ModrinthPacks.Sort.RELEVANCE, ModrinthPacks.Category.PVP,
				ModrinthPacks.Resolution.LOW);
		String url = ModrinthPacks.searchUrl(q, "1.21.1", 40, 40);
		String dec = java.net.URLDecoder.decode(url, StandardCharsets.UTF_8);
		check(dec.contains("[[\"project_type:resourcepack\"],[\"versions:1.21.1\"],[\"categories:combat\"],[\"categories:8x-\",\"categories:16x\"]]"),
				"facets AND-of-ORs: " + dec);
		check(dec.contains("query=pvp") && dec.contains("offset=40") && dec.contains("index=relevance"), "query/offset/index");
		String empty = ModrinthPacks.searchUrl(new ModrinthPacks.Query("", ModrinthPacks.Sort.RELEVANCE,
				ModrinthPacks.Category.ALL, ModrinthPacks.Resolution.ANY), "1.21.1", 0, 500);
		check(empty.contains("index=downloads") && empty.contains("limit=100") && !empty.contains("query="),
				"empty relevance -> downloads, limit clamped");
		var page = ModrinthPacks.parseSearch(SEARCH);
		eq(2, page.hits().size(), "hits without id skipped");
		var h = page.hits().get(0);
		eq("https://cdn.modrinth.com/g1.png", h.imageUrl(), "gallery fallback when featured is null");
		eq("32x", h.resolution(), "resolution tag");
		check(h.categories().contains("combat"), "display categories merged");
		eq("https://modrinth.com/resourcepack/faithful-32x", h.pageUrl(), "page url");
		eq("", page.hits().get(1).resolution(), "no resolution claimed when undeclared");
		check(ModrinthPacks.trustedDownload("https://cdn.modrinth.com/data/x.zip"), "cdn trusted");
		check(!ModrinthPacks.trustedDownload("http://cdn.modrinth.com/data/x.zip"), "http refused");
		check(!ModrinthPacks.trustedDownload("https://modrinth.com.evil.net/x.zip"), "lookalike host refused");
		check(!ModrinthPacks.trustedDownload("https://evilmodrinth.com/x.zip"), "suffix trick refused");
	}

	static final String VERSIONS = """
			[{"id":"v3","project_id":"AAA","version_number":"3-beta","version_type":"beta","game_versions":["1.21.1"],
			  "files":[{"url":"https://cdn.modrinth.com/b.zip","filename":"b.zip","primary":true,"size":1,"hashes":{"sha1":"s","sha512":"t"}}]},
			 {"id":"v2","project_id":"AAA","version_number":"2","version_type":"release","game_versions":["1.21.1"],
			  "files":[{"url":"https://cdn.modrinth.com/extra.zip","filename":"extra.zip","primary":false,"size":1,"hashes":{}},
			           {"url":"https://cdn.modrinth.com/main.zip","filename":"main.zip","primary":true,"size":9,"hashes":{"sha1":"aa","sha512":"bb"}}]}]
			""";

	static void versions() {
		var f = ModrinthPacks.pickFile(VERSIONS);
		eq("v2", f.versionId(), "newest RELEASE beats a newer beta");
		eq("main.zip", f.filename(), "primary file");
		eq("bb", f.sha512(), "sha512 parsed");
		eq(null, ModrinthPacks.pickFile("[]"), "nothing installable");
		var m = ModrinthPacks.parseHashMap("{\"aa\":" + VERSIONS.strip().substring(1).split("\\},\\s*\\{\"id\":\"v2\"")[0] + "}}");
		check(m.containsKey("aa"), "hash map parsed");
		check(ModrinthPacks.updateBody(List.of("aa"), "1.21.1").contains("\"loaders\":[\"minecraft\"]"), "update body");
	}

	static void index() throws Exception {
		Path f = Files.createTempDirectory("phi").resolve("packhub.json");
		PackIndex a = new PackIndex(f);
		a.put("x.zip", new PackIndex.Entry("P", "V", "X", "x", "abc"));
		a.savePreset("PvP", List.of("x.zip", "y.zip"));
		a.markForDelete("old.zip");
		PackIndex b = new PackIndex(f);
		eq("P", b.get("x.zip").projectId(), "installed persisted");
		eq(List.of("x.zip", "y.zip"), b.preset("PvP"), "preset order persisted");
		check(b.isPendingDelete("old.zip"), "pending delete persisted");
		eq("x.zip", b.fileWithSha1("ABC"), "sha1 lookup case-insensitive");
		Files.writeString(f, "{corrupt");
		check(new PackIndex(f).installed().isEmpty(), "corrupt store starts empty");
	}

	static void order() {
		// selected top-first; F = file packs, others are built-ins (high_contrast) or fixed (vanilla)
		List<PackOrder.Slot> cur = List.of(
				new PackOrder.Slot("file/a.zip", false), new PackOrder.Slot("high_contrast", false),
				new PackOrder.Slot("file/b.zip", false), new PackOrder.Slot("vanilla", true));
		eq(List.of("file/b.zip", "high_contrast", "file/a.zip", "vanilla"),
				PackOrder.apply(cur, List.of("file/b.zip", "file/a.zip")), "reorder keeps built-in slot");
		eq(List.of("file/c.zip", "high_contrast", "file/a.zip", "vanilla"),
				PackOrder.apply(cur, List.of("file/c.zip", "file/a.zip")), "disable b, enable c on top");
		eq(List.of("high_contrast", "vanilla"), PackOrder.apply(cur, List.of()), "disable all file packs");
		eq(List.of("file/a.zip", "high_contrast", "file/b.zip", "vanilla"),
				PackOrder.apply(cur, List.of("file/a.zip", "file/b.zip")), "no change is identity");
	}

	static void installer() throws Exception {
		byte[] pack = zip("pack.mcmeta", "{\"pack\":{\"pack_format\":15,\"description\":\"old\"}}");
		byte[] notPack = zip("readme.txt", "hi");
		HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		srv.createContext("/pack.zip", ex -> {
			ex.sendResponseHeaders(200, pack.length);
			try (OutputStream o = ex.getResponseBody()) {
				o.write(pack);
			}
		});
		srv.createContext("/notpack.zip", ex -> {
			ex.sendResponseHeaders(200, notPack.length);
			try (OutputStream o = ex.getResponseBody()) {
				o.write(notPack);
			}
		});
		srv.start();
		try {
			String base = "http://127.0.0.1:" + srv.getAddress().getPort();
			Path dir = Files.createTempDirectory("phd");
			PackIndex idx = new PackIndex(dir.resolveSibling(dir.getFileName() + "-hub.json"));
			Files.write(dir.resolve("My Pack.zip"), new byte[]{1, 2, 3}); // the player's own, different file
			String sha1 = hex("SHA-1", pack), sha512 = hex("SHA-512", pack);

			var file = new ModrinthPacks.VersionFile("P", "V", "1", base + "/pack.zip", "../My Pack.zip",
					pack.length, sha1, sha512, List.of("1.21.1"));
			var st = PackInstallerProbe.install(idx, file, dir, 34, false);
			eq("DONE", st.status().name(), "install done: " + st.message());
			eq("My Pack (2).zip", st.fileName(), "never overwrites the player's pack; name sanitized");
			check(st.oldFormat(), "format 15 flagged as made for another version");
			check(java.util.Arrays.equals(new byte[]{1, 2, 3}, Files.readAllBytes(dir.resolve("My Pack.zip"))), "original untouched");

			var again = PackInstallerProbe.install(idx, file, dir, 34, false);
			eq("My Pack (2).zip", again.fileName(), "identical file recognised, not duplicated");
			try (var ds = Files.list(dir)) {
				eq(2L, ds.count(), "no extra files (no .part left, no duplicate)");
			}

			var bad = new ModrinthPacks.VersionFile("P2", "V", "1", base + "/pack.zip", "x.zip",
					pack.length, sha1, "00", List.of());
			Path fresh = Files.createTempDirectory("phd2");
			eq("ERROR", PackInstallerProbe.install(new PackIndex(fresh.resolve("../hub2.json")), bad, fresh, 34, false)
					.status().name(), "hash mismatch refused");
			try (var ds = Files.list(fresh)) {
				eq(0L, ds.count(), "corrupted download leaves nothing behind");
			}
			var np = new ModrinthPacks.VersionFile("P3", "V", "1", base + "/notpack.zip", "np.zip",
					notPack.length, hex("SHA-1", notPack), hex("SHA-512", notPack), List.of());
			var npState = PackInstallerProbe.install(idx, np, dir, 34, false);
			check(npState.status().name().equals("ERROR") && npState.message().contains("pack.mcmeta"), "non-pack refused");
			var untrusted = PackInstallerProbe.install(idx, file, dir, 34, true);
			eq("ERROR", untrusted.status().name(), "non-Modrinth host refused by default");
			try (var ds = Files.list(dir)) {
				eq(2L, ds.count(), "failed installs leave nothing behind");
			}
		} finally {
			srv.stop(0);
		}
	}

	static String hex(String algo, byte[] b) throws Exception {
		StringBuilder sb = new StringBuilder();
		for (byte x : MessageDigest.getInstance(algo).digest(b)) {
			sb.append(String.format("%02x", x));
		}
		return sb.toString();
	}

	static void library() throws Exception {
		Path dir = Files.createTempDirectory("packhub-lib");
		Files.write(dir.resolve("b.zip"), zip("pack.mcmeta", META));
		Files.write(dir.resolve("A pack.ZIP"), zip("pack.mcmeta", META));
		Files.write(dir.resolve("broken.zip"), new byte[]{1, 2, 3});
		Files.write(dir.resolve("half.zip.part"), new byte[]{1});
		Files.write(dir.resolve("notes.txt"), new byte[]{1});
		Files.createDirectories(dir.resolve("Folder Pack"));
		Files.writeString(dir.resolve("Folder Pack/pack.mcmeta"), META);
		Files.createDirectories(dir.resolve("random dir"));
		List<PackLibrary.Local> l = PackLibrary.scanLocal(dir);
		eq(List.of("A pack.ZIP", "b.zip", "broken.zip", "Folder Pack"),
				l.stream().map(PackLibrary.Local::name).toList(), "only zips + pack folders, A-Z, .part ignored");
		check(l.get(0).info().ok() && !l.get(0).sha1().isEmpty(), "zip inspected + hashed");
		check(!l.get(2).info().ok(), "broken zip listed with a problem");
		check(l.get(3).folder() && l.get(3).info().ok() && l.get(3).sha1().isEmpty(), "folder pack read from its mcmeta");
		check(PackLibrary.scanLocal(dir.resolve("missing")).isEmpty(), "missing folder = empty list");
	}


	static void frameStats() {
		FrameStatsProbe.reset();
		long t = 1_000_000_000L;
		FrameStats.frame(t);
		// 198 steady 10 ms frames, then two 50 ms hitches
		for (int i = 0; i < 198; i++) {
			t += 10_000_000L;
			FrameStats.frame(t);
		}
		int[] sunk = {0};
		FrameStats.setSpikeSink((ms, avg, kind) -> sunk[0]++);
		for (int i = 0; i < 2; i++) {
			t += 50_000_000L;
			FrameStats.frame(t);
		}
		FrameStats.setSpikeSink(null);
		check(sunk[0] >= 1, "50 ms hitch reaches the spike sink (>= 40 ms)");
		eq(200, FrameStats.count(), "every frame recorded");
		check(Math.abs(FrameStats.ms(0) - 50f) < 0.01 && Math.abs(FrameStats.ms(2) - 10f) < 0.01, "newest first");
		check(FrameStats.kind(0) != FrameStats.NORMAL && FrameStats.kind(2) == FrameStats.NORMAL, "hitch flagged as spike");
		eq(20, FrameStats.onePercentLow(), "1% low = slowest 2 of 200 frames (50 ms -> 20 FPS)");
		t += 5_000_000_000L; // alt-tab: a 5 s gap is not a frame
		FrameStats.frame(t);
		eq(200, FrameStats.count(), "pause gap ignored");
		FrameStatsProbe.reset();
		FrameStats.frame(1);
		eq(0, FrameStats.onePercentLow(), "no data -> 0 (HUD shows --)");
	}

}
