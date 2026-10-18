package com.origin.client.client.packs;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.origin.client.client.net.OriginHttp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// The Modrinth v2 API as the Resource Pack Hub uses it: request URLs, response
// parsing, and one guarded HTTP helper. Pure JDK + Gson (no Minecraft classes)
// so the parsing is unit-tested outside the game (tools/packhub-tests).
//
// Modrinth is the only source: its metadata is what the hub shows (no invented
// compatibility or resolution claims) and its CDN is the only host a pack is
// downloaded from. API facts this relies on (docs.modrinth.com openapi.yaml):
// search limit <= 100; index = relevance|downloads|follows|newest|updated;
// facets are AND-of-ORs; 300 requests/minute per IP with X-Ratelimit-* headers;
// a User-Agent that identifies the project is required.
public final class ModrinthPacks {
	private ModrinthPacks() {
	}

	public static final String API = "https://api.modrinth.com/v2";
	static final String UA = "dozeyy/originclient (will@willhenry.me)";
	/** Modrinth resource-pack versions carry this loader. */
	static final String LOADER = "minecraft";
	/** JSON responses bigger than this are refused (a search page is ~50 KB). */
	private static final int MAX_JSON = 8 << 20;

	// ---- what the hub can ask for ----

	public enum Sort {
		RELEVANCE("relevance", "Relevance"), DOWNLOADS("downloads", "Downloads"), FOLLOWS("follows", "Follows"),
		NEWEST("newest", "Newest"), UPDATED("updated", "Updated");

		public final String index, label;

		Sort(String index, String label) {
			this.index = index;
			this.label = label;
		}
	}

	/**
	 * Category chips. Slugs are Modrinth's own resource-pack category tags
	 * (modrinth/code packages/assets/icons/tags/categories/*.svg) — PvP maps to
	 * Modrinth's "combat" tag; nothing here is inferred by Origin.
	 */
	public enum Category {
		ALL(null, "All"), PVP("combat", "PvP"), VANILLA_PLUS("vanilla-like", "Vanilla+"),
		REALISTIC("realistic", "Realistic"), SIMPLISTIC("simplistic", "Simplistic"), THEMED("themed", "Themed"),
		TWEAKS("tweaks", "Tweaks"), UTILITY("utility", "Utility"), DECORATION("decoration", "Decoration");

		public final String slug, label;

		Category(String slug, String label) {
			this.slug = slug;
			this.label = label;
		}
	}

	/**
	 * Resolution filter: the author-declared Modrinth resolution tags. Shown as the
	 * pack's own tag, never measured by Origin. Low-res packs are the light ones on
	 * VRAM; we say "Low-res", not "FPS boost", because that is all the tag proves.
	 */
	public enum Resolution {
		ANY(java.util.Collections.emptyList(), "Any res"), LOW(java.util.Collections.unmodifiableList(java.util.Arrays.asList("8x-", "16x")), "16x and below"), R32(java.util.Collections.unmodifiableList(java.util.Arrays.asList("32x")), "32x"),
		R64(java.util.Collections.unmodifiableList(java.util.Arrays.asList("48x", "64x")), "48-64x"), HIGH(java.util.Collections.unmodifiableList(java.util.Arrays.asList("128x", "256x", "512x+")), "128x+");

		public final List<String> tags;
		public final String label;

		Resolution(List<String> tags, String label) {
			this.tags = tags;
			this.label = label;
		}
	}

	/** Every resolution tag Modrinth uses, for picking a hit's display resolution. */
	static final List<String> RES_TAGS = java.util.Collections.unmodifiableList(java.util.Arrays.asList("8x-", "16x", "32x", "48x", "64x", "128x", "256x", "512x+"));

	public static final class Query {
		private final String text;
		private final Sort sort;
		private final Category category;
		private final Resolution resolution;

		public Query(String text, Sort sort, Category category, Resolution resolution) {
				text = text == null ? "" : text.trim();
		
			this.text = text;
			this.sort = sort;
			this.category = category;
			this.resolution = resolution;
		}

		public String text() {
			return text;
		}

		public Sort sort() {
			return sort;
		}

		public Category category() {
			return category;
		}

