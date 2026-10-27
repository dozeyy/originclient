package com.origin.client.client.compat;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;

/**
 * Which of Origin's hooks into OTHER mods (Sodium, Iris, JEI, ...) may run, decided once from the
 * versions Fabric Loader actually loaded. A player's own Sodium / Iris / JEI wins over Origin's
 * (DECISIONS 2026-10-25), so Origin can meet any version of them; each hook was proven only against
 * the builds this module ships plus the launcher's vetted pairs. On any other version the hook's
 * feature switches off cleanly and the rest of Origin keeps running (DECISIONS 2026-10-26).
 *
 * <p>The data is per module, in the jar resource {@value #RESOURCE} (version-independent code, version
 * data). Two kinds of line, fields split by '|':
 * <pre>
 *   group   &lt;id&gt; | &lt;label&gt; | &lt;mod&gt; &lt;range&gt; [; &lt;mod&gt; &lt;range&gt; ...] | [mixin simple names ...]
 *   feature &lt;id&gt; | &lt;label&gt; | &lt;group&gt; ... [ / &lt;group&gt; ... ]
 *   sig     &lt;group&gt; | &lt;owner class&gt; | &lt;method&gt;&lt;descriptor&gt; [or &lt;method&gt;&lt;descriptor&gt; ...]
 * </pre>
 * A sig line is one method a group's mixins inject into: the owner class by its exact binary name,
 * the method name and its full descriptor (javap -s on the jar the module pins). After the version
 * range passes, every sig line of the group must be declared by its class (ClassSignatures, read from
 * the class file without loading it); a moved class, a renamed method or a changed descriptor turns
 * the group off before Mixin applies any of its mixins, with one log line naming what is missing.
 * Minecraft classes in a descriptor are written in intermediary ({@code net/minecraft/class_437}) and
 * mapped to the running namespace (named in dev, intermediary for players); 26.x has no intermediary,
 * so its tables use the real names.
 * A group is on when every listed mod is loaded at a version inside its range (Fabric's predicate
 * syntax, e.g. {@code ~0.8.13} = 0.8.13 or newer on the 0.8 line; {@code >=19.51 <20.0.0-}; several
 * alternatives joined by {@code or}, e.g. {@code ~0.8.9 or ~0.9.2}; not {@code ||}, which would split the
 * line's '|' fields). Its mixins apply only while it is on (CompatMixinPlugin). A
 * feature is on when every group of at least one '/'-separated alternative is on; the feature's own
 * code asks {@link #on(String)} (its setting key is its id) before touching another mod's internals.
 * Ids of groups and features share one namespace. An id the data doesn't name is on: modules that have
 * no hook for it never gate it.
 *
 * <p>Runs during Mixin's config load, before any game class: nothing here may touch a Minecraft or
 * Origin class (only Fabric Loader, the JDK and log4j). Any failure reading the data turns every group
 * OFF (the safe direction: hooks skipped, the game runs vanilla-plus-Origin without them).
 */
public final class CompatGates {
	private CompatGates() { }

	public static final String RESOURCE = "originclient.compat-gates.txt";

	/** What a hook needs: mod id -> predicate text ("~0.8.13", or alternatives joined by " or "). */
	private static final class Group {
		final String id, label;
		final Map<String, String> needs = new LinkedHashMap<>();
		final List<String> mixins = new ArrayList<>();
		/** {owner class, "name(desc)ret [or ...]"} per sig line. */
		final List<String[]> sigs = new ArrayList<>();
		boolean on;
		String why;
		/** The mod and version that turned it off (for the Mod Menu page note), or null. */
		String offMod, offVersion;
		Group(String id, String label) { this.id = id; this.label = label; }
	}

	private static final class Feature {
		final String id, label;
		final List<List<String>> alternatives = new ArrayList<>();
		boolean on;
		String note;
		Feature(String id, String label) { this.id = id; this.label = label; }
	}

	private static final Map<String, Group> GROUPS = new LinkedHashMap<>();
	private static final Map<String, Feature> FEATURES = new LinkedHashMap<>();
	private static final Map<String, Group> BY_MIXIN = new HashMap<>();
	private static boolean loaded;
	private static boolean failed;
	/** Set once init() has finished; lets the per-frame callers of on() skip the synchronized init(). */
	private static volatile boolean ready;

