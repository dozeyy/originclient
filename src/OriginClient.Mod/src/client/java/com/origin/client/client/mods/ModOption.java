package com.origin.client.client.mods;

// One settings row in a mod's settings face: a typed spec (what control to
// draw, its bounds/choices, its default). Live values live in ModsConfig;
// this is only the schema. All 22+ mods share these five row kinds so every
// settings face is built from the same component pool.
public final class ModOption {
	public enum Kind { TOGGLE, SLIDER, COLOR, KEYBIND, MODE }

	public final String key;
	public final String label;
	public final Kind kind;

	// SLIDER
	public final double min, max, step, defNum;
	public final String format; // e.g. "%.0f", "%.1fx", "%.0f%%"

	// TOGGLE
	public final boolean defBool;

	// COLOR (ARGB swatch set; first is default)
	public final int[] swatches;

	// KEYBIND (GLFW keycode, -1 = unbound)
	public final int defKey;

	// MODE
	public final String[] modes;

	private ModOption(String key, String label, Kind kind, double min, double max, double step,
					  double defNum, String format, boolean defBool, int[] swatches, int defKey, String[] modes) {
		this.key = key;
		this.label = label;
		this.kind = kind;
		this.min = min;
		this.max = max;
		this.step = step;
		this.defNum = defNum;
		this.format = format;
		this.defBool = defBool;
		this.swatches = swatches;
		this.defKey = defKey;
		this.modes = modes;
	}

	public static ModOption toggle(String key, String label, boolean def) {
		return new ModOption(key, label, Kind.TOGGLE, 0, 0, 0, 0, null, def, null, -1, null);
	}

	public static ModOption slider(String key, String label, double min, double max, double step, double def, String format) {
		return new ModOption(key, label, Kind.SLIDER, min, max, step, def, format, false, null, -1, null);
	}

	public static ModOption color(String key, String label, int... swatches) {
		return new ModOption(key, label, Kind.COLOR, 0, 0, 0, 0, null, false, swatches, -1, null);
	}

	public static ModOption keybind(String key, String label, int defGlfwKey) {
		return new ModOption(key, label, Kind.KEYBIND, 0, 0, 0, 0, null, false, null, defGlfwKey, null);
	}

	public static ModOption mode(String key, String label, String... modes) {
		return new ModOption(key, label, Kind.MODE, 0, 0, 0, 0, null, false, null, -1, modes);
	}
}
