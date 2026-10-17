package com.origin.client.client.cosmetics;

import com.google.gson.JsonObject;

/**
 * A theme item's palette (FORMAT.md "Themes"): eight tokens, 0xAARRGGBB.
 * Missing tokens fall back to Origin's default palette, so a theme can be
 * partial. {@link com.origin.client.client.theme.ThemeManager} pushes the
 * worn theme into OriginTheme / OriginDeck.
 */
public record ThemeColors(int bg, int card, int line, int text, int dim, int acc, int accHi, int onAcc) {

	/** Today's menu colours expressed as theme tokens (what the store's example theme block also lists). */
	public static final ThemeColors DEFAULT = new ThemeColors(0xFF0E1110, 0xFF1D2420, 0xFF2E3B33, 0xFFF1E9D2,
			0xFFB8B5A6, 0xFF5FD09B, 0xFF83E2B5, 0xFF0A1710);

	static ThemeColors parse(JsonObject o) {
		ThemeColors d = DEFAULT;
		return new ThemeColors(hex(Cosmetics.str(o, "bg", null), d.bg), hex(Cosmetics.str(o, "card", null), d.card),
				hex(Cosmetics.str(o, "line", null), d.line), hex(Cosmetics.str(o, "text", null), d.text),
				hex(Cosmetics.str(o, "dim", null), d.dim), hex(Cosmetics.str(o, "acc", null), d.acc),
				hex(Cosmetics.str(o, "accHi", null), d.accHi), hex(Cosmetics.str(o, "onAcc", null), d.onAcc));
	}

	/** "#rgb", "#rrggbb" or "#rrggbbaa" (CSS order) to 0xAARRGGBB; `def` for anything else. */
	static int hex(String s, int def) {
		if (s == null) {
			return def;
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
			// fall through to the default
		}
		return def;
	}
}