	/** Reads the data and logs one line per switched-off hook. Idempotent; called from the mixin plugin. */
	public static synchronized void init() {
		if (loaded) {
			return;
		}
		loaded = true;
		try {
			read();
			for (Group g : GROUPS.values()) {
				evaluate(g);
			}
			for (Feature f : FEATURES.values()) {
				evaluate(f);
			}
			for (Feature f : FEATURES.values()) {
				if (!f.on) {
					log(f.label + " is off (" + firstWhy(f) + ")");
				}
			}
			// A group that no feature names (shader-screen styling, JEI toggle hooks) reports itself.
			for (Group g : GROUPS.values()) {
				if (!g.on && !named(g.id)) {
					log(g.label + " is off (" + g.why + ")");
				}
			}
			// A passed signature check says so too: CI greps run.log for it (proof the check ran, not just
			// that nothing failed).
			for (Group g : GROUPS.values()) {
				if (g.on && !g.sigs.isEmpty()) {
					log(g.label + " is on (signature check: " + g.sigs.size() + " of " + g.sigs.size()
							+ " methods match " + versions(g) + ")");
				}
			}
		} catch (Throwable t) {
			failed = true;
			log("could not read " + RESOURCE + ", every third-party hook stays off: " + t);
		} finally {
			ready = true;
		}
	}

	/** True when group or feature {@code id} may run. Unknown ids are on (see class doc). */
	public static boolean on(String id) {
		if (!ready) {
			init();
		}
		if (failed) {
			return false;
		}
		Feature f = FEATURES.get(id);
		if (f != null) {
			return f.on;
		}
		Group g = GROUPS.get(id);
		return g == null || g.on;
	}

	/**
	 * Short ASCII note for a switched-off feature or group ("Off: needs a supported Sodium"), or null
	 * while it runs. Shown next to the feature in the Mod Menu.
	 */
	public static String note(String id) {
		if (!ready) {
			init();
		}
		if (failed) {
			return FEATURES.containsKey(id) || GROUPS.containsKey(id) ? "Off: Origin could not check this" : null;
		}
		Feature f = FEATURES.get(id);
		if (f != null) {
			return f.on ? null : f.note;
		}
		Group g = GROUPS.get(id);
		return g == null || g.on ? null : noteFor(g);
	}

	/** The Mod Menu card's short note for card {@code id} (a group or feature with that id), or null. */
	public static String cardNote(String id) {
		return note(id);
	}

	/**
	 * The Mod Menu page's one-line explanation for card {@code id}, or null while it runs:
	 * "Origin's JEI hooks are off: they were made for another JEI than the one installed (19.27.0.340).
	 * JEI itself keeps working."
	 */
	public static String pageNote(String id) {
		if (note(id) == null) {
			return null;
		}
		Feature f = FEATURES.get(id);
		Group g = f != null ? firstOff(f) : GROUPS.get(id);
		String label = f != null ? f.label : g == null ? id : g.label;
		if (g == null || g.offMod == null) {
			return label + " is off: Origin could not check it against your mods.";
		}
		String mod = displayName(g.offMod);
		if (g.offVersion == null) {
			return label + " is off: it needs " + mod + ", which is not installed.";
		}
		return label + " is off: it was made for another " + mod + " than the one installed (" + ascii(g.offVersion)
				+ "). " + mod + " itself keeps working.";
	}

