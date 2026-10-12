package com.origin.client.client.worldedit;

import java.util.ArrayList;
import java.util.List;

// One WorldEdit selection as the server described it over the CUI protocol --
// pure data, no drawing (CuiRenderer turns it into lines each frame). One class
// with a `type` field rather than a class per shape: every shape is only a few
// fields, and the protocol mutates them piecemeal (a point here, a radius
// there), so a flat record of "what the server has told us so far" is simplest.
//
// Field meanings follow WorldEditCUI (github.com/EngineHub/WorldEditCUI,
// render/region/*Region.java), which is the reference client for this protocol.
final class CuiRegion {
	// Protocol keys from the server's `s|<type>` event.
	static final String CUBOID = "cuboid";
	static final String POLYGON = "polygon2d";
	static final String ELLIPSOID = "ellipsoid";
	static final String CYLINDER = "cylinder";
	static final String POLYHEDRON = "polyhedron";

	// The colour roles a shape draws with; mapped to the mod's colour options,
	// or to a server-sent palette (`+col`) for that region.
	static final int OUTLINE = 0, GRID = 1, POS1 = 2, POS2 = 3;

	final String type;

	// cuboid (ids 0/1) and polyhedron (any id) block positions; null = unset.
	final List<double[]> points = new ArrayList<>();
	// cuboid "vertex latch" (`p|id|~|~|~|dist`): that corner follows the block the
	// player is looking at, up to this distance. null entry = a fixed point.
	final List<Double> latches = new ArrayList<>();

	// polygon2d: corner columns (x, z); null = unset. min/max = Y range (`mm`).
	final List<int[]> points2d = new ArrayList<>();
	int minY, maxY;

	// ellipsoid: centre block + radii (x, y, z).
	int[] centre;
	double[] radii;

	// cylinder: centre block + horizontal radii; Y range shares minY/maxY.
	double radX, radZ;

	// polyhedron faces: lists of vertex ids into `points`.
	final List<int[]> faces = new ArrayList<>();

	// Server-only extras for "multi" regions (WorldGuard/CraftBook previews):
	// a per-region palette (`+col`) and grid spacing / cull mode (`+grid`).
	// palette == null (or a null entry) -> the player's own colour for that role.
	Integer[] palette;
	double gridSpacing = 1.0;
	// `+grid|n|cull`: the server asked for no see-through pass on this region.
	boolean visibleOnly;

	CuiRegion(String type) {
		this.type = type;
	}

	/** null for an unknown key, `clear`, or anything else the protocol doesn't define. */
	static CuiRegion create(String key) {
		switch (key) {
			case CUBOID: case POLYGON: case ELLIPSOID: case CYLINDER: case POLYHEDRON: return new CuiRegion(key);
			default: return null;
		}
	}

	/** How many colours this shape's `+col` palette carries (WorldEditCUI's style count per region). */
	int paletteSize() {
		switch (type) {
			case CUBOID: return 4;
			case ELLIPSOID: return 2;
			default: return 3;
		}
	}

	/**
	 * Index into a server palette for a colour role, in WorldEditCUI's style
	 * order per shape: cuboid [edge, grid, point1, point2]; polygon [edge, grid,
	 * point]; ellipsoid [grid, centre]; cylinder [edge, grid, centre];
	 * polyhedron [edge, vertex, first vertex].
	 */
	int paletteIndex(int role) {
		switch (type) {
			case CUBOID: return role;
			case ELLIPSOID: return role == POS1 || role == POS2 ? 1 : 0;
			case POLYHEDRON: return role == POS1 ? 2 : role == POS2 ? 1 : 0;
			default: return role == POS1 || role == POS2 ? 2 : role;   // polygon2d, cylinder
		}
	}

	// Highest point/vertex id accepted. A real selection never comes close; the
	// cap only stops a hostile `p|999999999|...` from allocating a huge list.
	static final int MAX_ID = 4096;

	static <T> void put(List<T> list, int id, T value) {
		if (id < 0 || id > MAX_ID) {
			return;
		}
		while (list.size() <= id) {
			list.add(null);
		}
		list.set(id, value);
	}

	static <T> T get(List<T> list, int id) {
		return id >= 0 && id < list.size() ? list.get(id) : null;
	}
}
