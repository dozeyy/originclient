package com.origin.client.client.cosmetics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * FORMAT.md model -> flat quad arrays. Pure maths, no Minecraft types, so it
 * is cheap to test and cannot touch a GL context. {@link ModelCosmetic} draws
 * the result.
 *
 * FORMAT space (kept here, converted to vanilla at draw time by one
 * `scale(1/16, -1/16, -1/16)`): model px, y up, +z = the player's front,
 * +x = the player's left, relative to the attach bone's pivot.
 *
 * Conventions where FORMAT.md is silent are taken from the art pipeline's own
 * code (website/js/uv.mjs + rig.js, tools/cosmetics/build.mjs):
 *  - face NAMES are literal axes: north = -z, south = +z, east = +x,
 *    west = -x, up = +y, down = -y; every face image is read as seen from
 *    OUTSIDE with +y up (website/js/uv.mjs faceCorners is the reference,
 *    corners below match it one for one);
 *  - box UV is the Blockbench/Bedrock layout: for a w x h x d cube at (u, v)
 *    up = [u+d, v, u+d+w, v+d], down = [u+d+w, v, u+d+2w, v+d],
 *    north = [u+d, v+d, u+d+w, v+d+h], east = [u, v+d, u+d, v+d+h],
 *    south = [u+2d+w, v+d, u+2d+2w, v+d+h], west = [u+d+w, v+d, u+2d+w, v+d+h];
 *  - rotations are right-handed in FORMAT space and applied Z first, then Y,
 *    then X (R = Rx * Ry * Rz), exactly as tools/cosmetics/build.mjs and the
 *    store's website/js/rig.js do ("XYZ" Euler order in three.js);
 *  - bone pivots / cube origins are item-absolute (Bedrock style), a bone
 *    rotates about its own pivot and inherits its parent's transform.
 */
final class ModelGeometry {
	private ModelGeometry() {
	}

	/** Floats per quad: 4 vertices x (x, y, z, u, v) + normal (3). */
	static final int QUAD = 23;
	/** Lit, emissive (full-bright), glow (additive). */
	static final int LIT = 0, EMISSIVE = 1, GLOW = 2;

	/** How far a zero-thickness plane's two faces are pushed apart (px) so they never z-fight. */
	private static final float PLANE_SEPARATION = 0.05f;

	static final class Compiled {
		final String[] names;
		final int[] parent;          // index or -1
		final float[][] pivot;
		final float[][] rot;         // degrees x, y, z
		final float[][][] quads;     // [bone][kind] -> flat quads (may be empty)
		final int[] roots;
		final int[][] children;
		final boolean hasGlow;
		final int texW, texH;

		Compiled(String[] names, int[] parent, float[][] pivot, float[][] rot, float[][][] quads, int[] roots,
				int[][] children, boolean hasGlow, int texW, int texH) {
			this.names = names;
			this.parent = parent;
			this.pivot = pivot;
			this.rot = rot;
			this.quads = quads;
			this.roots = roots;
			this.children = children;
			this.hasGlow = hasGlow;
			this.texW = texW;
			this.texH = texH;
		}

		int boneIndex(String name) {
			for (int i = 0; i < names.length; i++) {
				if (names[i].equals(name)) {
					return i;
				}
			}
			return -1;
		}
	}

