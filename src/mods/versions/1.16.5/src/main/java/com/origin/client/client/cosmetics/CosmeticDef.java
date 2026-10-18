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
	public static final class Model {
		private final int texW;
		private final int texH;
		private final int scale;
		private final List<Bone> bones;

		public Model(int texW, int texH, int scale, List<Bone> bones) {
			this.texW = texW;
			this.texH = texH;
			this.scale = scale;
			this.bones = bones;
		}

		public int texW() {
			return this.texW;
		}

		public int texH() {
			return this.texH;
		}

		public int scale() {
			return this.scale;
		}

		public List<Bone> bones() {
			return this.bones;
		}
	}

	/** `parent` is a bone name or null; pivot/rot are item-relative model px / degrees. */
	public static final class Bone {
		private final String name;
		private final String parent;
		private final float[] pivot;
		private final float[] rot;
		private final List<Cube> cubes;

		public Bone(String name, String parent, float[] pivot, float[] rot, List<Cube> cubes) {
			this.name = name;
			this.parent = parent;
			this.pivot = pivot;
			this.rot = rot;
			this.cubes = cubes;
		}

		public String name() {
			return this.name;
		}

		public String parent() {
			return this.parent;
		}

		public float[] pivot() {
			return this.pivot;
		}

		public float[] rot() {
			return this.rot;
		}

		public List<Cube> cubes() {
			return this.cubes;
		}
	}

	/**
	 * `uv` is the box-UV origin (null when only `faces` is given); `faces` maps
	 * a face name (north south east west up down) to [u0, v0, u1, v1] in model px.
	 */
	public static final class Cube {
		private final float[] from;
		private final float[] to;
		private final float[] uv;
		private final float inflate;
		private final boolean mirror;
		private final boolean emissive;
		private final boolean glow;
		private final float[] rotOrigin;
		private final float[] rotAngle;
		private final Map<String, float[]> faces;

		public Cube(float[] from, float[] to, float[] uv, float inflate, boolean mirror, boolean emissive, boolean glow, float[] rotOrigin, float[] rotAngle, Map<String, float[]> faces) {
			this.from = from;
			this.to = to;
			this.uv = uv;
			this.inflate = inflate;
			this.mirror = mirror;
			this.emissive = emissive;
			this.glow = glow;
			this.rotOrigin = rotOrigin;
			this.rotAngle = rotAngle;
			this.faces = faces;
		}

		public float[] from() {
			return this.from;
		}

		public float[] to() {
			return this.to;
		}

		public float[] uv() {
			return this.uv;
		}

		public float inflate() {
			return this.inflate;
		}

		public boolean mirror() {
			return this.mirror;
		}

		public boolean emissive() {
			return this.emissive;
		}

		public boolean glow() {
			return this.glow;
		}

		public float[] rotOrigin() {
			return this.rotOrigin;
		}

		public float[] rotAngle() {
			return this.rotAngle;
		}

		public Map<String, float[]> faces() {
			return this.faces;
		}
	}

	public static final class CapeSpec {
		private final int scale;
		private final int frames;
		private final float fps;

		public CapeSpec(int scale, int frames, float fps) {
			this.scale = scale;
			this.frames = frames;
			this.fps = fps;
		}

		public int scale() {
			return this.scale;
		}

		public int frames() {
			return this.frames;
		}

		public float fps() {
			return this.fps;
		}
	}

	/** One `anim.motion` entry: adds sin(2 pi (t / period + phase)) * A to a bone channel. */
	public static final class Motion {
		private final String bone;
		private final boolean pos;
		private final int axis;
		private final float amp;
		private final float period;
		private final float phase;
		private final float walkAmp;
		private final float airAmp;

		public Motion(String bone, boolean pos, int axis, float amp, float period, float phase, float walkAmp, float airAmp) {
			this.bone = bone;
			this.pos = pos;
			this.axis = axis;
			this.amp = amp;
			this.period = period;
			this.phase = phase;
			this.walkAmp = walkAmp;
			this.airAmp = airAmp;
		}

		public String bone() {
			return this.bone;
		}

		public boolean pos() {
			return this.pos;
		}

		public int axis() {
			return this.axis;
		}

		public float amp() {
			return this.amp;
		}

		public float period() {
			return this.period;
		}

		public float phase() {
			return this.phase;
		}

		public float walkAmp() {
			return this.walkAmp;
		}

		public float airAmp() {
			return this.airAmp;
		}
	}

	public static final class Anim {
		private final int frames;
		private final float fps;
		private final List<Motion> motion;

		public Anim(int frames, float fps, List<Motion> motion) {
			this.frames = frames;
			this.fps = fps;
			this.motion = motion;
		}

		public int frames() {
			return this.frames;
		}

		public float fps() {
			return this.fps;
		}

		public List<Motion> motion() {
			return this.motion;
		}

		static final Anim NONE = new Anim(1, 10f, java.util.Collections.<Motion>emptyList());
	}

	/** One `fx` entry (model px, seconds). `bone` is "head" or "body" (see CosmeticEffects). */
	public static final class Fx {
		private final String bone;
		private final float[] at;
		private final float[] spread;
		private final int color;
		private final int color2;
		private final float perSecond;
		private final float lifeMin;
		private final float lifeMax;
		private final float size;
		private final float rise;
		private final float[] drift;
		private final boolean glow;

		public Fx(String bone, float[] at, float[] spread, int color, int color2, float perSecond, float lifeMin, float lifeMax, float size, float rise, float[] drift, boolean glow) {
			this.bone = bone;
			this.at = at;
			this.spread = spread;
			this.color = color;
			this.color2 = color2;
			this.perSecond = perSecond;
			this.lifeMin = lifeMin;
			this.lifeMax = lifeMax;
			this.size = size;
			this.rise = rise;
			this.drift = drift;
			this.glow = glow;
		}

		public String bone() {
			return this.bone;
		}

		public float[] at() {
			return this.at;
		}

		public float[] spread() {
			return this.spread;
		}

		public int color() {
			return this.color;
		}

		public int color2() {
			return this.color2;
		}

		public float perSecond() {
			return this.perSecond;
		}

		public float lifeMin() {
			return this.lifeMin;
		}

		public float lifeMax() {
			return this.lifeMax;
		}

		public float size() {
			return this.size;
		}

		public float rise() {
			return this.rise;
		}

		public float[] drift() {
			return this.drift;
		}

		public boolean glow() {
			return this.glow;
		}
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
			JsonObject m = obj(root, "meta");
			if (m == null) {
				m = root;
			}
			meta = Cosmetics.Item.fromJson(m);
		}
		if (meta == null) {
			return null;
		}
		Model model = null;
		CapeSpec cape = null;
		ThemeColors theme = null;
		JsonObject mo = obj(root, "model");
		if (mo != null) {
			model = parseModel(mo);
		}
		JsonObject co = obj(root, "cape");
		if (co != null) {
			cape = new CapeSpec(clampInt((int) Cosmetics.num(co, "scale", 8), 1, 16),
					clampInt((int) Cosmetics.num(co, "frames", 1), 1, 256),
					(float) Math.max(1.0, Cosmetics.num(co, "fps", 10)));
		} else if (meta.slot() == Cosmetics.Slot.CAPE) {
			cape = new CapeSpec(8, 1, 10f);
		}
		JsonObject to = obj(root, "theme");
		if (to != null) {
			theme = ThemeColors.parse(to);
		}
		JsonObject ao = obj(root, "anim");
		Anim anim = ao != null ? parseAnim(ao) : Anim.NONE;
		List<Fx> fx = new ArrayList<>();
		JsonArray fa = arr(root, "fx");
		if (fa != null) {
			for (JsonElement e : fa) {
				if (fx.size() < MAX_FX && e instanceof JsonObject) {
					Fx f = parseFx((JsonObject) e);
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
		return new CosmeticDef(meta, model, cape, anim, java.util.Collections.unmodifiableList(fx), theme);
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
		JsonArray ba = arr(mo, "bones");
		if (ba != null) {
			for (JsonElement be : ba) {
				if (bones.size() >= MAX_BONES || !(be instanceof JsonObject)) {
					continue;
				}
				JsonObject bo = (JsonObject) be;
				List<Cube> list = new ArrayList<>();
				JsonArray ca = arr(bo, "cubes");
				if (ca != null) {
					for (JsonElement ce : ca) {
						if (cubes < MAX_CUBES && ce instanceof JsonObject) {
							Cube c = parseCube((JsonObject) ce);
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
		return bones.isEmpty() ? null : new Model(texW, texH, scale, java.util.Collections.unmodifiableList(bones));
	}

	private static Cube parseCube(JsonObject co) {
		if (!(co.get("from") instanceof JsonArray) || !(co.get("to") instanceof JsonArray)) {
			return null;
		}
		float[] from = floats(co, "from", 3, 0f), to = floats(co, "to", 3, 0f);
		float[] uv = co.get("uv") instanceof JsonArray ? floats(co, "uv", 2, 0f) : null;
		Map<String, float[]> faces = new LinkedHashMap<>();
		JsonObject fo = obj(co, "faces");
		if (fo != null) {
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
		JsonObject ro = obj(co, "rotation");
		if (ro != null) {
			rotOrigin = floats(ro, "origin", 3, 0f);
			rotAngle = floats(ro, "angle", 3, 0f);
		}
		return new Cube(from, to, uv, (float) Cosmetics.num(co, "inflate", 0), Cosmetics.bool(co, "mirror", false),
				Cosmetics.bool(co, "emissive", false), Cosmetics.bool(co, "glow", false), rotOrigin, rotAngle,
				faces);
	}

	private static Anim parseAnim(JsonObject ao) {
		List<Motion> motion = new ArrayList<>();
		JsonArray ma = arr(ao, "motion");
		if (ma != null) {
			for (JsonElement e : ma) {
				if (motion.size() >= MAX_MOTION || !(e instanceof JsonObject)) {
					continue;
				}
				JsonObject mo = (JsonObject) e;
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
				(float) Math.max(1.0, Cosmetics.num(ao, "fps", 10)), java.util.Collections.unmodifiableList(motion));
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
			JsonArray a = arr(o, key);
			if (a != null) {
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

	/** Java 8 has no instanceof patterns: the member as an object, or null. */
	static JsonObject obj(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e instanceof JsonObject ? (JsonObject) e : null;
	}

	/** The member as an array, or null. */
	static JsonArray arr(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e instanceof JsonArray ? (JsonArray) e : null;
	}

	private static int clampInt(int v, int lo, int hi) {
		return Math.max(lo, Math.min(hi, v));
	}
}
