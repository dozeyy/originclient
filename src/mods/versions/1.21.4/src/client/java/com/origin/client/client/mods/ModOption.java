package com.origin.client.client.mods;

// One row in a mod's settings face: a typed spec (what control to draw, its
// bounds/choices, its default). Live values live in ModsConfig; this is only
// the schema. Every settings face across all mods is built from this one pool
// of row kinds, so the renderer stays a single switch.
//
// Beyond the plain controls, two structural kinds shape the premium pages:
//   HEADER   — a section label (GENERAL, COLOR, ...); draws no control.
//   dependsOn — a row nested under a TOGGLE: indented, and only shown while
//               that toggle is on (Block Outline -> width/mode/color, etc.).
public final class ModOption {
	public enum Kind { HEADER, TOGGLE, SLIDER, DROPDOWN, KEYBIND, COLOR }

	public final String key;
	public final String label;
	public final Kind kind;

	// Optional one-line hover description ("what this setting does"). null = none;
	// self-explanatory rows (color pickers, obvious names) are left null on
	// purpose. Set fluently via .tip("...") after any factory. Mutable so it can
	// be attached without threading a param through every factory.
	public String tooltip = null;

	// key of the TOGGLE this row is nested under; null = top-level row.
	public final String dependsOn;

	// SLIDER
	public final double min, max, step, defNum;
	public final String format; // e.g. "%.0f", "%.1fx", "%.0f%%"

	// TOGGLE
	public final boolean defBool;

	// COLOR — default ARGB; the swatch opens the shared color picker.
	public final int defColor;

	// KEYBIND (GLFW keycode, -1 = unbound)
	public final int defKey;

	// DROPDOWN (ordered choices; first is default)
	public final String[] modes;

	private ModOption(String key, String label, Kind kind, String dependsOn, double min, double max,
					  double step, double defNum, String format, boolean defBool, int defColor,
					  int defKey, String[] modes) {
		this.key = key;
		this.label = label;
		this.kind = kind;
		this.dependsOn = dependsOn;
		this.min = min;
		this.max = max;
		this.step = step;
		this.defNum = defNum;
		this.format = format;
		this.defBool = defBool;
		this.defColor = defColor;
		this.defKey = defKey;
		this.modes = modes;
	}

	// ---- factories ----

	private static int headerSeq = 0;

	/** A section label row (GENERAL, COLOR, ...). Draws no control. */
	public static ModOption header(String label) {
		return new ModOption("§h" + (headerSeq++), label, Kind.HEADER, null,
				0, 0, 0, 0, null, false, 0, -1, null);
	}

	public static ModOption toggle(String key, String label, boolean def) {
		return new ModOption(key, label, Kind.TOGGLE, null, 0, 0, 0, 0, null, def, 0, -1, null);
	}

	public static ModOption slider(String key, String label, double min, double max, double step, double def, String format) {
		return new ModOption(key, label, Kind.SLIDER, null, min, max, step, def, format, false, 0, -1, null);
	}

	/** Color option — the swatch opens the shared chroma color picker. */
	public static ModOption color(String key, String label, int defColor) {
		return new ModOption(key, label, Kind.COLOR, null, 0, 0, 0, 0, null, false, defColor, -1, null);
	}

	/** Color option defaulting to white. */
	public static ModOption color(String key, String label) {
		return color(key, label, 0xFFFFFFFF);
	}

	public static ModOption keybind(String key, String label, int defGlfwKey) {
		return new ModOption(key, label, Kind.KEYBIND, null, 0, 0, 0, 0, null, false, 0, defGlfwKey, null);
	}

	/** Dropdown rendered as `< value >`; cycles through the given choices. */
	public static ModOption dropdown(String key, String label, String... choices) {
		return new ModOption(key, label, Kind.DROPDOWN, null, 0, 0, 0, 0, null, false, 0, -1, choices);
	}

	/** Back-compat alias — same as {@link #dropdown}. */
	public static ModOption mode(String key, String label, String... choices) {
		return dropdown(key, label, choices);
	}

	/** Returns a copy of this row nested under the given TOGGLE key. */
	public ModOption under(String parentKey) {
		ModOption c = new ModOption(key, label, kind, parentKey, min, max, step, defNum, format,
				defBool, defColor, defKey, modes);
		c.tooltip = tooltip;
		return c;
	}

	/** Attaches a hover description; returns this for fluent chaining. */
	public ModOption tip(String description) {
		this.tooltip = description;
		return this;
	}
}
