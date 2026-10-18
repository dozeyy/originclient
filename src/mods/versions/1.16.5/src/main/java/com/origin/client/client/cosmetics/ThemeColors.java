package com.origin.client.client.cosmetics;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * A theme item's colours (FORMAT.md "Themes"), as the build wrote them into item.json:
 * {@code "deck": {"PANEL": "#rrggbbaa", ...}} for {@link com.origin.client.client.gui.OriginDeck}
 * and {@code "workbench": {...}} for {@link com.origin.client.client.theme.OriginTheme},
 * token names exactly as those classes' fields. Every value was worked out from the main
 * theme by cosmetics/lib/theme.mjs, so nothing is derived here. Unreadable values are
 * dropped (that token keeps today's colour). {@link com.origin.client.client.theme.ThemeManager}
 * applies the worn theme.
 *
 * Java 8 (1.16.5) has no records: a final class with the record's accessors.
 */
public final class ThemeColors {
	private final Map<String, Integer> deck;
	private final Map<String, Integer> workbench;

	public ThemeColors(Map<String, Integer> deck, Map<String, Integer> workbench) {
		this.deck = deck;
		this.workbench = workbench;
	}

	public Map<String, Integer> deck() {
		return this.deck;
	}

	public Map<String, Integer> workbench() {
		return this.workbench;
	}

	static ThemeColors parse(JsonObject o) {
		return new ThemeColors(tokens(o.get("deck")), tokens(o.get("workbench")));
	}

	/** A deck token, or {@code def} if the theme doesn't set it (the Cosmetics screen's swatch fallback). */
	public int deck(String name, int def) {
		return deck.getOrDefault(name, def);
	}

	private static Map<String, Integer> tokens(JsonElement e) {
		Map<String, Integer> out = new HashMap<>();
		if (e instanceof JsonObject) {
			JsonObject o = (JsonObject) e;
			for (Map.Entry<String, JsonElement> en : o.entrySet()) {
				if (en.getValue().isJsonPrimitive()) {
					Integer v = parse(en.getValue().getAsString());
					if (v != null) {
						out.put(en.getKey(), v);
					}
				}
			}
		}
		return Collections.unmodifiableMap(out);
	}

	/** {@link #parse}, or {@code def} for anything unreadable (CosmeticDef's effect colours use it too). */
	static int hex(String s, int def) {
		Integer v = parse(s);
		return v != null ? v : def;
	}

	/** "#rgb", "#rrggbb" or "#rrggbbaa" (CSS order) to 0xAARRGGBB; null for anything else. */
	static Integer parse(String s) {
		if (s == null) {
			return null;
		}
		try {
			String h = s.startsWith("#") ? s.substring(1) : s;
			if (h.length() == 3) {
				h = "" + h.charAt(0) + h.charAt(0) + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2);
			}
			if (h.length() == 6) {
				return 0xFF000000 | Integer.parseInt(h, 16);
			}
			if (h.length() == 8) {
				long v = Long.parseLong(h, 16);
				return (int) (((v & 0xFF) << 24) | (v >>> 8));
			}
		} catch (NumberFormatException ignored) {
			// fall through
		}
		return null;
	}
}
