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
 * <p>A need may also be {@code <mod> absent}: met only while that mod is NOT loaded. Vulkan mode (VulkanMod
 * replaces Minecraft's OpenGL renderer; the launcher's opt-in Vulkan graphics) uses it: a group listing
 * {@code vulkanmod absent} first switches off, mixins included, before Mixin applies anything, and the Mod
 * Menu says "Off with Vulkan graphics". With the mod absent the need is met and leaves no trace, so every
 * other decision is exactly what it was without the line. {@link #VULKANMOD} is also a built-in id:
 * {@code on(VULKANMOD)} / {@link #vulkan()} is true while VulkanMod is loaded, in every module, data or not.
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
		/** Off because offMod IS loaded (an {@code absent} need), not because it is missing or the wrong version. */
		boolean offPresent;
		Group(String id, String label) { this.id = id; this.label = label; }
	}

	private static final class Feature {
		final String id, label;
		final List<List<String>> alternatives = new ArrayList<>();
		boolean on;
		String note;
		Feature(String id, String label) { this.id = id; this.label = label; }
	}

	/** Built-in condition id: true while VulkanMod (Origin's opt-in Vulkan graphics) is loaded. */
	public static final String VULKANMOD = "vulkanmod";
	/**
	 * The group of vanilla-target hooks that only work on OpenGL (1.21.1: the after-shaders overlay pass, the
	 * cull-frustum fingerprint, Color Saturation, Motion Blur). Its one need is {@code vulkanmod absent}, which
	 * needs no data to decide, so when the data fails to read it falls back to "on unless VulkanMod is loaded"
	 * instead of off: before Vulkan mode these hooks applied unconditionally, and a read failure must not take
	 * them away on normal graphics (nor turn them on under Vulkan). See {@link #failedFallback}.
	 */
	public static final String OPENGL = "opengl";
	/** The range word of a need that is met only while its mod is NOT loaded ({@code vulkanmod absent}). */
	static final String ABSENT = "absent";

	/** Which mods are loaded, at which version (null = not loaded). Fabric Loader in game; a fixed set in tests. */
	interface ModLookup {
		Version version(String mod);
	}

	private static final ModLookup FABRIC = new ModLookup() {
		@Override
		public Version version(String mod) {
			ModContainer c = FabricLoader.getInstance().getModContainer(mod).orElse(null);
			return c == null ? null : c.getMetadata().getVersion();
		}
	};

	private static volatile Boolean vulkan;

	/**
	 * True while VulkanMod is loaded (Vulkan mode): raw OpenGL beyond its shims, and the internals of the
	 * renderer it replaces, are off limits there. Safe during Mixin's config phase (Fabric Loader only).
	 */
	public static boolean vulkan() {
		Boolean v = vulkan;
		if (v == null) {
			boolean loaded;
			try {
				loaded = FabricLoader.getInstance().isModLoaded(VULKANMOD);
			} catch (Throwable t) {
				loaded = false;
			}
			v = loaded;
			vulkan = v;
		}
		return v;
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
				evaluate(g, FABRIC, true);
			}
			for (Feature f : FEATURES.values()) {
				evaluate(f, GROUPS);
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
			// A passed signature check says so too: tools/boot-smoke/smoke.py fails a module whose data has
			// sig lines and whose boot lacks this line (proof the check ran and passed, not just that nothing
			// failed). Keep the wording in step with smoke.py's SIG_ON.
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

	/**
	 * True when group or feature {@code id} may run. Unknown ids are on (see class doc). The built-in
	 * {@link #VULKANMOD} is a condition, not a hook: true while VulkanMod is loaded.
	 */
	public static boolean on(String id) {
		if (VULKANMOD.equals(id)) {
			return vulkan();
		}
		if (!ready) {
			init();
		}
		if (failed) {
			return failedFallback(id, FEATURES, vulkan());
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
			if (opengl(id, FEATURES)) {
				return vulkan() ? "Off with Vulkan graphics" : null;
			}
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
		if (g.offPresent) {
			return VULKANMOD.equals(g.offMod)
					? label + " is off with Vulkan graphics. It works again with Vulkan graphics turned off."
					: label + " is off while " + displayName(g.offMod) + " is installed.";
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
		return failed ? failedFallback(g.id, FEATURES, vulkan()) : g.on;
	}

	/**
	 * What id decides when the data failed to read: every hook off (the safe direction), except the
	 * {@link #OPENGL} group and features that need only it, which stay "on unless VulkanMod is loaded".
	 */
	private static boolean failedFallback(String id, Map<String, Feature> features, boolean vulkan) {
		return opengl(id, features) && !vulkan;
	}

	/** True for the OPENGL group and for a feature whose every alternative is just that group. */
	private static boolean opengl(String id, Map<String, Feature> features) {
		if (OPENGL.equals(id)) {
			return true;
		}
		Feature f = features.get(id);
		if (f == null || f.alternatives.isEmpty()) {
			return false;
		}
		for (List<String> alt : f.alternatives) {
			if (alt.size() != 1 || !OPENGL.equals(alt.get(0))) {
				return false;
			}
		}
		return true;
	}

	/** Test seam for {@link #failedFallback}: the decision for {@code id} if reading {@code data} had failed. */
	static boolean failedFallback(String data, String id, boolean vulkan) throws Exception {
		Map<String, Feature> features = new LinkedHashMap<>();
		parse(new BufferedReader(new java.io.StringReader(data)), new LinkedHashMap<>(), features, new LinkedHashMap<>());
		return failedFallback(id, features, vulkan);
	}

	// ---- data ------------------------------------------------------------------------------------

	private static void read() throws Exception {
		InputStream in = CompatGates.class.getClassLoader().getResourceAsStream(RESOURCE);
		if (in == null) {
			throw new IllegalStateException("resource missing");
		}
		try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
			parse(r, GROUPS, FEATURES, BY_MIXIN);
		}
	}

	private static void parse(BufferedReader r, Map<String, Group> groups, Map<String, Feature> features,
			Map<String, Group> byMixin) throws Exception {
		List<String[]> sigLines = new ArrayList<>();
		{
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
							byMixin.put(m, g);
						}
					}
					groups.put(g.id, g);
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
					features.put(f.id, f);
				} else if ("sig".equals(head[0]) && p.length == 3 && !p[1].trim().isEmpty()
						&& p[2].trim().indexOf('(') > 0) {
					sigLines.add(new String[] {head[1], p[1].trim(), p[2].trim()});
				} else {
					throw new IllegalArgumentException("bad line: " + line);
				}
			}
		}
		for (String[] s : sigLines) {
			Group g = groups.get(s[0]);
			if (g == null) {
				throw new IllegalArgumentException("sig names unknown group " + s[0]);
			}
			g.sigs.add(new String[] {s[1], s[2]});
		}
		for (Feature f : features.values()) {
			for (List<String> alt : f.alternatives) {
				for (String gid : alt) {
					if (!groups.containsKey(gid)) {
						throw new IllegalArgumentException(f.id + " names unknown group " + gid);
					}
				}
			}
		}
	}

	/** {@code sigs}: run the signature check (false only in tests, which have no Sodium/Iris/JEI classes). */
	private static void evaluate(Group g, ModLookup mods, boolean sigs) {
		g.on = true;
		for (Map.Entry<String, String> need : g.needs.entrySet()) {
			String mod = need.getKey();
			Version v = mods.version(mod);
			g.offMod = mod;
			if (ABSENT.equals(need.getValue())) {
				if (v != null) {
					g.on = false;
					g.offPresent = true;
					g.offVersion = v.getFriendlyString();
					g.why = mod + " " + g.offVersion + " is loaded";
					return;
				}
				g.offMod = null;
				continue;
			}
			if (v == null) {
				g.on = false;
				g.why = mod + " not installed";
				return;
			}
			if (!matches(need.getValue(), v)) {
				g.on = false;
				g.offVersion = v.getFriendlyString();
				g.why = mod + " " + g.offVersion + " is outside " + need.getValue();
				return;
			}
			g.offMod = null;
		}
		if (!sigs) {
			return;
		}
		try {
			checkSignatures(g, mods);
		} catch (Throwable t) {
			// Only this group goes off: a throw reaching init's catch would turn off every hook (Sodium, Iris too).
			g.on = false;
			g.why = "signature check could not run (" + t + ")";
		}
	}

	/**
	 * The version range passed; now every method the group's mixins inject into must exist with the exact
	 * descriptor. Off on the first group with a miss, naming every missing method in its one log line.
	 */
	private static void checkSignatures(Group g, ModLookup mods) {
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
		g.offMod = null;
		for (Map.Entry<String, String> need : g.needs.entrySet()) {
			if (!ABSENT.equals(need.getValue())) {
				g.offMod = need.getKey();
				break;
			}
		}
		Version v = g.offMod == null ? null : mods.version(g.offMod);
		g.offVersion = v == null ? null : v.getFriendlyString();
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
		for (Map.Entry<String, String> need : g.needs.entrySet()) {
			if (ABSENT.equals(need.getValue())) {
				continue;
			}
			String mod = need.getKey();
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

	private static void evaluate(Feature f, Map<String, Group> groups) {
		f.on = false;
		for (List<String> alt : f.alternatives) {
			boolean all = true;
			for (String gid : alt) {
				all &= groups.get(gid).on;
			}
			if (all) {
				f.on = true;
				return;
			}
		}
		Group off = firstOff(f, groups);
		f.note = off == null ? "Off: not supported here" : noteFor(off);
	}

	private static Group firstOff(Feature f) {
		return firstOff(f, GROUPS);
	}

	private static Group firstOff(Feature f, Map<String, Group> groups) {
		for (String gid : f.alternatives.get(0)) {
			Group g = groups.get(gid);
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
		if (g.offMod == null) {
			return "Off: not supported here";
		}
		if (g.offPresent) {
			return VULKANMOD.equals(g.offMod) ? "Off with Vulkan graphics" : "Off with " + displayName(g.offMod) + " installed";
		}
		return "Off: needs a supported " + displayName(g.offMod);
	}

	private static String displayName(String mod) {
		switch (mod) {
			case "sodium": return "Sodium";
			case "iris": return "Iris";
			case "jei": return "JEI";
			case "indium": return "Indium";
			case "vulkanmod": return "VulkanMod";
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

	/**
	 * Test seam (no game, no Fabric Loader): every decision {@code data} makes for the mods in {@code mods},
	 * without the signature check. Group and feature ids map to "on" or their Mod Menu note; each listed
	 * mixin maps ("mixin <name>") to "on" or "off". The in-game path runs the same parse and evaluate.
	 */
	static Map<String, String> decide(String data, ModLookup mods) throws Exception {
		Map<String, Group> groups = new LinkedHashMap<>();
		Map<String, Feature> features = new LinkedHashMap<>();
		Map<String, Group> byMixin = new LinkedHashMap<>();
		parse(new BufferedReader(new java.io.StringReader(data)), groups, features, byMixin);
		for (Group g : groups.values()) {
			evaluate(g, mods, false);
		}
		for (Feature f : features.values()) {
			evaluate(f, groups);
		}
		Map<String, String> out = new LinkedHashMap<>();
		for (Group g : groups.values()) {
			out.put(g.id, g.on ? "on" : noteFor(g));
		}
		for (Feature f : features.values()) {
			out.put(f.id, f.on ? "on" : f.note);
		}
		for (Map.Entry<String, Group> m : byMixin.entrySet()) {
			out.put("mixin " + m.getKey(), m.getValue().on ? "on" : "off");
		}
		return out;
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
