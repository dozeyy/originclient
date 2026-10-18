package com.origin.client.client.packs;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

// File-system safety for the Resource Pack Hub. Pure JDK + Gson (no Minecraft
// classes) so it is unit-tested outside the game (tools/packhub-tests).
//
// The hub never extracts an archive: Minecraft reads pack zips directly, so a
// downloaded pack is only ever written as ONE file inside resourcepacks/. That
// removes zip-slip by construction; inspect() still rejects archives whose entry
// names are unsafe, because a pack like that is malformed at best.
public final class PackFiles {
	private PackFiles() {
	}

	/** Longest filename we write (Windows MAX_PATH leaves little room under an instance folder). */
	static final int MAX_NAME = 120;
	/** pack.mcmeta larger than this is not a real pack descriptor. */
	private static final long MAX_MCMETA = 1 << 20;
	/** Archives listing more entries than this are refused rather than scanned forever. */
	private static final int MAX_ENTRIES = 200_000;

	private static final Pattern ILLEGAL = Pattern.compile("[\\p{Cntrl}<>:\"|?*]");
	private static final Pattern RESERVED = Pattern.compile("(?i)(con|prn|aux|nul|com[0-9]|lpt[0-9])(\\..*)?");

	/**
	 * Turns an untrusted remote filename into a safe single path component ending
	 * in .zip: last component only, no characters Windows rejects, no reserved
	 * device names, no trailing dots/spaces, bounded length.
	 */
	public static String safeFileName(String raw) {
		String name = raw == null ? "" : raw;
		int cut = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
		if (cut >= 0) {
			name = name.substring(cut + 1);
		}
		name = ILLEGAL.matcher(name).replaceAll("_").trim();
		if (!name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
			name = name + ".zip";
		}
		String base = name.substring(0, name.length() - 4);
		while (base.endsWith(".") || base.endsWith(" ")) {
			base = base.substring(0, base.length() - 1);
		}
		if (base.trim().isEmpty() || base.equals("..") || RESERVED.matcher(base).matches()) {
			base = "pack" + (base.trim().isEmpty() ? "" : "_" + base.replace(".", "_"));
		}
		if (base.length() > MAX_NAME - 4) {
			base = base.substring(0, MAX_NAME - 4).trim();
		}
		return base + ".zip";
	}

	/**
	 * dir/name, refusing anything that would land outside dir (or in a subfolder).
	 * name must already be a safeFileName.
	 */
	public static Path inside(Path dir, String name) throws IOException {
		Path root = dir.toAbsolutePath().normalize();
		Path p = root.resolve(name).normalize();
		if (!root.equals(p.getParent())) {
			throw new IOException("Refusing to write outside the resource pack folder: " + name);
		}
		return p;
	}

	/**
	 * A destination that does not exist yet: name.zip, else "name (2).zip", ... Existing
	 * packs are never overwritten.
	 */
	public static Path uniqueDestination(Path dir, String safeName) throws IOException {
		Path p = inside(dir, safeName);
		if (!Files.exists(p)) {
			return p;
		}
		String base = safeName.substring(0, safeName.length() - 4);
		for (int i = 2; i < 1000; i++) {
			Path c = inside(dir, base + " (" + i + ").zip");
			if (!Files.exists(c)) {
				return c;
			}
		}
		throw new IOException("Too many copies of " + safeName);
	}

	/** Moves tmp onto dest atomically where the file system allows; dest must not exist. */
	public static void moveInto(Path tmp, Path dest) throws IOException {
		try {
			Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(tmp, dest);
		}
	}

	// ---- Java 8 stand-ins ----
	// 1.8.9: the module is Java 8, so List.copyOf / Map.copyOf /
	// InputStream.readNBytes (Java 9-11) are spelled out here once.

	/** Immutable snapshot of a collection (List.copyOf). */
	static <T> java.util.List<T> copyOf(java.util.Collection<? extends T> c) {
		return java.util.Collections.unmodifiableList(new java.util.ArrayList<T>(c));
	}