	private static String ascii(String s) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < s.length() && sb.length() < 40; i++) {
			char c = s.charAt(i);
			if (c >= 0x20 && c < 0x7F) {
				sb.append(c);
			}
		}
		return sb.toString();
	}

	/** Mixin plugin: a mixin no group lists always applies; a listed one only while its group is on. */
	public static boolean mixinAllowed(String mixinClassName) {
		if (!ready) {
			init();
		}
		int dot = mixinClassName.lastIndexOf('.');
		Group g = BY_MIXIN.get(dot < 0 ? mixinClassName : mixinClassName.substring(dot + 1));
		if (g == null) {
			return true;
		}
		return !failed && g.on;
	}

	// ---- data ------------------------------------------------------------------------------------

	private static void read() throws Exception {
		List<String[]> sigLines = new ArrayList<>();
		InputStream in = CompatGates.class.getClassLoader().getResourceAsStream(RESOURCE);
		if (in == null) {
			throw new IllegalStateException("resource missing");
		}
		try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
			String line;
			while ((line = r.readLine()) != null) {
				line = line.trim();
				if (line.isEmpty() || line.startsWith("#")) {
					continue;
				}
				String[] p = line.split("\\|", -1);
				String[] head = p[0].trim().split("\\s+");
				if (head.length != 2) {
					throw new IllegalArgumentException("bad line: " + line);
				}
				if ("group".equals(head[0]) && p.length == 4) {
					Group g = new Group(head[1], p[1].trim());
					for (String need : p[2].split(";")) {
						need = need.trim();
						int sp = need.indexOf(' ');
						if (sp < 0) {
							throw new IllegalArgumentException("bad need: " + need);
						}
						g.needs.put(need.substring(0, sp), need.substring(sp + 1).trim());
					}
					for (String m : p[3].trim().split("\\s+")) {
						if (!m.isEmpty()) {
							g.mixins.add(m);
							BY_MIXIN.put(m, g);
						}
					}
					GROUPS.put(g.id, g);
				} else if ("feature".equals(head[0]) && p.length == 3) {
					Feature f = new Feature(head[1], p[1].trim());
					for (String alt : p[2].split("/")) {
						List<String> ids = new ArrayList<>();
						for (String gid : alt.trim().split("\\s+")) {
							if (!gid.isEmpty()) {
								ids.add(gid);
							}
						}
						f.alternatives.add(ids);
					}
					FEATURES.put(f.id, f);
				} else if ("sig".equals(head[0]) && p.length == 3 && !p[1].trim().isEmpty()
						&& p[2].trim().indexOf('(') > 0) {
					sigLines.add(new String[] {head[1], p[1].trim(), p[2].trim()});
				} else {
					throw new IllegalArgumentException("bad line: " + line);
				}
			}
		}
		for (String[] s : sigLines) {
			Group g = GROUPS.get(s[0]);
			if (g == null) {
				throw new IllegalArgumentException("sig names unknown group " + s[0]);
			}
			g.sigs.add(new String[] {s[1], s[2]});
		}
		for (Feature f : FEATURES.values()) {
			for (List<String> alt : f.alternatives) {
				for (String gid : alt) {
					if (!GROUPS.containsKey(gid)) {
						throw new IllegalArgumentException(f.id + " names unknown group " + gid);
					}
				}
			}
		}
	}

	private static void evaluate(Group g) {
		g.on = true;
		for (Map.Entry<String, String> need : g.needs.entrySet()) {
			String mod = need.getKey();
			ModContainer c = FabricLoader.getInstance().getModContainer(mod).orElse(null);
			g.offMod = mod;
			if (c == null) {
				g.on = false;
				g.why = mod + " not installed";
				return;
			}
			Version v = c.getMetadata().getVersion();
			if (!matches(need.getValue(), v)) {
				g.on = false;
				g.offVersion = v.getFriendlyString();
				g.why = mod + " " + g.offVersion + " is outside " + need.getValue();
				return;
			}
			g.offMod = null;
		}
		checkSignatures(g);
	}

	/**
	 * The version range passed; now every method the group's mixins inject into must exist with the exact
	 * descriptor. Off on the first group with a miss, naming every missing method in its one log line.
	 */
	private static void checkSignatures(Group g) {
		if (g.sigs.isEmpty()) {
			return;
		}
		StringBuilder misses = new StringBuilder();
		Map<String, byte[]> classes = new HashMap<>();
		for (String[] sig : g.sigs) {
			byte[] bytes;
			if (classes.containsKey(sig[0])) {
				bytes = classes.get(sig[0]);
			} else {
				bytes = classBytes(sig[0]);
				classes.put(sig[0], bytes);
			}
			String miss = ClassSignatures.missing(bytes, sig[0], sig[1], CompatGates::runtimeClassName);
			if (miss != null) {
				misses.append(misses.length() == 0 ? "" : ", ").append(miss);
			}
		}
		if (misses.length() == 0) {
			return;
		}
		g.on = false;
		g.why = "signature check failed against " + versions(g) + ": " + misses;
		// The Mod Menu note: "made for another JEI than the one installed (x)". True here too: this JEI
		// build moved or changed a method Origin's switch hooks.
		g.offMod = g.needs.isEmpty() ? null : g.needs.keySet().iterator().next();
		ModContainer c = g.offMod == null ? null : FabricLoader.getInstance().getModContainer(g.offMod).orElse(null);
		g.offVersion = c == null ? null : c.getMetadata().getVersion().getFriendlyString();
	}

	/** The class file of {@code binaryName} as the game will load it, or null when it is not on the classpath. */
	private static byte[] classBytes(String binaryName) {
		try (InputStream in = CompatGates.class.getClassLoader().getResourceAsStream(binaryName.replace('.', '/') + ".class")) {
			if (in == null) {
				return null;
			}
			java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
			byte[] buf = new byte[8192];
			int n;
			while ((n = in.read(buf)) > 0) {
				out.write(buf, 0, n);
			}
			return out.toByteArray();
		} catch (Throwable t) {
			return null;
		}
	}

	/**
	 * Internal class name from a sig descriptor -> the name in the running namespace. Only intermediary
	 * names (net/minecraft/class_N) are mapped; every other name, and all of 26.x, is already literal.
	 */
	private static String runtimeClassName(String internal) {
		if (!internal.startsWith("net/minecraft/class_")) {
			return internal;
		}
		return FabricLoader.getInstance().getMappingResolver()
				.mapClassName("intermediary", internal.replace('/', '.')).replace('.', '/');
	}

	/** "jei 19.51.0.418" for the group's mods as loaded (for the log line). */
	private static String versions(Group g) {
		StringBuilder sb = new StringBuilder();
		for (String mod : g.needs.keySet()) {
			ModContainer c = FabricLoader.getInstance().getModContainer(mod).orElse(null);
			sb.append(sb.length() == 0 ? "" : ", ").append(mod).append(' ')
					.append(c == null ? "?" : c.getMetadata().getVersion().getFriendlyString());
		}
		return sb.toString();
	}

	private static boolean matches(String ranges, Version v) {
		for (String alt : ranges.split("\\s+or\\s+")) {
			try {
				if (VersionPredicate.parse(alt.trim()).test(v)) {
					return true;
				}
			} catch (Exception e) {
				log("bad version range '" + alt.trim() + "': " + e);
			}
		}
		return false;
	}

	private static void evaluate(Feature f) {
		f.on = false;
		for (List<String> alt : f.alternatives) {
			boolean all = true;
			for (String gid : alt) {
				all &= GROUPS.get(gid).on;
			}
			if (all) {
				f.on = true;
				return;
			}
		}
		Group off = firstOff(f);
		f.note = off == null ? "Off: not supported here" : noteFor(off);
	}

	private static Group firstOff(Feature f) {
		for (String gid : f.alternatives.get(0)) {
			Group g = GROUPS.get(gid);
			if (!g.on) {
				return g;
			}
		}
		return null;
	}

	private static String firstWhy(Feature f) {
		Group g = firstOff(f);
		return g == null ? "no supported combination" : g.why;
	}

	private static String noteFor(Group g) {
		return g.offMod == null ? "Off: not supported here" : "Off: needs a supported " + displayName(g.offMod);
	}

	private static String displayName(String mod) {
		switch (mod) {
			case "sodium": return "Sodium";
			case "iris": return "Iris";
			case "jei": return "JEI";
			case "indium": return "Indium";
			default: return mod;
		}
	}

	private static boolean named(String gid) {
		for (Feature f : FEATURES.values()) {
			for (List<String> alt : f.alternatives) {
				if (alt.contains(gid)) {
					return true;
				}
			}
		}
		return false;
	}

	/** One line in latest.log, prefixed so CI can grep it ("Origin compat gate: ..."). */
	private static void log(String msg) {
		String line = "Origin compat gate: " + msg;
		try {
			org.apache.logging.log4j.LogManager.getLogger("originclient").info(line);
		} catch (Throwable t) {
			System.out.println("[originclient] " + line);
		}
	}
}