		public Resolution resolution() {
			return resolution;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof Query)) {
				return false;
			}
			Query x = (Query) o;
			return java.util.Objects.equals(text, x.text)
					&& java.util.Objects.equals(sort, x.sort)
					&& java.util.Objects.equals(category, x.category)
					&& java.util.Objects.equals(resolution, x.resolution);
		}

		@Override
		public int hashCode() {
			return java.util.Arrays.hashCode(new Object[] {text, sort, category, resolution});
		}

		/** Stable key for caches. */
		public String key(String gameVersion) {
			return gameVersion + "|" + text.toLowerCase(Locale.ROOT) + "|" + sort + "|" + category + "|" + resolution;
		}
	
	}

	// ---- results ----

	/** One search hit, exactly the fields Modrinth returned (empty strings where absent). */
	public static final class Hit {
		private final String projectId;
		private final String slug;
		private final String title;
		private final String description;
		private final String author;
		private final long downloads;
		private final long follows;
		private final String iconUrl;
		private final String imageUrl;
		private final List<String> categories;
		private final List<String> versions;
		private final String license;
		private final String dateModified;

		public Hit(String projectId, String slug, String title, String description, String author, long downloads, long follows, String iconUrl, String imageUrl, List<String> categories, List<String> versions, String license, String dateModified) {
			this.projectId = projectId;
			this.slug = slug;
			this.title = title;
			this.description = description;
			this.author = author;
			this.downloads = downloads;
			this.follows = follows;
			this.iconUrl = iconUrl;
			this.imageUrl = imageUrl;
			this.categories = categories;
			this.versions = versions;
			this.license = license;
			this.dateModified = dateModified;
		}

		public String projectId() {
			return projectId;
		}

		public String slug() {
			return slug;
		}

		public String title() {
			return title;
		}

		public String description() {
			return description;
		}

		public String author() {
			return author;
		}

		public long downloads() {
			return downloads;
		}

		public long follows() {
			return follows;
		}

		public String iconUrl() {
			return iconUrl;
		}

		public String imageUrl() {
			return imageUrl;
		}

		public List<String> categories() {
			return categories;
		}

		public List<String> versions() {
			return versions;
		}

		public String license() {
			return license;
		}

		public String dateModified() {
			return dateModified;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof Hit)) {
				return false;
			}
			Hit x = (Hit) o;
			return java.util.Objects.equals(projectId, x.projectId)
					&& java.util.Objects.equals(slug, x.slug)
					&& java.util.Objects.equals(title, x.title)
					&& java.util.Objects.equals(description, x.description)
					&& java.util.Objects.equals(author, x.author)
					&& downloads == x.downloads
					&& follows == x.follows
					&& java.util.Objects.equals(iconUrl, x.iconUrl)
					&& java.util.Objects.equals(imageUrl, x.imageUrl)
					&& java.util.Objects.equals(categories, x.categories)
					&& java.util.Objects.equals(versions, x.versions)
					&& java.util.Objects.equals(license, x.license)
					&& java.util.Objects.equals(dateModified, x.dateModified);
		}

		@Override
		public int hashCode() {
			return java.util.Arrays.hashCode(new Object[] {projectId, slug, title, description, author, downloads, follows, iconUrl, imageUrl, categories, versions, license, dateModified});
		}

		/** The author-declared resolution tag ("16x"), or "" when the pack declares none. */
		public String resolution() {
			for (String c : categories) {
				if (RES_TAGS.contains(c)) {
					return c;
				}
			}
			return "";
		}

		public String pageUrl() {
			return "https://modrinth.com/resourcepack/" + (slug.isEmpty() ? projectId : slug);
		}
	
	}

	public static final class Page {
		private final List<Hit> hits;
		private final int offset;
		private final int total;

		public Page(List<Hit> hits, int offset, int total) {
			this.hits = hits;
			this.offset = offset;
			this.total = total;
		}

		public List<Hit> hits() {
			return hits;
		}

		public int offset() {
			return offset;
		}

		public int total() {
			return total;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof Page)) {
				return false;
			}
			Page x = (Page) o;
			return java.util.Objects.equals(hits, x.hits)
					&& offset == x.offset
					&& total == x.total;
		}

		@Override
		public int hashCode() {
			return java.util.Arrays.hashCode(new Object[] {hits, offset, total});
		}
	}

	/** A downloadable file of one Modrinth version. */
	public static final class VersionFile {
		private final String projectId;
		private final String versionId;
		private final String versionNumber;
		private final String url;
		private final String filename;
		private final long size;
		private final String sha1;
		private final String sha512;
		private final List<String> gameVersions;

		public VersionFile(String projectId, String versionId, String versionNumber, String url, String filename, long size, String sha1, String sha512, List<String> gameVersions) {
			this.projectId = projectId;
			this.versionId = versionId;
			this.versionNumber = versionNumber;
			this.url = url;
			this.filename = filename;
			this.size = size;
			this.sha1 = sha1;
			this.sha512 = sha512;
			this.gameVersions = gameVersions;
		}

		public String projectId() {
			return projectId;
		}

		public String versionId() {
			return versionId;
		}

		public String versionNumber() {
			return versionNumber;
		}

		public String url() {
			return url;
		}

		public String filename() {
			return filename;
		}

		public long size() {
			return size;
		}

		public String sha1() {
			return sha1;
		}

		public String sha512() {
			return sha512;
		}

		public List<String> gameVersions() {
			return gameVersions;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof VersionFile)) {
				return false;
			}
			VersionFile x = (VersionFile) o;
			return java.util.Objects.equals(projectId, x.projectId)
					&& java.util.Objects.equals(versionId, x.versionId)
					&& java.util.Objects.equals(versionNumber, x.versionNumber)
					&& java.util.Objects.equals(url, x.url)
					&& java.util.Objects.equals(filename, x.filename)
					&& size == x.size
					&& java.util.Objects.equals(sha1, x.sha1)
					&& java.util.Objects.equals(sha512, x.sha512)
					&& java.util.Objects.equals(gameVersions, x.gameVersions);
		}

		@Override
		public int hashCode() {
			return java.util.Arrays.hashCode(new Object[] {projectId, versionId, versionNumber, url, filename, size, sha1, sha512, gameVersions});
		}
	}

	// ---- URLs ----

	public static String searchUrl(Query q, String gameVersion, int offset, int limit) {
		StringBuilder facets = new StringBuilder("[[\"project_type:resourcepack\"],[\"versions:")
				.append(jsonEscape(gameVersion)).append("\"]");
		if (q.category() != null && q.category().slug != null) {
			facets.append(",[\"categories:").append(q.category().slug).append("\"]");
		}
		if (q.resolution() != null && !q.resolution().tags.isEmpty()) {
			facets.append(",[");
			for (int i = 0; i < q.resolution().tags.size(); i++) {
				facets.append(i == 0 ? "" : ",").append("\"categories:").append(q.resolution().tags.get(i)).append('"');
			}
			facets.append(']');
		}
		facets.append(']');
		Sort sort = q.sort() == null ? Sort.RELEVANCE : q.sort();
		// relevance with an empty query is an arbitrary order; downloads is what
		// players expect from "most popular first"
		if (sort == Sort.RELEVANCE && q.text().isEmpty()) {
			sort = Sort.DOWNLOADS;
		}
		return API + "/search?limit=" + Math.max(1, Math.min(100, limit)) + "&offset=" + Math.max(0, offset)
				+ "&index=" + sort.index + "&facets=" + enc(facets.toString())
				+ (q.text().isEmpty() ? "" : "&query=" + enc(q.text()));
	}

	public static String versionsUrl(String projectId, String gameVersion) {
		return API + "/project/" + enc(projectId) + "/version?game_versions=" + enc("[\"" + jsonEscape(gameVersion) + "\"]")
				+ "&loaders=" + enc("[\"" + LOADER + "\"]");
	}

	/** Body for POST /version_files/update: latest version per installed file hash. */
	public static String updateBody(List<String> sha1s, String gameVersion) {
		JsonObject o = new JsonObject();
		o.add("hashes", strings(sha1s));
		o.addProperty("algorithm", "sha1");
		o.add("loaders", strings(java.util.Collections.unmodifiableList(java.util.Arrays.asList(LOADER))));
		o.add("game_versions", strings(java.util.Collections.unmodifiableList(java.util.Arrays.asList(gameVersion))));
		return o.toString();
	}

	/** Body for POST /version_files: which Modrinth version each local file hash is. */
	public static String identifyBody(List<String> sha1s) {
		JsonObject o = new JsonObject();
		o.add("hashes", strings(sha1s));
		o.addProperty("algorithm", "sha1");
		return o.toString();
	}

	public static String projectsUrl(List<String> ids) {
		return API + "/projects?ids=" + enc(strings(ids).toString());
	}

	// ---- parsing ----

	public static Page parseSearch(String json) {
		JsonObject root = new JsonParser().parse(json).getAsJsonObject();
		List<Hit> hits = new ArrayList<>();
		JsonArray arr = root.getAsJsonArray("hits");
		if (arr != null) {
			for (JsonElement el : arr) {
				if (!el.isJsonObject()) {
					continue;
				}
				JsonObject h = el.getAsJsonObject();
				String id = str(h, "project_id");
				if (id.isEmpty()) {
					continue;
				}
				// featured gallery image, else the first gallery image, else the icon
				String image = str(h, "featured_gallery");
				if (image.isEmpty()) {
					List<String> gallery = list(h, "gallery");
					image = gallery.isEmpty() ? "" : gallery.get(0);
				}
				List<String> cats = new ArrayList<>(list(h, "categories"));
				for (String c : list(h, "display_categories")) {
					if (!cats.contains(c)) {
						cats.add(c);
					}
				}
				hits.add(new Hit(id, str(h, "slug"), str(h, "title"), str(h, "description"), str(h, "author"),
						num(h, "downloads"), num(h, "follows"), str(h, "icon_url"), image, PackFiles.copyOf(cats),
						list(h, "versions"), str(h, "license"), str(h, "date_modified")));
			}
		}
		return new Page(PackFiles.copyOf(hits), (int) num(root, "offset"), (int) num(root, "total_hits"));
	}

	/**
	 * The file to install from a /project/{id}/version listing (already filtered to
	 * this game version + loader by the URL): newest release, else newest of any
	 * type; its primary file, else its first. null when nothing is installable.
	 */
	public static VersionFile pickFile(String json) {
		JsonArray versions = new JsonParser().parse(json).getAsJsonArray();
		JsonObject pick = null;
		for (JsonElement el : versions) {
			JsonObject v = el.getAsJsonObject();
			if ("release".equals(str(v, "version_type"))) {
				pick = v;
				break;
			}
		}
		if (pick == null && versions.size() > 0) {
			pick = versions.get(0).getAsJsonObject();
		}
		return pick == null ? null : fileOf(pick);
	}

	/** Primary (else first) file of one version object. */
	static VersionFile fileOf(JsonObject v) {
		JsonArray files = v.getAsJsonArray("files");
		if (files == null || files.size() == 0) {
			return null;
		}
		JsonObject f = files.get(0).getAsJsonObject();
		for (JsonElement el : files) {
			JsonObject o = el.getAsJsonObject();
			if (o.has("primary") && o.get("primary").getAsBoolean()) {
				f = o;
				break;
			}
		}
		JsonObject hashes = f.getAsJsonObject("hashes");
		return new VersionFile(str(v, "project_id"), str(v, "id"), str(v, "version_number"), str(f, "url"),
				str(f, "filename"), num(f, "size"), hashes == null ? "" : str(hashes, "sha1"),
				hashes == null ? "" : str(hashes, "sha512"), list(v, "game_versions"));
	}

	/** Response of /version_files or /version_files/update: hash -> that version's file. */
	public static Map<String, VersionFile> parseHashMap(String json) {
		Map<String, VersionFile> out = new HashMap<>();
		JsonObject root = new JsonParser().parse(json).getAsJsonObject();
		for (Map.Entry<String, JsonElement> e : root.entrySet()) {
			if (e.getValue().isJsonObject()) {
				VersionFile f = fileOf(e.getValue().getAsJsonObject());
				if (f != null) {
					out.put(e.getKey(), f);
				}
			}
		}
		return out;
	}

	/** /projects?ids=... -> id -> {title, icon_url, slug}. */
	public static Map<String, String[]> parseProjects(String json) {
		Map<String, String[]> out = new HashMap<>();
		for (JsonElement el : new JsonParser().parse(json).getAsJsonArray()) {
			JsonObject p = el.getAsJsonObject();
			out.put(str(p, "id"), new String[]{str(p, "title"), str(p, "icon_url"), str(p, "slug")});
		}
		return out;
	}

	// ---- trust boundaries ----

	/** Pack files are only ever downloaded over https from Modrinth's own hosts. */
	public static boolean trustedDownload(String url) {
		return trustedHost(url);
	}

	/** Preview images: https from Modrinth's CDN only (it serves every gallery/icon image). */
	public static boolean trustedImage(String url) {
		return trustedHost(url);
	}

	private static boolean trustedHost(String url) {
		try {
			URI u = URI.create(url);
			String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
			return "https".equalsIgnoreCase(u.getScheme()) && (host.equals("modrinth.com") || host.endsWith(".modrinth.com"));
		} catch (RuntimeException e) {
			return false;
		}
	}

	// ---- HTTP ----

	/** Thrown on HTTP 429; retryAtMs is when Modrinth says the window resets. */
	public static final class RateLimited extends IOException {
		public final long retryAtMs;

		RateLimited(long retryAtMs) {
			super("Modrinth rate limit");
			this.retryAtMs = retryAtMs;
		}
	}

	/** Set after a 429 so every caller backs off together instead of hammering the API. */
	private static volatile long blockedUntil = 0;

	public static long blockedUntil() {
		return blockedUntil;
	}

	public static String get(String url) throws IOException {
		return request(url, null);
	}

	public static String post(String url, String jsonBody) throws IOException {
		return request(url, jsonBody);
	}

	private static String request(String url, String body) throws IOException {
		long now = System.currentTimeMillis();
		if (now < blockedUntil) {
			throw new RateLimited(blockedUntil);
		}
		HttpURLConnection conn = open(url);
		if (body != null) {
			conn.setRequestMethod("POST");
			conn.setDoOutput(true);
			conn.setRequestProperty("Content-Type", "application/json");
			try (OutputStream out = conn.getOutputStream()) {
				out.write(body.getBytes(StandardCharsets.UTF_8));
			}
		}
		int code = conn.getResponseCode();
		if (code == 429) {
			long resetS = 60;
			try {
				resetS = Math.max(1, Math.min(300, Long.parseLong(conn.getHeaderField("X-Ratelimit-Reset").trim())));
			} catch (RuntimeException ignored) {
				// header missing or odd: a minute is Modrinth's window
			}
			blockedUntil = System.currentTimeMillis() + resetS * 1000;
			conn.disconnect();
			throw new RateLimited(blockedUntil);
		}
		if (code == 404) {
			conn.disconnect();
			return null;
		}
		if (code < 200 || code >= 300) {
			conn.disconnect();
			throw new IOException("Modrinth answered HTTP " + code);
		}
		try (InputStream in = conn.getInputStream()) {
			byte[] raw = PackFiles.readUpTo(in, MAX_JSON + 1);
			if (raw.length > MAX_JSON) {
				throw new IOException("Modrinth response too large");
			}
			return new String(raw, StandardCharsets.UTF_8);
		}
	}

	/** A GET connection with Origin's User-Agent and sane timeouts (caller reads it). */
	public static HttpURLConnection open(String url) throws IOException {
		// 1.8.9: through OriginHttp -- Java 8's old trust store fails Modrinth's
		// certificate chain with PKIX errors (PORT-189.md).
		HttpURLConnection conn = OriginHttp.open(url);
		conn.setRequestProperty("User-Agent", UA);
		conn.setRequestProperty("Accept", "application/json");
		conn.setConnectTimeout(10_000);
		conn.setReadTimeout(30_000);
		conn.setInstanceFollowRedirects(true);
		return conn;
	}

	// ---- json helpers ----

	private static String enc(String s) {
		try {
			// 1.8.9: Java 8 has no URLEncoder.encode(String, Charset)
			return URLEncoder.encode(s, "UTF-8");
		} catch (java.io.UnsupportedEncodingException e) {
			throw new IllegalStateException(e);
		}
	}

	private static String jsonEscape(String s) {
		return s.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	private static JsonArray strings(List<String> xs) {
		JsonArray a = new JsonArray();
		for (String x : xs) {
			a.add(new com.google.gson.JsonPrimitive(x)); // 1.8.9: Gson 2.2.4 has no add(String)
		}
		return a;
	}

	static String str(JsonObject o, String k) {
		JsonElement e = o.get(k);
		return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? "" : e.getAsString();
	}

	static long num(JsonObject o, String k) {
		JsonElement e = o.get(k);
		try {
			return e == null || e.isJsonNull() ? 0 : e.getAsLong();
		} catch (RuntimeException ex) {
			return 0;
		}
	}

	static List<String> list(JsonObject o, String k) {
		JsonElement e = o.get(k);
		if (e == null || !e.isJsonArray()) {
			return java.util.Collections.emptyList();
		}
		List<String> out = new ArrayList<>();
		for (JsonElement x : e.getAsJsonArray()) {
			if (x.isJsonPrimitive()) {
				out.add(x.getAsString());
			}
		}
		return PackFiles.copyOf(out);
	}
}
