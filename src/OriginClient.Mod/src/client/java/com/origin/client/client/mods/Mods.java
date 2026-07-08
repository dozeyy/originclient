package com.origin.client.client.mods;

import com.google.gson.JsonPrimitive;
import com.origin.client.client.OriginClientMod;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.lwjgl.glfw.GLFW;

// The single registry behind the mod menu: every mod's id, display name,
// default enable state, and settings schema, plus the typed read/write
// accessors every feature hook uses. Values persist via ModsConfig; the
// legacy originclient.json feature flags are migrated in on first load.
public final class Mods {
	// A mod definition. Icon is drawn by ModIcons keyed on id.
	public record Mod(String id, String name, boolean defaultOn, List<ModOption> options) {
	}

	// Shared overlay color swatches (white first = default, then real hues —
	// overlays like keystrokes/block outline legitimately want color).
	private static final int[] SWATCHES = {
			0xFFFFFFFF, 0xFFE05555, 0xFF55E055, 0xFF5599FF,
			0xFFFFD855, 0xFF55DDDD, 0xFFFF9944, 0xFFCC66FF};

	public static final List<Mod> ALL = new ArrayList<>();

	static {
		// --- HUD readouts ---
		add("fps", "FPS", false,
				ModOption.slider("threshold", "Color thresholds", 0, 1, 1, 1, "%.0f"));
		add("cps", "CPS Counter", false);
		add("coords", "Coords", true,
				ModOption.toggle("biome", "Show biome", true),
				ModOption.toggle("direction", "Show direction", true));
		add("keystrokes", "Keystrokes", false,
				ModOption.color("color", "Key color", SWATCHES),
				ModOption.slider("scale", "Size", 0.5, 2.0, 0.1, 1.0, "%.1fx"));
		add("potionhud", "Potion HUD", false,
				ModOption.slider("scale", "Size", 0.5, 2.0, 0.1, 1.0, "%.1fx"));
		add("armorhud", "Armor HUD", false);
		add("serveraddress", "Server IP", false);
		add("packdisplay", "Pack Display", false);

		// --- gameplay QoL ---
		add("zoom", "Zoom", true,
				ModOption.keybind("key", "Zoom key", GLFW.GLFW_KEY_C),
				ModOption.slider("fov", "Zoom level", 10, 60, 1, 30, "%.0f"));
		add("freelook", "Freelook", true,
				ModOption.keybind("key", "Freelook key", GLFW.GLFW_KEY_LEFT_ALT));
		add("togglesprint", "Toggle Sprint", false,
				ModOption.keybind("key", "Sprint key", -1),
				ModOption.mode("mode", "Mode", "Toggle", "Hold"));
		add("togglesneak", "Toggle Sneak", false,
				ModOption.keybind("key", "Sneak key", -1),
				ModOption.mode("mode", "Mode", "Toggle", "Hold"));
		add("fullbright", "FullBright", false,
				ModOption.slider("gamma", "Brightness", 1, 16, 0.5, 16, "%.1f"));

		// --- world overlays ---
		add("blockoverlay", "Block Overlay", false,
				ModOption.color("color", "Outline color", SWATCHES),
				ModOption.slider("thickness", "Thickness", 1, 3, 1, 1, "%.0f"));
		add("chunkborders", "Chunk Borders", false,
				ModOption.keybind("key", "Toggle key", GLFW.GLFW_KEY_F9),
				ModOption.color("color", "Line color", SWATCHES),
				ModOption.slider("thickness", "Thickness", 1, 3, 1, 1, "%.0f"));
		add("hitboxes", "Hitbox Display", false);
		add("nametags", "Nametag Tweaks", false,
				ModOption.slider("scale", "Size", 0.5, 2.0, 0.1, 1.0, "%.1fx"));

		// --- rendering / cosmetic ---
		add("weather", "Weather Toggle", false);
		add("customsky", "Custom Sky", false,
				ModOption.mode("mode", "Sky", "Flat", "Vanilla"));
		add("timechanger", "Time Changer", false,
				ModOption.slider("time", "Time of day", 0, 24000, 500, 6000, "%.0f"));
		add("motionblur", "Motion Blur", false,
				ModOption.slider("amount", "Blur amount", 1, 3, 1, 2, "%.0f"));
		add("particles", "Particles", false,
				ModOption.mode("mode", "Particles", "All", "Reduced", "Off"));
		add("chat", "Chat", false,
				ModOption.slider("opacity", "Opacity", 0.1, 1.0, 0.05, 1.0, "%.0f%%"),
				ModOption.slider("scale", "Scale", 0.5, 1.0, 0.05, 1.0, "%.0f%%"),
				ModOption.toggle("timestamps", "Timestamps", false));
		add("scoreboard", "Scoreboard", false,
				ModOption.slider("scale", "Size", 0.5, 1.5, 0.05, 1.0, "%.1fx"));
	}