	/** Immutable snapshot of a map (Map.copyOf), keeping its iteration order. */
	static <K, V> java.util.Map<K, V> copyOfMap(java.util.Map<? extends K, ? extends V> m) {
		return java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<K, V>(m));
	}

	/** Reads at most max bytes (InputStream.readNBytes(max)). */
	static byte[] readUpTo(InputStream in, int max) throws IOException {
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(Math.min(max, 65536));
		byte[] buf = new byte[8192];
		int total = 0;
		while (total < max) {
			int n = in.read(buf, 0, Math.min(buf.length, max - total));
			if (n < 0) {
				break;
			}
			out.write(buf, 0, n);
			total += n;
		}
		return out.toByteArray();
	}

	// ---- hashing ----

	public static String sha1(Path file) throws IOException {
		return hash(file, "SHA-1");
	}

	public static String sha512(Path file) throws IOException {
		return hash(file, "SHA-512");
	}

	private static String hash(Path file, String algo) throws IOException {
		try (InputStream in = Files.newInputStream(file)) {
			MessageDigest md = MessageDigest.getInstance(algo);
			byte[] buf = new byte[65536];
			int n;
			while ((n = in.read(buf)) > 0) {
				md.update(buf, 0, n);
			}
			return hex(md.digest());
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IOException(e);
		}
	}

	static String hex(byte[] b) {
		StringBuilder sb = new StringBuilder(b.length * 2);
		for (byte x : b) {
			sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
		}
		return sb.toString();
	}

	// ---- archive inspection ----

	/**
	 * What a pack zip declares. format = -1 when pack.mcmeta has no readable
	 * pack_format; minFormat/maxFormat widen it when supported_formats is present.
	 * problem != null means "not a loadable resource pack" with a player-facing reason.
	 */
	public static final class PackInfo {
		private final int format;
		private final int minFormat;
		private final int maxFormat;
		private final String description;
		private final String problem;

		public PackInfo(int format, int minFormat, int maxFormat, String description, String problem) {
			this.format = format;
			this.minFormat = minFormat;
			this.maxFormat = maxFormat;
			this.description = description;
			this.problem = problem;
		}

		public int format() {
			return format;
		}

		public int minFormat() {
			return minFormat;
		}

		public int maxFormat() {
			return maxFormat;
		}

		public String description() {
			return description;
		}

		public String problem() {
			return problem;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof PackInfo)) {
				return false;
			}
			PackInfo x = (PackInfo) o;
			return format == x.format
					&& minFormat == x.minFormat
					&& maxFormat == x.maxFormat
					&& java.util.Objects.equals(description, x.description)
					&& java.util.Objects.equals(problem, x.problem);
		}

		@Override
		public int hashCode() {
			return java.util.Arrays.hashCode(new Object[] {format, minFormat, maxFormat, description, problem});
		}

		public boolean ok() {
			return problem == null;
		}

		/** Whether Minecraft would list this pack as compatible with gameFormat. */
		public boolean supports(int gameFormat) {
			if (format < 0) {
				return false;
			}
			return gameFormat >= minFormat && gameFormat <= maxFormat;
		}
	
	}

	private static PackInfo bad(String why) {
		return new PackInfo(-1, -1, -1, "", why);
	}

	/** Opens the archive read-only and checks it is a resource pack Minecraft can load. */
	public static PackInfo inspect(Path zip) {
		try (ZipFile zf = new ZipFile(zip.toFile(), StandardCharsets.UTF_8)) {
			ZipEntry mcmeta = null;
			boolean nested = false;
			int count = 0;
			Enumeration<? extends ZipEntry> en = zf.entries();
			while (en.hasMoreElements()) {
				ZipEntry e = en.nextElement();
				if (++count > MAX_ENTRIES) {
					return bad("Archive is too large to check");
				}
				String n = e.getName();
				if (!safeEntryName(n)) {
					return bad("Archive contains unsafe file paths");
				}
				if (n.equals("pack.mcmeta")) {
					mcmeta = e;
				} else if (n.endsWith("/pack.mcmeta")) {
					nested = true;
				}
			}
			if (mcmeta == null) {
				return bad(nested ? "Pack is inside an extra folder; Minecraft can't load it" : "Not a resource pack (no pack.mcmeta)");
			}
			if (mcmeta.getSize() > MAX_MCMETA) {
				return bad("pack.mcmeta is too large");
			}
			try (InputStream in = zf.getInputStream(mcmeta)) {
				byte[] raw = PackFiles.readUpTo(in, (int) MAX_MCMETA);
				return parseMcmeta(new String(raw, StandardCharsets.UTF_8));
			}
		} catch (java.util.zip.ZipException e) {
			return bad("Download is not a valid zip file");
		} catch (IOException e) {
			return bad("Could not read the pack");
		}
	}

	/** Relative, forward-slash names with no traversal, drive letters or NULs. */
	static boolean safeEntryName(String n) {
		if (n.isEmpty() || n.startsWith("/") || n.indexOf('\\') >= 0 || n.indexOf('\0') >= 0
				|| (n.length() > 1 && n.charAt(1) == ':')) {
			return false;
		}
		for (String part : n.split("/")) {
			if (part.equals("..")) {
				return false;
			}
		}
		return true;
	}

	/** pack.mcmeta -> PackInfo. Lenient like Minecraft: unknown fields ignored, BOM tolerated. */
	public static PackInfo parseMcmeta(String json) {
		try {
			String s = json.startsWith("﻿") ? json.substring(1) : json;
			JsonObject root = new JsonParser().parse(s).getAsJsonObject();
			JsonObject pack = root.getAsJsonObject("pack");
			if (pack == null) {
				return bad("pack.mcmeta has no \"pack\" section");
			}
			int fmt = pack.has("pack_format") && pack.get("pack_format").isJsonPrimitive()
					? pack.get("pack_format").getAsInt() : -1;
			int min = fmt, max = fmt;
			JsonElement sf = pack.get("supported_formats");
			if (sf != null) {
				if (sf.isJsonPrimitive()) {
					min = max = sf.getAsInt();
				} else if (sf.isJsonArray() && sf.getAsJsonArray().size() == 2) {
					JsonArray a = sf.getAsJsonArray();
					min = a.get(0).getAsInt();
					max = a.get(1).getAsInt();
				} else if (sf.isJsonObject()) {
					JsonObject o = sf.getAsJsonObject();
					min = o.has("min_inclusive") ? o.get("min_inclusive").getAsInt() : fmt;
					max = o.has("max_inclusive") ? o.get("max_inclusive").getAsInt() : fmt;
				}
				if (fmt >= 0) {
					min = Math.min(min, fmt);
					max = Math.max(max, fmt);
				}
			}
			if (fmt < 0) {
				return bad("pack.mcmeta has no pack_format");
			}
			return new PackInfo(fmt, min, max, describe(pack.get("description")), null);
		} catch (RuntimeException e) {
			return bad("pack.mcmeta is not valid JSON");
		}
	}

	/** Plain text of a pack description (a string, a text component, or a list of them). */
	static String describe(JsonElement d) {
		if (d == null || d.isJsonNull()) {
			return "";
		}
		if (d.isJsonPrimitive()) {
			return strip(d.getAsString());
		}
		StringBuilder sb = new StringBuilder();
		if (d.isJsonArray()) {
			for (JsonElement e : d.getAsJsonArray()) {
				sb.append(describe(e));
			}
		} else if (d.isJsonObject()) {
			JsonObject o = d.getAsJsonObject();
			if (o.has("text")) {
				sb.append(strip(o.get("text").getAsString()));
			}
			if (o.has("extra")) {
				sb.append(describe(o.get("extra")));
			}
		}
		return sb.toString();
	}

	/** Drops legacy section-sign formatting codes and newlines. */
	private static String strip(String s) {
		return s.replaceAll("§.", "").replace('\n', ' ').trim();
	}
}
