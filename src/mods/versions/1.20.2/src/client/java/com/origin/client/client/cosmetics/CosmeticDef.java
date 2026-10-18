package com.origin.client.client.cosmetics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One item's art, parsed from assets/originclient/cosmetics/&lt;id&gt;/item.json
 * (cosmetics/FORMAT.md is the contract). Plain data, no Minecraft types, so it
 * parses on any thread; {@link ModelCosmetic} turns the model into geometry and
 * {@link CapeTextures} / {@link CosmeticEffects} / {@link ThemeColors} read the
 * other parts.
 *
 * Parsing is fail-soft down to the field: a bad number becomes its default, a
 * bad cube is dropped, and only an item with nothing usable at all fails
 * ({@link #parse} returns null; {@link CosmeticCatalog} logs it once and the
 * item is simply absent).
 */
public final class CosmeticDef {
	public final Cosmetics.Item meta;
	/** Null for capes and themes. */
	public final Model model;
	/** Capes only (null otherwise). */
	public final CapeSpec cape;
	/** Never null; empty = no animation. */
	public final Anim anim;
	public final List<Fx> fx;
	/** Themes only (null otherwise). */
	public final ThemeColors theme;

	private CosmeticDef(Cosmetics.Item meta, Model model, CapeSpec cape, Anim anim, List<Fx> fx, ThemeColors theme) {
		this.meta = meta;
		this.model = model;
		this.cape = cape;
		this.anim = anim;
		this.fx = fx;
		this.theme = theme;
	}

	// ------------------------------------------------------------- records

	/** `texSize` in model px; the PNG is texSize * scale. */
	public record Model(int texW, int texH, int scale, List<Bone> bones) {
	}

	/** `parent` is a bone name or null; pivot/rot are item-relative model px / degrees. */
	public record Bone(String name, String parent, float[] pivot, float[] rot, List<Cube> cubes) {
	}

	/**
	 * `uv` is the box-UV origin (null when only `faces` is given); `faces` maps
	 * a face name (north south east west up down) to [u0, v0, u1, v1] in model px.
	 */
	public record Cube(float[] from, float[] to, float[] uv, float inflate, boolean mirror, boolean emissive,
			boolean glow, float[] rotOrigin, float[] rotAngle, Map<String, float[]> faces) {
	}

	public record CapeSpec(int scale, int frames, float fps) {
	}

	/** One `anim.motion` entry: adds sin(2 pi (t / period + phase)) * A to a bone channel. */
	public record Motion(String bone, boolean pos, int axis, float amp, float period, float phase, float walkAmp,
			float airAmp) {
	}

	public record Anim(int frames, float fps, List<Motion> motion) {
		static final Anim NONE = new Anim(1, 10f, List.of());
	}

	/** One `fx` entry (model px, seconds). `bone` is "head" or "body" (see CosmeticEffects). */
	public record Fx(String bone, float[] at, float[] spread, int color, int color2, float perSecond, float lifeMin,
			float lifeMax, float size, float rise, float[] drift, boolean glow) {
	}

	// -------------------------------------------------------------- parsing

	private static final int MAX_BONES = 64, MAX_CUBES = 256, MAX_FX = 8, MAX_MOTION = 32;

	/**
	 * Parses an item.json. `catalogMeta` (from catalog.json) wins for the meta
	 * when present; otherwise the meta comes from the item.json itself, either
	 * nested as {"meta": {...}} or flat at the top level (FORMAT.md shows both
	 * the meta block and the art blocks but not how they nest in the file).
	 * Returns null if nothing usable.
	 */
	static CosmeticDef parse(JsonObject root, Cosmetics.Item catalogMeta) {
		Cosmetics.Item meta = catalogMeta;
		if (meta == null) {
			JsonObject m = root.get("meta") instanceof JsonObject mo ? mo : root;
			meta = Cosmetics.Item.fromJson(m);
		}
		if (meta == null) {
			return null;
		}
		Model model = null;
		CapeSpec cape = null;
		ThemeColors theme = null;
		if (root.get("model") instanceof JsonObject mo) {
			model = parseModel(mo);
		}
		if (root.get("cape") instanceof JsonObject co) {
			cape = new CapeSpec(clampInt((int) Cosmetics.num(co, "scale", 8), 1, 16),
					clampInt((int) Cosmetics.num(co, "frames", 1), 1, 256),
					(float) Math.max(1.0, Cosmetics.num(co, "fps", 10)));
		} else if (meta.slot() == Cosmetics.Slot.CAPE) {
			cape = new CapeSpec(8, 1, 10f);
		}
		if (root.get("theme") instanceof JsonObject to) {
			theme = ThemeColors.parse(to);
		}
		Anim anim = root.get("anim") instanceof JsonObject ao ? parseAnim(ao) : Anim.NONE;
		List<Fx> fx = new ArrayList<>();
		if (root.get("fx") instanceof JsonArray fa) {
			for (JsonElement e : fa) {
				if (fx.size() < MAX_FX && e instanceof JsonObject fo) {
					Fx f = parseFx(fo);
					if (f != null) {
						fx.add(f);
					}
				}
			}
		}
		// A slot that needs a model but has none can't be drawn: that item is absent.
		boolean needsModel = meta.slot() == Cosmetics.Slot.HEAD || meta.slot() == Cosmetics.Slot.FACE
				|| meta.slot() == Cosmetics.Slot.BACK;
		if (needsModel && model == null) {
			return null;
		}
		if (meta.slot() == Cosmetics.Slot.THEME && theme == null) {
			return null;
		}
		return new CosmeticDef(meta, model, cape, anim, List.copyOf(fx), theme);
	}

	private static Model parseModel(JsonObject mo) {
		float[] tex = floats(mo, "texSize", 2, 0f);
		int texW = Math.round(tex[0]), texH = Math.round(tex[1]);
		if (texW <= 0 || texH <= 0 || texW > 4096 || texH > 4096) {
			return null;
		}
		int scale = clampInt((int) Cosmetics.num(mo, "scale", 4), 1, 16);
		List<Bone> bones = new ArrayList<>();
		int cubes = 0;
		if (mo.get("bones") instanceof JsonArray ba) {
			for (JsonElement be : ba) {
				if (bones.size() >= MAX_BONES || !(be instanceof JsonObject bo)) {
					continue;
				}
				List<Cube> list = new ArrayList<>();
				if (bo.get("cubes") instanceof JsonArray ca) {
					for (JsonElement ce : ca) {
						if (cubes < MAX_CUBES && ce instanceof JsonObject co) {
							Cube c = parseCube(co);
							if (c != null) {
								list.add(c);
								cubes++;
							}
						}
					}
				}
				String name = Cosmetics.str(bo, "name", "bone" + bones.size());
				String parent = Cosmetics.str(bo, "parent", null);
				bones.add(new Bone(name, parent, floats(bo, "pivot", 3, 0f), floats(bo, "rotation", 3, 0f), list));
			}
		}
		return bones.isEmpty() ? null : new Model(texW, texH, scale, List.copyOf(bones));
	}

	private static Cube parseCube(JsonObject co) {
		if (!(co.get("from") instanceof JsonArray) || !(co.get("to") instanceof JsonArray)) {
			return null;
		}
		float[] from = floats(co, "from", 3, 0f), to = floats(co, "to", 3, 0f);
		float[] uv = co.get("uv") instanceof JsonArray ? floats(co, "uv", 2, 0f) : null;
		Map<String, float[]> faces = new LinkedHashMap<>();
		if (co.get("faces") instanceof JsonObject fo) {
			for (String face : new String[] {"north", "south", "east", "west", "up", "down"}) {
				if (fo.get(face) instanceof JsonArray) {
					faces.put(face, floats(fo, face, 4, 0f));
				}
			}
		}
		if (uv == null && faces.isEmpty()) {
			return null;
		}
		float[] rotOrigin = new float[3], rotAngle = new float[3];
		if (co.get("rotation") instanceof JsonObject ro) {
			rotOrigin = floats(ro, "origin", 3, 0f);
			rotAngle = floats(ro, "angle", 3, 0f);
		}
		return new Cube(from, to, uv, (float) Cosmetics.num(co, "inflate", 0), Cosmetics.bool(co, "mirror", false),
				Cosmetics.bool(co, "emissive", false), Cosmetics.bool(co, "glow", false), rotOrigin, rotAngle,
				faces);
	}

	private static Anim parseAnim(JsonObject ao) {
		List<Motion> motion = new ArrayList<>();
		if (ao.get("motion") instanceof JsonArray ma) {
			for (JsonElement e : ma) {
				if (motion.size() >= MAX_MOTION || !(e instanceof JsonObject mo)) {
					continue;
				}
				String bone = Cosmetics.str(mo, "bone", null);
				String axis = Cosmetics.str(mo, "axis", "x");
				if (bone == null) {
					continue;
				}
				motion.add(new Motion(bone, "pos".equals(Cosmetics.str(mo, "channel", "rot")),
						axis.equals("y") ? 1 : axis.equals("z") ? 2 : 0,
						(float) Cosmetics.num(mo, "amp", 0),
						(float) Math.max(0.05, Cosmetics.num(mo, "period", 1)),
						(float) Cosmetics.num(mo, "phase", 0),
						(float) Cosmetics.num(mo, "walkAmp", 0), (float) Cosmetics.num(mo, "airAmp", 0)));
			}
		}
		return new Anim(clampInt((int) Cosmetics.num(ao, "frames", 1), 1, 256),
				(float) Math.max(1.0, Cosmetics.num(ao, "fps", 10)), List.copyOf(motion));
	}

	private static Fx parseFx(JsonObject fo) {
		float perSecond = (float) Cosmetics.num(fo, "perSecond", 0);
		if (perSecond <= 0f) {
			return null;
		}
		float[] life = floats(fo, "life", 2, 1.2f);
		if (!(fo.get("life") instanceof JsonArray)) {
			life = new float[] {1.2f, 2.2f};
		}
		int c1 = ThemeColors.hex(Cosmetics.str(fo, "color", "#ffffff"), 0xFFFFFFFF);
		int c2 = ThemeColors.hex(Cosmetics.str(fo, "color2", null), c1);
		return new Fx(Cosmetics.str(fo, "bone", "body"), floats(fo, "at", 3, 0f), floats(fo, "spread", 3, 0f), c1, c2,
				Math.min(perSecond, 60f), Math.max(0.1f, Math.min(life[0], life[1])), Math.max(0.1f, Math.max(life[0], life[1])),
				(float) Math.max(0.1, Cosmetics.num(fo, "size", 0.8)), (float) Cosmetics.num(fo, "rise", 0),
				floats(fo, "drift", 3, 0f), Cosmetics.bool(fo, "glow", false));
	}

	// ------------------------------------------------------------- helpers

	/** An n-number array under `key`; missing / short / non-numeric entries become `def`. */
	static float[] floats(JsonObject o, String key, int n, float def) {
		float[] out = new float[n];
		java.util.Arrays.fill(out, def);
		try {
			if (o.get(key) instanceof JsonArray a) {
				for (int i = 0; i < n && i < a.size(); i++) {
					JsonElement e = a.get(i);
					if (e != null && e.isJsonPrimitive()) {
						out[i] = e.getAsFloat();
					}
				}
			}
		} catch (Exception ignored) {
			// keep what was read
		}
		return out;
	}

	private static int clampInt(int v, int lo, int hi) {
		return Math.max(lo, Math.min(hi, v));
	}
}