	static Compiled compile(CosmeticDef.Model model) {
		List<CosmeticDef.Bone> bones = model.bones();
		int n = bones.size();
		String[] names = new String[n];
		Map<String, Integer> index = new HashMap<>();
		for (int i = 0; i < n; i++) {
			names[i] = bones.get(i).name();
			index.putIfAbsent(names[i], i);
		}
		int[] parent = new int[n];
		float[][] pivot = new float[n][];
		float[][] rot = new float[n][];
		float[][][] quads = new float[n][3][];
		boolean hasGlow = false;
		for (int i = 0; i < n; i++) {
			CosmeticDef.Bone b = bones.get(i);
			Integer p = b.parent() == null ? null : index.get(b.parent());
			parent[i] = p == null || p == i ? -1 : p;
			pivot[i] = b.pivot();
			rot[i] = b.rot();
			List<List<Float>> kinds = java.util.Arrays.<List<Float>>asList(new ArrayList<Float>(), new ArrayList<Float>(), new ArrayList<Float>());
			for (CosmeticDef.Cube c : b.cubes()) {
				addCube(c, model.texW(), model.texH(), kinds.get(c.glow() ? GLOW : c.emissive() ? EMISSIVE : LIT));
			}
			for (int k = 0; k < 3; k++) {
				List<Float> l = kinds.get(k);
				quads[i][k] = new float[l.size()];
				for (int j = 0; j < l.size(); j++) {
					quads[i][k][j] = l.get(j);
				}
			}
			hasGlow |= quads[i][GLOW].length > 0;
		}
		// A parent chain that loops back on itself would never reach a root: cut it.
		for (int i = 0; i < n; i++) {
			int hops = 0;
			for (int p = parent[i]; p >= 0; p = parent[p]) {
				if (++hops > n) {
					parent[i] = -1;
					break;
				}
			}
		}
		int[][] children = new int[n][];
		List<Integer> roots = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			List<Integer> kids = new ArrayList<>();
			for (int j = 0; j < n; j++) {
				if (parent[j] == i) {
					kids.add(j);
				}
			}
			children[i] = kids.stream().mapToInt(Integer::intValue).toArray();
			if (parent[i] < 0) {
				roots.add(i);
			}
		}
		return new Compiled(names, parent, pivot, rot, quads, roots.stream().mapToInt(Integer::intValue).toArray(),
				children, hasGlow, model.texW(), model.texH());
	}

	private static final String[] FACES = {"north", "south", "east", "west", "up", "down"};

	private static void addCube(CosmeticDef.Cube c, int texW, int texH, List<Float> out) {
		float inf = c.inflate();
		float x0 = Math.min(c.from()[0], c.to()[0]), x1 = Math.max(c.from()[0], c.to()[0]);
		float y0 = Math.min(c.from()[1], c.to()[1]), y1 = Math.max(c.from()[1], c.to()[1]);
		float z0 = Math.min(c.from()[2], c.to()[2]), z1 = Math.max(c.from()[2], c.to()[2]);
		float w = x1 - x0, h = y1 - y0, d = z1 - z0;      // UV size: the cube BEFORE inflate
		x0 -= inf;
		y0 -= inf;
		z0 -= inf;
		x1 += inf;
		y1 += inf;
		z1 += inf;
		boolean plane = w <= 1e-4f || h <= 1e-4f || d <= 1e-4f;
		Map<String, float[]> boxUv = c.uv() != null ? boxFaces(c.uv()[0], c.uv()[1], w, h, d, c.mirror()) : java.util.Collections.<String, float[]>emptyMap();

		for (String face : FACES) {
			float[] rect = c.faces().get(face);
			if (rect == null) {
				rect = boxUv.get(face);
			}
			if (rect == null) {
				continue;
			}
			// A face with no area (the sides of a flat plane) is skipped.
			boolean flat;
			switch (face) {
				case "north":
				case "south":
					flat = x1 - x0 <= 1e-4f || y1 - y0 <= 1e-4f;
					break;
				case "east":
				case "west":
					flat = y1 - y0 <= 1e-4f || z1 - z0 <= 1e-4f;
					break;
				default:
					flat = x1 - x0 <= 1e-4f || z1 - z0 <= 1e-4f;
					break;
			}
			if (flat) {
				continue;
			}
			float[][] corners = faceCorners(face, x0, y0, z0, x1, y1, z1);   // TL, BL, BR, TR
			float[] normal = normalOf(face);
			float push = plane ? PLANE_SEPARATION : 0f;
			float[] uvs = {rect[0], rect[1], rect[0], rect[3], rect[2], rect[3], rect[2], rect[1]};
			float[] q = new float[QUAD];
			float[] nrm = normal.clone();
			float[] ro = c.rotOrigin(), ra = c.rotAngle();
			boolean rotated = ra[0] != 0f || ra[1] != 0f || ra[2] != 0f;
			for (int v = 0; v < 4; v++) {
				float[] p = {corners[v][0] + normal[0] * push, corners[v][1] + normal[1] * push,
						corners[v][2] + normal[2] * push};
				if (rotated) {
					p = rotateAbout(p, ro, ra);
				}
				q[v * 5] = p[0];
				q[v * 5 + 1] = p[1];
				q[v * 5 + 2] = p[2];
				q[v * 5 + 3] = uvs[v * 2];
				q[v * 5 + 4] = uvs[v * 2 + 1];
			}
			if (rotated) {
				nrm = rotateVec(nrm, ra);
			}
			q[20] = nrm[0];
			q[21] = nrm[1];
			q[22] = nrm[2];
			for (float f : q) {
				out.add(f);
			}
		}
	}

	/** Blockbench box UV for a w x h x d cube at (u, v); `mirror` swaps east/west and flips u. */
	static Map<String, float[]> boxFaces(float u, float v, float w, float h, float d, boolean mirror) {
		Map<String, float[]> m = new HashMap<>();
		m.put("up", new float[] {u + d, v, u + d + w, v + d});
		m.put("down", new float[] {u + d + w, v, u + d + 2 * w, v + d});
		m.put("north", new float[] {u + d, v + d, u + d + w, v + d + h});
		m.put("east", new float[] {u, v + d, u + d, v + d + h});
		m.put("south", new float[] {u + 2 * d + w, v + d, u + 2 * d + 2 * w, v + d + h});
		m.put("west", new float[] {u + d + w, v + d, u + 2 * d + w, v + d + h});
		if (mirror) {
			float[] e = m.get("east");
			m.put("east", m.get("west"));
			m.put("west", e);
			for (float[] r : m.values()) {
				float t = r[0];
				r[0] = r[2];
				r[2] = t;
			}
		}
		return m;
	}

	/** The four corners of a face as seen from outside, in the order top-left, bottom-left, bottom-right, top-right (counter-clockwise). */
	static float[][] faceCorners(String face, float x0, float y0, float z0, float x1, float y1, float z1) {
		switch (face) {
			// seen from -z: right = -x
			case "north":
				return new float[][] {{x1, y1, z0}, {x1, y0, z0}, {x0, y0, z0}, {x0, y1, z0}};
			// seen from +z: right = +x
			case "south":
				return new float[][] {{x0, y1, z1}, {x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}};
			// seen from +x: right = -z
			case "east":
				return new float[][] {{x1, y1, z1}, {x1, y0, z1}, {x1, y0, z0}, {x1, y1, z0}};
			// seen from -x: right = +z
			case "west":
				return new float[][] {{x0, y1, z0}, {x0, y0, z0}, {x0, y0, z1}, {x0, y1, z1}};
			// seen from above: image top = +z, right = -x
			case "up":
				return new float[][] {{x1, y1, z1}, {x1, y1, z0}, {x0, y1, z0}, {x0, y1, z1}};
			// seen from below: image top = -z, right = -x
			default:
				return new float[][] {{x1, y0, z0}, {x1, y0, z1}, {x0, y0, z1}, {x0, y0, z0}};
		}
	}

	static float[] normalOf(String face) {
		switch (face) {
			case "north":
				return new float[] {0, 0, -1};
			case "south":
				return new float[] {0, 0, 1};
			case "east":
				return new float[] {1, 0, 0};
			case "west":
				return new float[] {-1, 0, 0};
			case "up":
				return new float[] {0, 1, 0};
			default:
				return new float[] {0, -1, 0};
		}
	}

	/** Rotates `v` by `deg` (x, y, z): Z first, then Y, then X (FORMAT.md; tools/cosmetics/build.mjs rotXYZ and website/js/rig.js 'XYZ'), i.e. R = Rx * Ry * Rz. */
	static float[] rotateVec(float[] v, float[] deg) {
		double rx = Math.toRadians(deg[0]), ry = Math.toRadians(deg[1]), rz = Math.toRadians(deg[2]);
		double cx = Math.cos(rx), sx = Math.sin(rx), cy = Math.cos(ry), sy = Math.sin(ry), cz = Math.cos(rz), sz = Math.sin(rz);
		double x = v[0], y = v[1], z = v[2];
		double x1 = x * cz - y * sz, y1 = x * sz + y * cz;           // about Z
		double x2 = x1 * cy + z * sy, z2 = -x1 * sy + z * cy;        // about Y
		double y3 = y1 * cx - z2 * sx, z3 = y1 * sx + z2 * cx;       // about X
		return new float[] {(float) x2, (float) y3, (float) z3};
	}

	static float[] rotateAbout(float[] p, float[] origin, float[] deg) {
		float[] r = rotateVec(new float[] {p[0] - origin[0], p[1] - origin[1], p[2] - origin[2]}, deg);
		return new float[] {r[0] + origin[0], r[1] + origin[1], r[2] + origin[2]};
	}
}
