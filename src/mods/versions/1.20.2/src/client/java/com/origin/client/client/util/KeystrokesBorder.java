package com.origin.client.client.util;

/**
 * Key Strokes' Border used to start ON at thickness 0 (N94-2): the switch showed on and nothing
 * was drawn, and flipping it changed nothing. New installs start with it off at thickness 1 and
 * the menu default is off too. This decides how a saved config gets there without changing what
 * is on screen; each module's Mods applies it once per config (util/OnceSteps marker
 * "optionVersion.keystrokes.border") and on every profile apply (a profile saved earlier brings
 * the old pair back).
 *
 * <p>Plain strings in and out (the caller adapts its JSON), so it is pure, Java 8, and tested by
 * tools/qol-tests. Version-independent, so it lives in shared/; the pre-1.20 and 1.8.9 modules
 * carry identical copies.
 */
public final class KeystrokesBorder {
	private KeystrokesBorder() {
	}

	/**
	 * The new {border, borderThickness} for a Key Strokes config, or null to leave it alone. A
	 * null in the result leaves that value as it is. {@code border} and {@code thickness} are the
	 * saved values as text, null when not saved. {@code once}: the one-time config step, which
	 * also writes a never-saved Border as on (the old default the player has been seeing, since
	 * the default is now off); false for a profile apply, which only fixes the invisible pair.
	 */
	public static String[] upgrade(String border, String thickness, boolean once) {
		boolean on;
		if (border == null) {
			on = true;   // the old default
		} else if ("true".equals(border.trim())) {
			on = true;
		} else if ("false".equals(border.trim())) {
			on = false;
		} else {
			return null;   // hand-edited: leave it alone
		}
		double t = 1;      // the schema default
		if (thickness != null) {
			try {
				t = Double.parseDouble(thickness.trim());
			} catch (NumberFormatException handEdited) {
				return null;
			}
		}
		if (on && t <= 0) {
			return new String[] {"false", "1"};   // nothing was drawn, nothing is drawn
		}
		if (once && border == null) {
			return new String[] {"true", null};   // keep the border the player sees
		}
		return null;
	}
}
