package com.origin.client.client.gui;

import com.origin.client.client.mods.ModOption;
import mezz.jei.api.runtime.IJeiRuntime;
import mezz.jei.api.runtime.config.IJeiConfigCategory;
import mezz.jei.api.runtime.config.IJeiConfigFile;
import mezz.jei.api.runtime.config.IJeiConfigListValueSerializer;
import mezz.jei.api.runtime.config.IJeiConfigValue;
import mezz.jei.api.runtime.config.IJeiConfigValueSerializer;
import mezz.jei.fabric.plugins.fabric.FabricGuiPlugin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Bridges JEI's OWN settings into the Origin mod menu as the "jei" page's rows.
 *
 * JEI values are NOT copied into Origin's ModsConfig — they live in JEI's config
 * and stay the single source of truth, so the JEI GUI and the Origin menu always
 * agree and JEI actually honours what you change. This class mirrors them live:
 *   - {@link #options()} builds the row SCHEMA (kind / label / choices / slider
 *     bounds) from JEI's config manager, cached per runtime; the mod menu renders
 *     it like any mod's page.
 *   - the getter/setter methods read/write the LIVE value through each value's
 *     own serializer, so an edit lands in JEI immediately and persists to disk.
 *
 * EVERYTHING IS DONE IN STRING SPACE via {@code serialize}/{@code deserialize},
 * so we never touch JEI's concrete generic value types (List&lt;IngredientSortStage&gt;
 * etc.). Kind is detected structurally, verified against jei-1.21.11-fabric-
 * 27.17.0.50 with javap (not assumed):
 *   - serializer is a list serializer  -> MULTISELECT (element set from the API)
 *   - value is a Boolean               -> TOGGLE
 *   - value is an Integer              -> SLIDER, bounds found by PROBING
 *     {@code deserialize} for the range that reports no error (JEI's
 *     IntegerSerializer rejects out-of-range with a non-empty error list rather
 *     than clamping — so a value is valid iff getErrors() is empty). This needs
 *     no prose-parsing of "range [1, 7]" strings and no private-field access.
 *   - serializer has a finite valid set -> DROPDOWN (single enum)
 *   - anything else                    -> skipped (no honest control for it)
 *
 * ROBUSTNESS. Every JEI call is wrapped: any surprise degrades to "no rows",
 * never a crash (Origin's never-broken mandate). The one JEI-internal reference
 * is {@link FabricGuiPlugin#getRuntime()} — the same fail-soft internal coupling
 * the toggle mixins already carry; re-verify it (and the serializer shapes) on
 * every JEI bump, alongside the toggle mixin method names.
 *
 * JEI-BUNDLING VERSIONS ONLY. This module bundles JEI, so this file references mezz.jei.* and
 * must never be synced to a version without JEI on the classpath. It is not in
 * shared/, so sync never touches it.
 *
 * JEI 27.54 (bundled since 2026-10-25) moved its settings into MezzConfig (a nested jar) and
 * keeps this IJeiConfig* API as a deprecated-for-removal adapter
 * (mezz.jei.common.config.ConfigManagerAdapter). Two things changed for us, both javap-verified:
 *   - the adapter's serializer never implements IJeiConfigListValueSerializer, so list values
 *     are recognised through its record accessor delegate() (a MezzConfig
 *     IConfigListValueSerializer) by reflection — MezzConfig is not on our compile classpath;
 *   - MezzConfig serializes a list as a JSON array ("[\"A\",\"B\"]") and rejects anything else
 *     ("Expected a structured array"), so getMulti/setMulti translate to and from the menu's
 *     "A, B" form. When JEI removes the adapter this page degrades to "no settings".
 */
@SuppressWarnings("removal") // the JEI config API above is deprecated for removal in JEI 27.54
public final class JeiSettings {
	private JeiSettings() {
	}

	// key -> live JEI value handle. key = "<category>/<name>"; the bare name
	// collides (maxRows lives in three categories), so it is category-qualified.
	private static final Map<String, IJeiConfigValue<?>> HANDLES = new LinkedHashMap<>();
	// key -> {min, max, step} for sliders, so the deserialize probe runs once.
	private static final Map<String, double[]> BOUNDS = new HashMap<>();

	// The schema is cached and only rebuilt when JEI hands us a NEW runtime
	// (identity compare) — options() is called every frame by the menu layout.
	private static Object builtFor;
	private static List<ModOption> cached = List.of();

	// Probe window for integer bounds. Covers every real JEI int setting; the UI
	// cap keeps an unbounded-above setting (recipeGuiHeight, min 175, no max) from
	// becoming a slider to 2 billion.
	private static final int PROBE_FLOOR = -100_000;
	private static final int PROBE_CEIL = 100_000;
	private static final int UI_CAP = 4096;

	/** True when JEI's runtime is up (always so while the in-game menu is open). */
	public static boolean available() {
		try {
			return FabricGuiPlugin.getRuntime().isPresent();
		} catch (Throwable t) {
			return false;
		}
	}

	/** Live schema rows for the JEI page. Empty (→ "no settings") if JEI isn't ready. */
	public static List<ModOption> options() {
		IJeiRuntime rt;
		try {
			rt = FabricGuiPlugin.getRuntime().orElse(null);
		} catch (Throwable t) {
			rt = null;
		}
		if (rt == null) {
			builtFor = null;
			cached = List.of();
			HANDLES.clear();
			BOUNDS.clear();
			return cached;
		}
		if (rt == builtFor) {
			return cached; // identity: same runtime, reuse the built schema
		}
		rebuild(rt);
		builtFor = rt;
		return cached;
	}

	private static void rebuild(IJeiRuntime rt) {
		HANDLES.clear();
		BOUNDS.clear();
		List<ModOption> out = new ArrayList<>();
		try {
			for (IJeiConfigFile file : rt.getConfigManager().getConfigFiles()) {
				if (fileName(file).contains("debug")) {
					continue; // developer toggles, not player-facing
				}
				for (IJeiConfigCategory cat : file.getCategories()) {
					List<ModOption> rows = new ArrayList<>();
					for (IJeiConfigValue<?> v : cat.getConfigValues()) {
						ModOption row = build(cat.getName(), v);
						if (row != null) {
							rows.add(row);
						}
					}
					// A header only earns its place if the category produced rows.
					if (!rows.isEmpty()) {
						out.add(ModOption.header(prettify(cat.getName())));
						out.addAll(rows);
					}
				}
			}
		} catch (Throwable t) {
			HANDLES.clear();
			cached = List.of();
			return;
		}
		cached = out;
	}

	private static ModOption build(String category, IJeiConfigValue<?> v) {
		try {
			String key = category + "/" + v.getName();
			IJeiConfigValueSerializer<?> ser = v.getSerializer();
			String label = v.getLocalizedName().getString();

			ModOption row;
			List<String> mezzList = ser instanceof IJeiConfigListValueSerializer<?> ? null : mezzListChoices(ser);
			if (ser instanceof IJeiConfigListValueSerializer<?> ls) {
				List<String> choices = elementChoices(ls);
				if (choices.isEmpty()) {
					return null; // a list with no enumerable element set — no control
				}
				row = ModOption.multiselect(key, label, choices.toArray(new String[0]));
			} else if (mezzList != null) {
				if (mezzList.isEmpty()) {
					return null;
				}
				row = ModOption.multiselect(key, label, mezzList.toArray(new String[0]));
			} else {
				Object cur = v.getValue();
				if (cur instanceof Boolean b) {
					row = ModOption.toggle(key, label, b);
				} else if (cur instanceof Integer i) {
					double[] b = bounds(key, ser, i);
					row = ModOption.slider(key, label, b[0], b[1], b[2], i, "%.0f");
				} else {
					List<String> choices = validTokens(ser);
					if (choices.isEmpty()) {
						return null; // free string / opaque type — nothing honest to draw
					}
					row = ModOption.dropdown(key, label, choices.toArray(new String[0]));
				}
			}

			HANDLES.put(key, v);
			String tip = v.getLocalizedDescription().getString();
			if (tip != null && !tip.isBlank() && !tip.equals(label)) {
				row.tip(tip);
			}
			return row;
		} catch (Throwable t) {
			return null; // one bad value can't take out the whole page
		}
	}

	// ---- live value access (string space) ----

	public static boolean getBool(String key) {
		IJeiConfigValue<?> v = HANDLES.get(key);
		if (v == null) {
			return false;
		}
		try {
			return "true".equalsIgnoreCase(readTyped(v));
		} catch (Throwable t) {
			return false;
		}
	}

	public static void setBool(String key, boolean b) {
		write(key, b ? "true" : "false");
	}

	public static double getNum(String key) {
		IJeiConfigValue<?> v = HANDLES.get(key);
		if (v == null) {
			return 0;
		}
		try {
			return Double.parseDouble(readTyped(v).trim());
		} catch (Throwable t) {
			return 0;
		}
	}

	public static void setNum(String key, double d) {
		write(key, Integer.toString((int) Math.round(d)));
	}

	/** Current single-choice (enum) token, or "" if unset/unknown. */
	public static String getMode(String key) {
		IJeiConfigValue<?> v = HANDLES.get(key);
		if (v == null) {
			return "";
		}
		try {
			return readTyped(v);
		} catch (Throwable t) {
			return "";
		}
	}

	public static void setMode(String key, String token) {
		write(key, token);
	}

	/** Current ordered multi-select as the serialized comma string ("A, B, C"). */
	public static String getMulti(String key) {
		String s = getMode(key);
		if (!s.trim().startsWith("[")) {
			return s; // JEI <= 27.17 list format is already "A, B, C"
		}
		try {
			List<String> out = new ArrayList<>();
			for (JsonElement e : JsonParser.parseString(s).getAsJsonArray()) {
				out.add(e.isJsonPrimitive() ? e.getAsString() : e.toString());
			}
			return String.join(", ", out);
		} catch (Throwable t) {
			return "";
		}
	}

	public static void setMulti(String key, String csv) {
		if (!getMode(key).trim().startsWith("[")) {
			write(key, csv);
			return;
		}
		// MezzConfig list (JEI 27.54): it only accepts a JSON array.
		JsonArray arr = new JsonArray();
		for (String p : csv.split(",")) {
			String t = p.trim();
			if (!t.isEmpty()) {
				arr.add(t);
			}
		}
		write(key, arr.toString());
	}

	private static void write(String key, String s) {
		IJeiConfigValue<?> v = HANDLES.get(key);
		if (v == null) {
			return;
		}
		try {
			writeTyped(v, s);
		} catch (Throwable t) {
			// swallow — a rejected write must never crash the menu
		}
	}

	// ---- typed helpers (capture the value's wildcard once so serialize/set line up) ----

	private static <T> String readTyped(IJeiConfigValue<T> v) {
		return v.getSerializer().serialize(v.getValue());
	}

	private static <T> void writeTyped(IJeiConfigValue<T> v, String s) {
		IJeiConfigValueSerializer.IDeserializeResult<T> r = v.getSerializer().deserialize(s);
		// JEI reports an out-of-range/invalid parse as a non-empty error list while
		// still handing back a (clamped) value — so only commit when clean.
		if (!r.getErrors().isEmpty()) {
			return;
		}
		r.getResult().ifPresent(v::set);
	}

	private static <T> List<String> validTokens(IJeiConfigValueSerializer<T> ser) {
		return ser.getAllValidValues()
				.map(vals -> {
					List<String> out = new ArrayList<>(vals.size());
					for (T v : vals) {
						out.add(ser.serialize(v));
					}
					return out;
				})
				.orElseGet(List::of);
	}

	private static <E> List<String> elementChoices(IJeiConfigListValueSerializer<E> ls) {
		return validTokens(ls.getListValueSerializer());
	}

	/**
	 * JEI 27.54: the element choices of a MezzConfig list value behind the JEI adapter, or null
	 * when {@code ser} is not one. Reflection only (see the class note): the adapter record's
	 * delegate() is a net.mezzdev.config.api.value.serializer.IConfigListValueSerializer whose
	 * getElementSerializer().getAllValidValues() is the element set; each element is rendered
	 * with the element serializer's serialize(), the same token the JSON array holds.
	 */
	private static List<String> mezzListChoices(Object ser) {
		try {
			Method delegate = ser.getClass().getDeclaredMethod("delegate");
			delegate.setAccessible(true); // the adapter record is package-private
			Object del = delegate.invoke(ser);
			ClassLoader cl = del.getClass().getClassLoader();
			Class<?> listSer = Class.forName("net.mezzdev.config.api.value.serializer.IConfigListValueSerializer", false, cl);
			if (!listSer.isInstance(del)) {
				return null;
			}
			Class<?> valSer = Class.forName("net.mezzdev.config.api.value.serializer.IConfigValueSerializer", false, cl);
			Object elem = listSer.getMethod("getElementSerializer").invoke(del);
			Object valid = valSer.getMethod("getAllValidValues").invoke(elem);
			if (!(valid instanceof java.util.Optional<?> opt) || opt.isEmpty()
					|| !(opt.get() instanceof java.util.Collection<?> vals)) {
				return List.of();
			}
			Method serialize = valSer.getMethod("serialize", Object.class);
			List<String> out = new ArrayList<>(vals.size());
			for (Object v : vals) {
				out.add(String.valueOf(serialize.invoke(elem, v)));
			}
			return out;
		} catch (NoSuchMethodException | ClassNotFoundException e) {
			return null; // not the 27.54 adapter: ordinary single value
		} catch (Throwable t) {
			return List.of(); // a list we could not read: no control
		}
	}

	// ---- integer slider bounds by probing (no prose, no private fields) ----

	private static double[] bounds(String key, IJeiConfigValueSerializer<?> ser, int pivot) {
		double[] hit = BOUNDS.get(key);
		if (hit != null) {
			return hit;
		}
		int min = probeMin(ser, pivot);
		int max = probeMax(ser, pivot);
		if (max > UI_CAP) {
			max = UI_CAP; // unbounded-above → sane UI ceiling
		}
		if (min < -UI_CAP) {
			min = -UI_CAP;
		}
		if (max <= min) {
			max = min + 1;
		}
		int range = max - min;
		// keep the handle from pixel-hunting on wide ranges; whole steps for ints
		double step = range <= 100 ? 1 : Math.max(1, Math.round(range / 200.0));
		double[] b = {min, max, step};
		BOUNDS.put(key, b);
		return b;
	}

	// isValid(x) is a contiguous [min,max] for JEI's IntegerSerializer, so the
	// smallest/largest x with a clean deserialize IS the bound. pivot (the current
	// value) is always valid, giving each search a known-good end.
	private static int probeMin(IJeiConfigValueSerializer<?> ser, int pivot) {
		if (validInt(ser, PROBE_FLOOR)) {
			return PROBE_FLOOR;
		}
		int lo = PROBE_FLOOR, hi = pivot; // lo invalid, hi valid
		while (lo + 1 < hi) {
			int mid = lo + (hi - lo) / 2;
			if (validInt(ser, mid)) {
				hi = mid;
			} else {
				lo = mid;
			}
		}
		return hi;
	}

	private static int probeMax(IJeiConfigValueSerializer<?> ser, int pivot) {
		if (validInt(ser, PROBE_CEIL)) {
			return PROBE_CEIL;
		}
		int lo = pivot, hi = PROBE_CEIL; // lo valid, hi invalid
		while (lo + 1 < hi) {
			int mid = lo + (hi - lo) / 2;
			if (validInt(ser, mid)) {
				lo = mid;
			} else {
				hi = mid;
			}
		}
		return lo;
	}

	private static boolean validInt(IJeiConfigValueSerializer<?> ser, int x) {
		try {
			return ser.deserialize(Integer.toString(x)).getErrors().isEmpty();
		} catch (Throwable t) {
			return false;
		}
	}

	// ---- text ----

	private static String fileName(IJeiConfigFile file) {
		try {
			return file.getPath().getFileName().toString().toLowerCase(Locale.ROOT);
		} catch (Throwable t) {
			return "";
		}
	}

	/** "lookupHistory" / "ingredient_list" → "Lookup History" / "Ingredient List". */
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
			// camelCase boundary: lower/digit followed by an upper
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
