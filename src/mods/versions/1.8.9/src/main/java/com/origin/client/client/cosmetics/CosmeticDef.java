package com.origin.client.client.cosmetics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
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
 *
 * 1.8.9: Java 8 has no records or pattern-matching instanceof, so the nested
 * types are final value classes with the record accessor names (callers read
 * the same {@code bone.cubes()} as on 1.21.1) and every instanceof is test-then-cast.
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

	// ------------------------------------------------------------- value types

	/** `texSize` in model px; the PNG is texSize * scale. */
	public static final class Model {
		private final int texW, texH, scale;
		private final List<Bone> bones;

		Model(int texW, int texH, int scale, List<Bone> bones) {
			this.texW = texW;
			this.texH = texH;
			this.scale = scale;
			this.bones = bones;
		}

		public int texW() {
			return texW;
		}

		public int texH() {
			return texH;
		}

		public int scale() {
			return scale;
		}

		public List<Bone> bones() {
			return bones;
		}
	}

	/** `parent` is a bone name or null; pivot/rot are item-relative model px / degrees. */
	public static final class Bone {
		private final String name, parent;
		private final float[] pivot, rot;
		private final List<Cube> cubes;

		Bone(String name, String parent, float[] pivot, float[] rot, List<Cube> cubes) {
			this.name = name;
			this.parent = parent;
			this.pivot = pivot;
			this.rot = rot;
			this.cubes = cubes;
		}

		public String name() {
			return name;
		}

		public String parent() {
			return parent;
		}

		public float[] pivot() {
			return pivot;
		}

		public float[] rot() {
			return rot;
		}

		public List<Cube> cubes() {
			return cubes;
		}
	}

	/**
	 * `uv` is the box-UV origin (null when only `faces` is given); `faces` maps
	 * a face name (north south east west up down) to [u0, v0, u1, v1] in model px.
	 */
	public static final class Cube {
		private final float[] from, to, uv, rotOrigin, rotAngle;
		private final float inflate;
		private final boolean mirror, emissive, glow;
		private final Map<String, float[]> faces;

		Cube(float[] from, float[] to, float[] uv, float inflate, boolean mirror, boolean emissive, boolean glow,
				float[] rotOrigin, float[] rotAngle, Map<String, float[]> faces) {
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
			return from;
		}

		public float[] to() {
			return to;
		}

		public float[] uv() {
			return uv;
		}

		public float inflate() {
			return inflate;
		}

		public boolean mirror() {
			return mirror;
		}

		public boolean emissive() {
			return emissive;
		}

		public boolean glow() {
			return glow;
		}

		public float[] rotOrigin() {
			return rotOrigin;
		}

		public float[] rotAngle() {
			return rotAngle;
		}

		public Map<String, float[]> faces() {
			return faces;
		}
	}

	public static final class CapeSpec {
		private final int scale, frames;
		private final float fps;

		CapeSpec(int scale, int frames, float fps) {
			this.scale = scale;
			this.frames = frames;
			this.fps = fps;
		}

		public int scale() {
			return scale;
		}

		public int frames() {
			return frames;
		}

		public float fps() {
			return fps;
		}
	}

	/**
	 * One `anim.motion` entry: adds sin(2 pi (t / period + phase)) * A to a bone channel, or (`spin`, channel
	 * "spin") a continuous rotation of `speed` degrees per second about `axis`.
	 */
	public static final class Motion {
		private final String bone;
		private final boolean pos;
		private final int axis;
		private final float amp, period, phase, walkAmp, airAmp;
		private final boolean spin;
		private final float speed;

		Motion(String bone, boolean pos, int axis, float amp, float period, float phase, float walkAmp, float airAmp,
				boolean spin, float speed) {
			this.spin = spin;
			this.speed = speed;
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
			return bone;
		}

		public boolean pos() {
			return pos;
		}

		public int axis() {
			return axis;
		}

		public float amp() {
			return amp;
		}

		public float period() {
			return period;
		}

		public float phase() {
			return phase;
		}

		public float walkAmp() {
			return walkAmp;
		}

		public float airAmp() {
			return airAmp;
		}

		public boolean spin() {
			return spin;
		}

		public float speed() {
			return speed;
		}
	}

	public static final class Anim {
		static final Anim NONE = new Anim(1, 10f, Collections.<Motion>emptyList());

		private final int frames;
		private final float fps;
		private final List<Motion> motion;

		Anim(int frames, float fps, List<Motion> motion) {
			this.frames = frames;
			this.fps = fps;
			this.motion = motion;
		}

		public int frames() {
			return frames;
		}

		public float fps() {
			return fps;
		}

		public List<Motion> motion() {
			return motion;
		}
	}

	/** One `fx` entry (model px, seconds). `bone` is "head" or "body" (see CosmeticEffects). */
	public static final class Fx {
		private final String bone;
		private final float[] at, spread, drift;
		private final int color, color2;
		private final float perSecond, lifeMin, lifeMax, size, rise;
		private final boolean glow;

		Fx(String bone, float[] at, float[] spread, int color, int color2, float perSecond, float lifeMin,
				float lifeMax, float size, float rise, float[] drift, boolean glow) {
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
			return bone;
		}

		public float[] at() {
			return at;
		}

		public float[] spread() {
			return spread;
		}

		public int color() {
			return color;
		}

		public int color2() {
			return color2;
		}

		public float perSecond() {
			return perSecond;
		}

		public float lifeMin() {
			return lifeMin;
		}

		public float lifeMax() {
			return lifeMax;
		}

		public float size() {
			return size;
		}

		public float rise() {
			return rise;
		}

		public float[] drift() {
			return drift;
		}

		public boolean glow() {
			return glow;
		}
	}

	// -------------------------------------------------------------- parsing

	private static final int MAX_BONES = 64, MAX_CUBES = 256, MAX_FX = 8, MAX_MOTION = 32;

	/** The object under `key`, or null if it is absent or not an object. */
	private static JsonObject obj(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e instanceof JsonObject ? (JsonObject) e : null;
	}

	/** The array under `key`, or null if it is absent or not an array. */
	private static JsonArray arr(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e instanceof JsonArray ? (JsonArray) e : null;
	}

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
			meta = Cosmetics.Item.fromJson(m != null ? m : root);
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
		return new CosmeticDef(meta, model, cape, anim, Collections.unmodifiableList(fx), theme);
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
				bones.add(new Bone(name, parent, floats(bo, "pivot", 3, 0f), floats(bo, "rotation", 3, 0f),
						Collections.unmodifiableList(list)));
			}
		}
		return bones.isEmpty() ? null : new Model(texW, texH, scale, Collections.unmodifiableList(bones));
	}

	private static Cube parseCube(JsonObject co) {
		if (arr(co, "from") == null || arr(co, "to") == null) {
			return null;
		}
		float[] from = floats(co, "from", 3, 0f), to = floats(co, "to", 3, 0f);
		float[] uv = arr(co, "uv") != null ? floats(co, "uv", 2, 0f) : null;
		Map<String, float[]> faces = new LinkedHashMap<>();
		JsonObject fo = obj(co, "faces");
		if (fo != null) {
			for (String face : new String[] {"north", "south", "east", "west", "up", "down"}) {
				if (arr(fo, face) != null) {
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
						(float) Cosmetics.num(mo, "walkAmp", 0), (float) Cosmetics.num(mo, "airAmp", 0),
						"spin".equals(Cosmetics.str(mo, "channel", "rot")), (float) Cosmetics.num(mo, "speed", 0)));
			}
		}
		return new Anim(clampInt((int) Cosmetics.num(ao, "frames", 1), 1, 256),
				(float) Math.max(1.0, Cosmetics.num(ao, "fps", 10)), Collections.unmodifiableList(motion));
	}

	private static Fx parseFx(JsonObject fo) {
		float perSecond = (float) Cosmetics.num(fo, "perSecond", 0);
		if (perSecond <= 0f) {
			return null;
		}
		float[] life = floats(fo, "life", 2, 1.2f);
		if (arr(fo, "life") == null) {
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

	private static int clampInt(int v, int lo, int hi) {
		return Math.max(lo, Math.min(hi, v));
	}
}
