package com.origin.client.client.gui;

import com.origin.client.client.mods.ModOption;

import java.util.List;

/**
 * STUB for MC 1.18.2 (JEI 10). JEI 10 ships NO config API — there is no
 * {@code mezz.jei.api.runtime.config.IJeiConfigManager} to read (it arrived at
 * JEI 11, was String-shaped through ~17, Component-shaped from 19). So the JEI
 * page on 1.18.2 exposes only the on/off toggle (from the mod list); opening it
 * shows "No additional settings". The toggle itself (JeiGuiEventHandlerMixin /
 * JeiClientInputHandlerMixin) works exactly as on every other version — this stub
 * only makes the settings-tab code paths in OriginModMenuScreen compile and
 * degrade cleanly. Referencing no {@code mezz.jei.*} type keeps it compilable
 * against JEI 10, which lacks the config classes the real JeiSettings uses.
 *
 * If a future 1.18.2 JEI ever exposes a config API, swap this for the real
 * JeiSettings (the JEI-14-shaped one from the 1.19.x / 1.20 modules).
 */
public final class JeiSettings {
	private JeiSettings() {
	}

	/** No config API on JEI 10 → no rows. The page shows "No additional settings". */
	public static List<ModOption> options() {
		return List.of();
	}

	public static boolean getBool(String key) {
		return false;
	}

	public static void setBool(String key, boolean b) {
	}

	public static double getNum(String key) {
		return 0;
	}

	public static void setNum(String key, double d) {
	}

	public static String getMode(String key) {
		return "";
	}

	public static void setMode(String key, String token) {
	}

	public static String getMulti(String key) {
		return "";
	}

	public static void setMulti(String key, String csv) {
	}

	/** "lookupHistory" / "ingredient_list" → "Lookup History" / "Ingredient List".
	 *  Kept identical to the real JeiSettings so OriginMultiSelect renders the same. */
	static String prettify(String raw) {
		if (raw == null || raw.isEmpty()) {
			return "";
		}
		StringBuilder out = new StringBuilder(raw.length() + 4);
		boolean newWord = true;
		char prev = 0;
		for (int i = 0; i < raw.length(); i++) {
			char c = raw.charAt(i);
			if (c == '_' || c == '-' || c == ' ') {
				out.append(' ');
				newWord = true;
				prev = c;
				continue;
			}
			if (i > 0 && Character.isUpperCase(c) && (Character.isLowerCase(prev) || Character.isDigit(prev))) {
				out.append(' ');
				newWord = true;
			}
			out.append(newWord ? Character.toUpperCase(c) : c);
			newWord = false;
			prev = c;
		}
		return out.toString();
	}
}
