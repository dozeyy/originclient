package com.origin.client.client.mods;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Origin steps aside (Will, 2026-10-26): when a player's own mod does the same job as
 * one of Origin's switchable mods, Origin's turns itself off, its Mod Menu card says
 * which mod replaced it, and the player can turn Origin's back on.
 *
 * Hand copy of src/mods/shared/.../mods/StepAside.java for this .no-shared-sync module.
 * Same logic and accessors; JAVA 8 here, so the shared records are small final classes
 * and List.of/Map.copyOf are the Collections equivalents. Only {@link #TABLE} differs:
 * it lists only the Origin features this module has, with this loader's mod ids.
 *
 * {@link #TABLE} is the whole policy: Origin feature (a Mods id) -> the mod ids that
 * replace it. {@link StepAsideDetect} resolves it ONCE at client init; after that every
 * read is a map get (an empty-map check when nothing is replaced, the common case), so
 * Mods.on stays as cheap as before.
 *
 * Pure Java on purpose (no Minecraft, no loader, no Gson). The per-module wiring
 * (Mods.on/setOn, the card note, the init call) is in
 * .claude/skills/origin-port/recipes/modmenu-options-config.md.
 */
public final class StepAside {

	/**
	 * The player-forced-on flag, stored inside the feature's own block of
	 * originclient-mods.json ("mods": {"freelook": {"enabled": true, "forcedOn": true}}).
	 * Present only while forced: absent means "not forced", so every config written
	 * before this existed reads correctly with no migration, and clearing the flag
	 * removes the key, leaving the file exactly as it was.
	 */
	public static final String FORCED_KEY = "forcedOn";

	/**
	 * One overlap. asideOn = what the feature reads as while stepped aside: false for a
	 * normal feature (Origin's version switches off); true for a feature whose "on" means
	 * "Origin lets the other mod run" (JEI). shippedByOrigin = Origin bundles a mod with
	 * that id itself, so only a copy NOT nested in Origin's jar counts (StepAsideDetect).
	 */
	public static final class Row {
		private final String feature;
		private final boolean asideOn;
		private final boolean shippedByOrigin;
		private final List<String> foreignIds;

		public Row(String feature, boolean asideOn, boolean shippedByOrigin, List<String> foreignIds) {
			this.feature = feature;
			this.asideOn = asideOn;
			this.shippedByOrigin = shippedByOrigin;
			this.foreignIds = foreignIds;
		}

		public String feature() {
			return feature;
		}

		public boolean asideOn() {
			return asideOn;
		}

		public boolean shippedByOrigin() {
			return shippedByOrigin;
		}

		public List<String> foreignIds() {
			return foreignIds;
		}
	}

	/**
	 * THE table. Add a row only when the other mod really does the same job (its own
	 * description/behaviour), never just because both touch the same game code.
	 *  - freelook <- freecam: both let you look around without turning your player.
	 *  - zoom <- zoomify: a zoom mod, also on the C key by default.
	 *  - saturation <- appleskin: Origin's Saturation IS a built-in AppleSkin (hunger
	 *    overlay, held-food preview, food tooltip).
	 *  - worldeditcui <- worldeditcui: the WorldEdit selection overlay; two would fight
	 *    over the worldedit:cui channel.
	 * No jei row: Origin has no JEI on this version (no Fabric JEI exists for it).
	 */
	public static final List<Row> TABLE = Collections.unmodifiableList(Arrays.asList(
			new Row("freelook", false, false, ids("freecam")),
			new Row("zoom", false, false, ids("zoomify")),
			new Row("saturation", false, false, ids("appleskin")),
			new Row("worldeditcui", false, false, ids("worldeditcui"))));


	/** A resolved overlap: the row and the display name of the mod that replaced it. */
	public static final class Replaced {
		private final Row row;
		private final String by;

		public Replaced(Row row, String by) {
			this.row = row;
			this.by = by;
		}

		public Row row() {
			return row;
		}

		public String by() {
			return by;
		}
	}

	// feature -> Replaced. Written once by resolve(); empty until then (and forever when
	// nothing overlaps), which is exactly the old behaviour.
	private static volatile Map<String, Replaced> resolved = Collections.emptyMap();

	private StepAside() {
	}

	private static List<String> ids(String... ids) {
		return Collections.unmodifiableList(Arrays.asList(ids));
	}

	/**
	 * Resolves the table. foreignName: mod id -> that mod's display name when a copy that
	 * counts (see Row.shippedByOrigin) is loaded, else null. The first loaded id in a
	 * row's list names the replacement. Never throws: a failing lookup counts as absent.
	 */
	public static Map<String, Replaced> resolve(Function<String, String> foreignName) {
		Map<String, Replaced> out = new HashMap<>();
		for (Row row : TABLE) {
			for (String id : row.foreignIds()) {
				String name;
				try {
					name = foreignName.apply(id);
				} catch (RuntimeException e) {
					name = null;
				}
				if (name != null) {
					out.put(row.feature(), new Replaced(row, displayName(name, id)));
					break;
				}
			}
		}
		resolved = out.isEmpty() ? Collections.<String, Replaced>emptyMap() : Collections.unmodifiableMap(out);
		return resolved;
	}

	/** True when at least one feature is replaced (Mods.on's fast path). */
	public static boolean any() {
		return !resolved.isEmpty();
	}

	/** Every replaced feature id, in table order (dev-shots, logs). */
	public static List<String> replacedFeatures() {
		Map<String, Replaced> r = resolved;
		ArrayList<String> out = new ArrayList<>();
		for (Row row : TABLE) {
			if (r.containsKey(row.feature())) {
				out.add(row.feature());
			}
		}
		return out;
	}

	/** The overlap for this feature, or null when nothing replaces it. */
	public static Replaced replaced(String feature) {
		Map<String, Replaced> r = resolved;
		return r.isEmpty() ? null : r.get(feature);
	}

	/** True while the feature is stepped aside: replaced and the player has not forced it on. */
	public static boolean aside(String feature, boolean forced) {
		return !forced && replaced(feature) != null;
	}

	/** What Mods.on returns: the player's own switch, unless the feature is stepped aside. */
	public static boolean effectiveOn(String feature, boolean enabled, boolean forced) {
		Replaced r = replaced(feature);
		if (r == null || forced) {
			return enabled;
		}
		return r.row().asideOn();
	}

	/**
	 * What a switch press writes for a REPLACED feature (an unreplaced one keeps the old
	 * plain "enabled" write). forced: true = write FORCED_KEY, false = remove it.
	 * enabled: the new "enabled" value, or null to leave it as the player had it.
	 *
	 * Pressing toward the stepped-aside state clears the force and leaves "enabled"
	 * alone, so once the other mod is removed Origin's feature is back exactly as the
	 * player had it. Pressing away from it forces Origin's feature into the asked state.
	 */
	public static final class Write {
		private final boolean forced;
		private final Boolean enabled;

		public Write(boolean forced, Boolean enabled) {
			this.forced = forced;
			this.enabled = enabled;
		}

		public boolean forced() {
			return forced;
		}

		public Boolean enabled() {
			return enabled;
		}
	}

	public static Write toggle(String feature, boolean want) {
		Replaced r = replaced(feature);
		if (r == null) {
			return new Write(false, want);
		}
		if (want == r.row().asideOn()) {
			return new Write(false, null);
		}
		return new Write(true, want);
	}

	/** The card's short note: "Replaced by Freecam" (or, when forced, "Kept on with Freecam"); null when not replaced. */
	public static String cardNote(String feature, boolean forced) {
		Replaced r = replaced(feature);
		if (r == null) {
			return null;
		}
		if (!forced) {
			return "Replaced by " + r.by();
		}
		return r.row().asideOn() ? "Origin controls " + r.by() : "Kept on with " + r.by();
	}

	/** The mod page's one-line explanation; null when not replaced. */
	public static String pageNote(String feature, String featureName, boolean forced) {
		Replaced r = replaced(feature);
		if (r == null) {
			return null;
		}
		String by = r.by();
		if (r.row().asideOn()) {
			return forced
					? "Your own " + by + " is installed. Origin's switch is controlling it; turn the switch on to leave it alone again."
					: "Your own " + by + " is installed, so Origin leaves it alone. Turn the switch off to let Origin hide it.";
		}
		return forced
				? by + " is installed too. You kept Origin's " + featureName + " on; turn it off to let " + by + " take over."
				: by + " does this job, so Origin's " + featureName + " is off. Turn it on to use both.";
	}

	/** Minecraft's font + ASCII-only UI strings: drop anything outside printable ASCII, cap the length, fall back to the id. */
	static String displayName(String name, String id) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < name.length() && sb.length() < 32; i++) {
			char c = name.charAt(i);
			if (c >= 0x20 && c < 0x7F) {
				sb.append(c);
			}
		}
		String s = sb.toString().trim().replaceAll(" {2,}", " ");
		return s.isEmpty() ? id : s;
	}
}