	private static void add(String id, String name, boolean defaultOn, ModOption... options) {
		ALL.add(new Mod(id, name, defaultOn, List.of(options)));
	}

	private Mods() {
	}

	public static Mod byId(String id) {
		for (Mod m : ALL) {
			if (m.id().equals(id)) {
				return m;
			}
		}
		return null;
	}

	// ---- typed access (all read-through to ModsConfig with schema defaults) ----

	public static boolean on(String modId) {
		var v = raw(modId).get("enabled");
		if (v != null) {
			return v.getAsBoolean();
		}
		Mod m = byId(modId);
		return m != null && m.defaultOn();
	}

	public static void setOn(String modId, boolean on) {
		raw(modId).put("enabled", new JsonPrimitive(on));
		ModsConfig.save();
	}

	public static boolean bool(String modId, String key) {
		var v = raw(modId).get(key);
		if (v != null) {
			return v.getAsBoolean();
		}
		ModOption o = opt(modId, key);
		return o != null && o.defBool;
	}

	public static double num(String modId, String key) {
		var v = raw(modId).get(key);
		if (v != null) {
			return v.getAsDouble();
		}
		ModOption o = opt(modId, key);
		return o == null ? 0 : o.defNum;
	}

	public static int color(String modId, String key) {
		var v = raw(modId).get(key);
		if (v != null) {
			return (int) v.getAsLong();
		}
		ModOption o = opt(modId, key);
		return o == null || o.swatches == null || o.swatches.length == 0 ? 0xFFFFFFFF : o.swatches[0];
	}

	public static int keyCode(String modId, String key) {
		var v = raw(modId).get(key);
		if (v != null) {
			return v.getAsInt();
		}
		ModOption o = opt(modId, key);
		return o == null ? -1 : o.defKey;
	}

	public static String mode(String modId, String key) {
		var v = raw(modId).get(key);
		ModOption o = opt(modId, key);
		if (v != null) {
			return v.getAsString();
		}
		return o == null || o.modes == null || o.modes.length == 0 ? "" : o.modes[0];
	}

	public static void set(String modId, String key, boolean v) {
		raw(modId).put(key, new JsonPrimitive(v));
		ModsConfig.save();
	}

	public static void set(String modId, String key, double v) {
		raw(modId).put(key, new JsonPrimitive(v));
		ModsConfig.save();
	}

	public static void set(String modId, String key, int v) {
		raw(modId).put(key, new JsonPrimitive(v));
		ModsConfig.save();
	}

	public static void set(String modId, String key, String v) {
		raw(modId).put(key, new JsonPrimitive(v));
		ModsConfig.save();
	}

	public static boolean metaBool(String key, boolean def) {
		ModsConfig.ensureLoaded();
		var v = ModsConfig.META.get(key);
		return v == null ? def : v.getAsBoolean();
	}

	public static void setMetaBool(String key, boolean v) {
		ModsConfig.ensureLoaded();
		ModsConfig.META.put(key, new JsonPrimitive(v));
		ModsConfig.save();
	}

	private static Map<String, com.google.gson.JsonElement> raw(String modId) {
		ModsConfig.ensureLoaded();
		migrateOnce();
		return ModsConfig.VALUES.computeIfAbsent(modId, k -> new java.util.HashMap<>());
	}

	private static ModOption opt(String modId, String key) {
		Mod m = byId(modId);
		if (m == null) {
			return null;
		}
		for (ModOption o : m.options()) {
			if (o.key.equals(key)) {
				return o;
			}
		}
		return null;
	}

	// One-time seed from the legacy originclient.json flags so nobody loses
	// their zoom/freelook state; the legacy file stays on disk untouched
	// (the launcher's originUiEnabled bridge field lives there).
	private static boolean migrated = false;

	private static void migrateOnce() {
		if (migrated) {
			return;
		}
		migrated = true;
		if (!ModsConfig.VALUES.isEmpty()) {
			return; // already have real state
		}
		var legacy = OriginClientMod.FEATURES;
		seed("zoom", legacy.zoomEnabled);
		seed("freelook", legacy.freelookEnabled);
		seed("coords", legacy.hudInfoEnabled);
		seed("fps", legacy.hudInfoEnabled);
		seed("togglesprint", legacy.toggleSprintEnabled);
		seed("togglesneak", legacy.toggleSneakEnabled);
		seed("fullbright", legacy.fullbrightEnabled);
		ModsConfig.VALUES.computeIfAbsent("zoom", k -> new java.util.HashMap<>())
				.put("fov", new JsonPrimitive(legacy.zoomFov));
	}

	private static void seed(String id, boolean on) {
		ModsConfig.VALUES.computeIfAbsent(id, k -> new java.util.HashMap<>())
				.put("enabled", new JsonPrimitive(on));
	}
}
