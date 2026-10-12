package com.origin.client.client.worldedit;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Matrix4f;
import com.origin.client.client.gui.OriginColorPicker;
import com.origin.client.client.mods.Mods;
import com.origin.client.client.render.OriginRenderTypes;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

// The DRAWING half of WorldEdit CUI: turns each CuiRegion into lines every frame,
// on WorldRenderEvents.LAST. Shapes and their offsets follow WorldEditCUI
// (render/shapes/*.java) so a selection lands exactly where players expect:
//   cuboid     -- the box (corners grown 0.02 past the blocks), a grid over all
//                 six faces at the server's spacing, and a cube on each corner.
//   polygon2d  -- a post at every corner column, top + bottom loops, a loop per
//                 block of height as the grid, and a column box per corner.
//   ellipsoid  -- the three great circles as the outline, latitude rings in each
//                 plane as the grid, a cube on the centre.
//   cylinder   -- top + bottom circles, a ring per block of height and the
//                 vertical slices as the grid, a cube on the centre.
//   polyhedron -- every face as a loop, a cube on every vertex.
// "Show Grid" off leaves just the outline + points: a readable frame of the
// selection without the lattice.
//
// LINES ARE REAL GEOMETRY. Every segment is one camera-facing quad (a ribbon)
// in Origin's own overlay render type, not RenderType.lines(): that shader
// widens lines in SCREEN space from the projected endpoints, and a long edge
// with one end behind the camera projects sign-flipped -- it swings and bends as
// you turn (the waypoint-beam bug). A ribbon is clipped by the GPU like any
// other world quad. Its half-width grows with each endpoint's distance from the
// camera, so a line keeps one steady on-screen width ("Line Thickness" ~= pixels
// at 1080p) from your feet to the far end of a 500-block selection.
//
// CAMERA ROTATION IS APPLIED EXACTLY ONCE. On 1.17.1 Fabric fires LAST inside
// renderLevel's clouds/weather block, after vanilla pushed the pass's pose (which
// holds the camera rotation) onto the global model-view, and ctx.matrixStack()
// holds that same rotation. WorldEditCui's LAST hook therefore sets the
// model-view to IDENTITY around this draw -- the same window OriginClientMod
// opens for WaypointRenderer / ChunkBorderRenderer -- and vertices are camera-
// RELATIVE world coords passed through matrixStack().last(), so the rotation
// comes from the pose alone. Never let both carry it (that double rotation is
// what broke waypoints on 1.16.5-1.19.3).
//
// SEE THROUGH: first a pass that ONLY draws where something is in front
// (GREATER depth test), dimmed to 75% colour / 25% opacity -- WorldEditCUI's
// own hidden-line style -- then the normal depth-tested pass on top. Off = just
// the normal pass, so terrain hides what is behind it.
final class CuiRenderer {
	private static final String M = WorldEditCui.ID;

	// Line half-width per block of distance from the camera, per thickness step.
	// One screen pixel at 1080p / FOV 70 subtends ~0.0013 rad, so 2 x 0.00065 x d
	// is ~1px per step at any distance d.
	private static final double HALF_WIDTH_PER_STEP = 0.00065;
	// Up close the distance-scaled width tends to zero; keep a hair of width.
	private static final double MIN_HALF_WIDTH = 0.003;
	// Grid lines further than this many grid cells from the camera are skipped
	// (WorldEditCUI's CULL_RANGE), so a 2000-block selection costs no more than a
	// 256-block one.
	private static final double GRID_CULL_CELLS = 128;
	// A grid band closer than this to the far face is dropped (it would sit on the
	// edge itself). WorldEditCUI's SKIP_THRESHOLD.
	private static final double GRID_SKIP = 0.25;
	// Rings per ellipsoid plane / cylinder height before they are thinned out:
	// beyond this they merge into a solid wash anyway.
	private static final int MAX_RINGS = 128;
	private static final int ELLIPSE_SEGMENTS = 40;
	private static final int CIRCLE_SEGMENTS = 75;
	// Offsets lifted from WorldEditCUI: corner cubes stand 0.03 proud of the block,
	// the cuboid box 0.02, polygon loops sit 0.03 above the block face.
	private static final double POINT_PAD = 0.03;
	private static final double BOX_PAD = 0.02;
	private static final double LIFT = 0.03;
	private static final double OFF = 0.001;

	// ---- the see-through pass's render type ----

	/**
	 * Origin's overlay quads (OriginRenderTypes.OVERLAY_QUADS) with the depth test
	 * flipped to GREATER: fragments draw ONLY where terrain is in front of them.
	 * No depth write, no view offset (nothing here should win a depth tie).
	 * Same constructor + vanilla shard technique as OriginRenderTypes. 1.17.1
	 * has no GREATER_DEPTH_TEST shard (only always / == / <=), so this subclass
	 * builds one from vanilla's own DepthTestStateShard (protected nested class,
	 * public constructor) with GL_GREATER (516).
	 */
	private static final class HiddenQuads extends RenderType {
		private static final DepthTestStateShard GREATER_DEPTH_TEST = new DepthTestStateShard(">", 516);

		private HiddenQuads() {
			super("originclient_cui_hidden_quads", DefaultVertexFormat.POSITION_COLOR,
					VertexFormat.Mode.QUADS, 1 << 16, false, false,
					() -> {
						POSITION_COLOR_SHADER.setupRenderState();
						TRANSLUCENT_TRANSPARENCY.setupRenderState();
						GREATER_DEPTH_TEST.setupRenderState();
						NO_CULL.setupRenderState();
						COLOR_WRITE.setupRenderState();
					},
					() -> {
						COLOR_WRITE.clearRenderState();
						NO_CULL.clearRenderState();
						GREATER_DEPTH_TEST.clearRenderState();
						TRANSLUCENT_TRANSPARENCY.clearRenderState();
						POSITION_COLOR_SHADER.clearRenderState();
					});
		}
	}

	private static final RenderType HIDDEN = new HiddenQuads();
	// Private immediate buffer, drawn on the spot (see OriginRenderTypes for why
	// nothing here goes through the frame's shared, Iris-deferred buffer).
	private static final MultiBufferSource.BufferSource HIDDEN_BUFFER =
			MultiBufferSource.immediate(new BufferBuilder(1 << 16));   // no ByteBufferBuilder before 1.20.5

	// ---- per-frame draw state (render thread only) ----
	private static VertexConsumer out;
	private static PoseStack.Pose pose;
	private static boolean hiddenPass;
	private static double camX, camY, camZ;
	private static double halfPerBlock;
	private static boolean showGrid;
	private static int colOutline, colGrid, colPos1, colPos2;
	// the colour segments are currently emitted in (already pass-adjusted)
	private static float cr, cg, cb, ca;

	private CuiRenderer() {
	}

	static void render(WorldRenderContext ctx) {
		if (!Mods.on(M)) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null || ctx.matrixStack() == null) {
			return;
		}
		List<CuiRegion> regions = WorldEditCui.regions();
		if (regions.isEmpty()) {
			return;
		}
		Vec3 cam = ctx.camera().getPosition();
		camX = cam.x;
		camY = cam.y;
		camZ = cam.z;
		pose = ctx.matrixStack().last();
		showGrid = Mods.bool(M, "grid");
		halfPerBlock = HALF_WIDTH_PER_STEP * Math.max(1, Math.min(5, Mods.num(M, "thickness")));
		colOutline = OriginColorPicker.liveColor(M, "outlineColor");
		colGrid = OriginColorPicker.liveColor(M, "gridColor");
		colPos1 = OriginColorPicker.liveColor(M, "pos1Color");
		colPos2 = OriginColorPicker.liveColor(M, "pos2Color");

		trackLatches(regions, mc);

		// Each pass is flushed in a finally: a throw mid-shape must not leave half a
		// batch in the buffer to be drawn under some later frame's matrices.
		if (Mods.bool(M, "seeThrough")) {
			hiddenPass = true;
			out = HIDDEN_BUFFER.getBuffer(HIDDEN);
			try {
				for (CuiRegion r : regions) {
					if (!r.visibleOnly) {
						draw(r);
					}
				}
			} finally {
				HIDDEN_BUFFER.endBatch();
			}
		}
		hiddenPass = false;
		out = OriginRenderTypes.begin();
		try {
			for (CuiRegion r : regions) {
				draw(r);
			}
		} finally {
			OriginRenderTypes.draw();
			out = null;
		}
	}

	// Latched cuboid corners follow the block under the crosshair (WorldEditCUI's
	// PointCubeTracking): re-picked every frame from the camera entity.
	private static void trackLatches(List<CuiRegion> regions, Minecraft mc) {
		Entity eye = mc.getCameraEntity();
		if (eye == null) {
			return;
		}
		float partial = mc.getFrameTime();
		for (CuiRegion r : regions) {
			for (int id = 0; id < r.latches.size(); id++) {
				Double dist = r.latches.get(id);
				if (dist == null) {
					continue;
				}
				HitResult hit = eye.pick(dist, partial, false);
				if (hit instanceof BlockHitResult) {
					BlockPos p = ((BlockHitResult) hit).getBlockPos();
					CuiRegion.put(r.points, id, new double[]{p.getX(), p.getY(), p.getZ()});
				}
			}
		}
	}

	private static void draw(CuiRegion r) {
		switch (r.type) {
			case CuiRegion.CUBOID: cuboid(r); break;
			case CuiRegion.POLYGON: polygon(r); break;
			case CuiRegion.ELLIPSOID: ellipsoid(r); break;
			case CuiRegion.CYLINDER: cylinder(r); break;
			case CuiRegion.POLYHEDRON: polyhedron(r); break;
			default: {

				break;
			}
		}
	}

	// ---- shapes (all coordinates absolute world space; seg() makes them camera-relative) ----

	private static void cuboid(CuiRegion r) {
		double[] p1 = CuiRegion.get(r.points, 0);
		double[] p2 = CuiRegion.get(r.points, 1);
		if (p1 != null && p2 != null) {
			double x1 = Math.min(p1[0], p2[0]) - BOX_PAD, x2 = Math.max(p1[0], p2[0]) + 1 + BOX_PAD;
			double y1 = Math.min(p1[1], p2[1]) - BOX_PAD, y2 = Math.max(p1[1], p2[1]) + 1 + BOX_PAD;
			double z1 = Math.min(p1[2], p2[2]) - BOX_PAD, z2 = Math.max(p1[2], p2[2]) + 1 + BOX_PAD;
			if (showGrid && use(r, CuiRegion.GRID)) {
				cuboidGrid(x1, y1, z1, x2, y2, z2, r.gridSpacing);
			}
			if (use(r, CuiRegion.OUTLINE)) {
				box(x1, y1, z1, x2, y2, z2);
			}
		}
		if (p1 != null && use(r, CuiRegion.POS1)) {
			pointCube(p1[0], p1[1], p1[2]);
		}
		if (p2 != null && use(r, CuiRegion.POS2)) {
			pointCube(p2[0], p2[1], p2[2]);
		}
	}

	// WorldEditCUI's Render3DGrid: a ring around the box every `spacing` blocks of
	// height, and lines across the four side faces + top/bottom every `spacing`
	// along X and Z. A server spacing other than 1 also washes the faces in the
	// grid colour at quarter strength; spacing below 1 (WorldGuard sends 0) means
	// "faces only, no lines".
	private static void cuboidGrid(double x1, double y1, double z1, double x2, double y2, double z2, double spacing) {
		if (spacing != 1.0) {
			float a = ca;
			ca *= 0.25f;
			fillBox(x1, y1, z1, x2, y2, z2);
			ca = a;
		}
		if (spacing < 1.0) {
			return;
		}
		double reach = GRID_CULL_CELLS * spacing;
		double yEnd = Math.min(y2 + OFF, camY + reach);
		for (double y = gridStart(y1, camY - reach, spacing) + OFF; y <= yEnd; y += spacing) {
			if (yEnd - y < GRID_SKIP) {
				continue;
			}
			seg(x1, y, z2, x2, y, z2);
			seg(x1, y, z1, x2, y, z1);
			seg(x1, y, z1, x1, y, z2);
			seg(x2, y, z1, x2, y, z2);
		}
		double xEnd = Math.min(x2, camX + reach);
		for (double x = gridStart(x1, camX - reach, spacing); x <= xEnd; x += spacing) {
			if (xEnd - x < GRID_SKIP) {
				continue;
			}
			seg(x, y1, z1, x, y2, z1);
			seg(x, y1, z2, x, y2, z2);
			seg(x, y2, z1, x, y2, z2);
			seg(x, y1, z1, x, y1, z2);
		}
		double zEnd = Math.min(z2, camZ + reach);
		for (double z = gridStart(z1, camZ - reach, spacing); z <= zEnd; z += spacing) {
			if (zEnd - z < GRID_SKIP) {
				continue;
			}
			seg(x1, y1, z, x2, y1, z);
			seg(x1, y2, z, x2, y2, z);
			seg(x2, y1, z, x2, y2, z);
			seg(x1, y1, z, x1, y2, z);
		}
	}

	/** First grid line at or after `cullFrom`, still on the grid that starts at `origin`. */
	private static double gridStart(double origin, double cullFrom, double spacing) {
		if (origin >= cullFrom) {
			return origin;
		}
		return origin + Math.ceil((cullFrom - origin) / spacing) * spacing;
	}

	private static void polygon(CuiRegion r) {
		List<int[]> pts = r.points2d;
		double lo = r.minY + LIFT, hi = r.maxY + 1 + LIFT;
		boolean any = false;
		for (int i = 0; i < pts.size(); i++) {
			int[] p = pts.get(i);
			if (p == null) {
				continue;
			}
			any = true;
			if (use(r, i == 0 ? CuiRegion.POS1 : CuiRegion.POS2)) {
				box(p[0] - POINT_PAD, r.minY - POINT_PAD, p[1] - POINT_PAD,
						p[0] + 1 + POINT_PAD, r.maxY + 1 + POINT_PAD, p[1] + 1 + POINT_PAD);
			}
		}
		if (!any) {
			return;
		}
		if (use(r, CuiRegion.OUTLINE)) {
			for (int[] p : pts) {
				if (p != null) {
					seg(p[0] + 0.5, lo, p[1] + 0.5, p[0] + 0.5, hi, p[1] + 0.5);
				}
			}
			polyLoop(pts, lo);
			polyLoop(pts, hi);
		}
		if (showGrid && use(r, CuiRegion.GRID)) {
			int step = ringStep(r.maxY - r.minY);
			for (int h = r.minY + 1; h <= r.maxY; h += step) {
				polyLoop(pts, h + LIFT);
			}
		}
	}

	/** Closed loop through every set polygon corner (block centres) at height y. */
	private static void polyLoop(List<int[]> pts, double y) {
		int[] first = null, prev = null;
		for (int[] p : pts) {
			if (p == null) {
				continue;
			}
			if (prev == null) {
				first = p;
			} else {
				seg(prev[0] + 0.5, y, prev[1] + 0.5, p[0] + 0.5, y, p[1] + 0.5);
			}
			prev = p;
		}
		if (prev != null && prev != first) {
			seg(prev[0] + 0.5, y, prev[1] + 0.5, first[0] + 0.5, y, first[1] + 0.5);
		}
	}

	private static void ellipsoid(CuiRegion r) {
		if (r.centre == null) {
			return;
		}
		if (use(r, CuiRegion.POS1)) {
			pointCube(r.centre[0], r.centre[1], r.centre[2]);
		}
		if (r.radii == null) {
			return;
		}
		double cx = r.centre[0] + 0.5, cy = r.centre[1] + 0.5, cz = r.centre[2] + 0.5;
		double rx = r.radii[0], ry = r.radii[1], rz = r.radii[2];
		if (use(r, CuiRegion.OUTLINE)) {
			ellipse(cx, cy, cz, 0, rx, rz, 0);   // XZ (horizontal)
			ellipse(cx, cy, cz, 1, ry, rz, 0);   // YZ
			ellipse(cx, cy, cz, 2, rx, ry, 0);   // XY
		}
		if (!showGrid || !use(r, CuiRegion.GRID)) {
			return;
		}
		// Latitude rings: the ellipse's cross-section at each whole block offset
		// along the plane's normal axis (WorldEditCUI's RenderEllipsoid).
		int yr = (int) Math.floor(ry), step = ringStep(2 * yr);
		for (int k = -yr; k < yr; k += step) {
			if (k != 0) {
				double s = Math.cos(Math.asin(k / ry));
				ellipse(cx, cy, cz, 0, rx * s, rz * s, k);
			}
		}
		int xr = (int) Math.floor(rx);
		step = ringStep(2 * xr);
		for (int k = -xr; k < xr; k += step) {
			if (k != 0) {
				double s = Math.sin(Math.acos(k / rx));
				ellipse(cx, cy, cz, 1, ry * s, rz * s, k);
			}
		}
		int zr = (int) Math.floor(rz);
		step = ringStep(2 * zr);
		for (int k = -zr; k < zr; k += step) {
			if (k != 0) {
				double s = Math.sin(Math.acos(k / rz));
				ellipse(cx, cy, cz, 2, rx * s, ry * s, k);
			}
		}
	}

	/**
	 * One closed ellipse around (cx,cy,cz), shifted `off` along the plane's normal.
	 * plane 0 = horizontal (radius a on X, b on Z, off on Y); 1 = YZ (a on Y, b on
	 * Z, off on X); 2 = XY (a on X, b on Y, off on Z).
	 */
	private static void ellipse(double cx, double cy, double cz, int plane, double a, double b, double off) {
		if (!(a > 0) || !(b > 0)) {
			return;
		}
		double px = 0, py = 0, pz = 0;
		for (int i = 0; i <= ELLIPSE_SEGMENTS; i++) {
			double t = i * (Math.PI * 2) / ELLIPSE_SEGMENTS;
			double u = a * Math.cos(t), v = b * Math.sin(t);
			double x, y, z;
			switch (plane) {
				case 0: {
					x = cx + u;
					y = cy + off;
					z = cz + v;
					break;
				}
				case 1: {
					x = cx + off;
					y = cy + u;
					z = cz + v;
					break;
				}
				default: {
					x = cx + u;
					y = cy + v;
					z = cz + off;
					break;
				}
			}
			if (i > 0) {
				seg(px, py, pz, x, y, z);
			}
			px = x;
			py = y;
			pz = z;
		}
	}

	private static void cylinder(CuiRegion r) {
		if (r.centre == null) {
			return;
		}
		if (use(r, CuiRegion.POS1)) {
			pointCube(r.centre[0], r.centre[1], r.centre[2]);
		}
		// WorldEditCUI: until the server sends a Y range, the cylinder is one block tall at the centre.
		int lo = r.minY, hi = r.maxY;
		if (lo == 0 || hi == 0) {
			lo = r.centre[1];
			hi = r.centre[1];
		}
		double cx = r.centre[0] + 0.5, cz = r.centre[2] + 0.5;
		double rx = r.radX, rz = r.radZ;
		if (use(r, CuiRegion.OUTLINE)) {
			circle(cx, lo, cz, rx, rz);
			circle(cx, hi + 1, cz, rx, rz);
		}
		if (!showGrid || !use(r, CuiRegion.GRID)) {
			return;
		}
		int step = ringStep(hi - lo);
		for (int y = lo + 1; y <= hi; y += step) {
			circle(cx, y, cz, rx, rz);
		}
		if (!(rx > 0) || !(rz > 0)) {
			return;
		}
		// Vertical slices: a rectangle through the cylinder at every whole block
		// along X and along Z (WorldEditCUI's RenderCylinderGrid).
		double top = hi + 1, bottom = lo;
		int nx = (int) Math.ceil(rx), xs = ringStep(2 * nx);
		for (int k = -nx; k <= nx; k += xs) {
			double h = rz * Math.cos(Math.asin(k / rx));
			if (Double.isNaN(h)) {
				continue;
			}
			rect(cx + k, top, cz + h, cx + k, top, cz - h, cx + k, bottom, cz - h, cx + k, bottom, cz + h);
		}
		int nz = (int) Math.ceil(rz), zs = ringStep(2 * nz);
		for (int k = -nz; k <= nz; k += zs) {
			double h = rx * Math.sin(Math.acos(k / rz));
			if (Double.isNaN(h)) {
				continue;
			}
			rect(cx + h, top, cz + k, cx - h, top, cz + k, cx - h, bottom, cz + k, cx + h, bottom, cz + k);
		}
	}

	private static void circle(double cx, double y, double cz, double rx, double rz) {
		if (!(rx > 0) || !(rz > 0)) {
			return;
		}
		double px = cx + rx, pz = cz;
		for (int i = 1; i <= CIRCLE_SEGMENTS; i++) {
			double t = i * (Math.PI * 2) / CIRCLE_SEGMENTS;
			double x = cx + rx * Math.cos(t), z = cz + rz * Math.sin(t);
			seg(px, y, pz, x, y, z);
			px = x;
			pz = z;
		}
	}

	private static void polyhedron(CuiRegion r) {
		for (int id = 0; id < r.points.size(); id++) {
			double[] p = r.points.get(id);
			if (p != null && use(r, id == 0 ? CuiRegion.POS1 : CuiRegion.POS2)) {
				pointCube(p[0], p[1], p[2]);
			}
		}
		if (!use(r, CuiRegion.OUTLINE)) {
			return;
		}
		for (int[] face : r.faces) {
			double[] prev = null, first = null;
			for (int id : face) {
				double[] p = CuiRegion.get(r.points, id);
				if (p == null) {
					continue;
				}
				if (prev == null) {
					first = p;
				} else {
					seg(prev[0] + 0.5, prev[1] + 0.5, prev[2] + 0.5, p[0] + 0.5, p[1] + 0.5, p[2] + 0.5);
				}
				prev = p;
			}
			if (prev != null && prev != first) {
				seg(prev[0] + 0.5, prev[1] + 0.5, prev[2] + 0.5, first[0] + 0.5, first[1] + 0.5, first[2] + 0.5);
			}
		}
	}

	/** Thins rings out so no shape draws more than MAX_RINGS of them per axis. */
	private static int ringStep(int count) {
		return Math.max(1, (int) Math.ceil(count / (double) MAX_RINGS));
	}

	// ---- primitives ----

	/** A marker cube around one block, 0.03 proud of it (WorldEditCUI's PointCube). */
	private static void pointCube(double x, double y, double z) {
		box(x - POINT_PAD, y - POINT_PAD, z - POINT_PAD, x + 1 + POINT_PAD, y + 1 + POINT_PAD, z + 1 + POINT_PAD);
	}

	/** The 12 edges of an axis-aligned box. */
	private static void box(double x1, double y1, double z1, double x2, double y2, double z2) {
		rect(x1, y1, z1, x2, y1, z1, x2, y1, z2, x1, y1, z2);   // bottom
		rect(x1, y2, z1, x2, y2, z1, x2, y2, z2, x1, y2, z2);   // top
		seg(x1, y1, z1, x1, y2, z1);
		seg(x2, y1, z1, x2, y2, z1);
		seg(x2, y1, z2, x2, y2, z2);
		seg(x1, y1, z2, x1, y2, z2);
	}

	/** A closed four-point loop. */
	private static void rect(double ax, double ay, double az, double bx, double by, double bz,
							 double cx, double cy, double cz, double dx, double dy, double dz) {
		seg(ax, ay, az, bx, by, bz);
		seg(bx, by, bz, cx, cy, cz);
		seg(cx, cy, cz, dx, dy, dz);
		seg(dx, dy, dz, ax, ay, az);
	}

	/** Six translucent faces of a box (the server-spacing face wash). */
	private static void fillBox(double x1, double y1, double z1, double x2, double y2, double z2) {
		if (ca <= 0f) {
			return;
		}
		float ax = (float) (x1 - camX), ay = (float) (y1 - camY), az = (float) (z1 - camZ);
		float bx = (float) (x2 - camX), by = (float) (y2 - camY), bz = (float) (z2 - camZ);
		quad(ax, ay, az, bx, ay, az, bx, ay, bz, ax, ay, bz);
		quad(ax, by, az, bx, by, az, bx, by, bz, ax, by, bz);
		quad(ax, ay, az, ax, ay, bz, ax, by, bz, ax, by, az);
		quad(bx, ay, az, bx, by, az, bx, by, bz, bx, ay, bz);
		quad(ax, ay, az, ax, by, az, bx, by, az, bx, ay, az);
		quad(ax, ay, bz, bx, ay, bz, bx, by, bz, ax, by, bz);
	}

	/**
	 * One line segment as a camera-facing ribbon (see the class comment). The
	 * camera is the origin of camera-relative space, so the ribbon's sideways
	 * direction is perpendicular to both the segment and the line of sight to it:
	 * cross(b - a, a) -- the same direction at both ends, so the quad stays flat.
	 * Each end's half-width is proportional to its own distance, which is exactly
	 * what keeps the on-screen width constant along the whole line.
	 */
	private static void seg(double ax, double ay, double az, double bx, double by, double bz) {
		if (ca <= 0f) {
			return;
		}
		ax -= camX;
		ay -= camY;
		az -= camZ;
		bx -= camX;
		by -= camY;
		bz -= camZ;
		double dx = bx - ax, dy = by - ay, dz = bz - az;
		double nx = dy * az - dz * ay;
		double ny = dz * ax - dx * az;
		double nz = dx * ay - dy * ax;
		double nl = Math.sqrt(nx * nx + ny * ny + nz * nz);
		if (nl < 1.0e-9) {
			return;   // zero length, or pointing straight at the camera: nothing visible
		}
		nx /= nl;
		ny /= nl;
		nz /= nl;
		double wa = Math.max(MIN_HALF_WIDTH, Math.sqrt(ax * ax + ay * ay + az * az) * halfPerBlock);
		double wb = Math.max(MIN_HALF_WIDTH, Math.sqrt(bx * bx + by * by + bz * bz) * halfPerBlock);
		quad((float) (ax - nx * wa), (float) (ay - ny * wa), (float) (az - nz * wa),
				(float) (bx - nx * wb), (float) (by - ny * wb), (float) (bz - nz * wb),
				(float) (bx + nx * wb), (float) (by + ny * wb), (float) (bz + nz * wb),
				(float) (ax + nx * wa), (float) (ay + ny * wa), (float) (az + nz * wa));
	}

	private static void quad(float x0, float y0, float z0, float x1, float y1, float z1,
							 float x2, float y2, float z2, float x3, float y3, float z3) {
		Matrix4f m = pose.pose();
		out.vertex(m, x0, y0, z0).color(cr, cg, cb, ca).endVertex();
		out.vertex(m, x1, y1, z1).color(cr, cg, cb, ca).endVertex();
		out.vertex(m, x2, y2, z2).color(cr, cg, cb, ca).endVertex();
		out.vertex(m, x3, y3, z3).color(cr, cg, cb, ca).endVertex();
	}

	/**
	 * Makes the region's colour for `role` the current draw colour, adjusted for
	 * the pass (the see-through pass is 75% colour at 25% opacity). Returns false
	 * when it ends up fully transparent, so the caller can skip the shape.
	 */
	private static boolean use(CuiRegion r, int role) {
		int argb;
		switch (role) {
			case CuiRegion.OUTLINE: argb = colOutline; break;
			case CuiRegion.GRID: argb = colGrid; break;
			case CuiRegion.POS1: argb = colPos1; break;
			default: argb = colPos2; break;
		}
		if (r.palette != null) {
			int i = r.paletteIndex(role);
			if (i < r.palette.length && r.palette[i] != null) {
				argb = r.palette[i];
			}
		}
		float k = hiddenPass ? 0.75f : 1f;
		cr = ((argb >> 16) & 0xFF) / 255f * k;
		cg = ((argb >> 8) & 0xFF) / 255f * k;
		cb = (argb & 0xFF) / 255f * k;
		ca = ((argb >>> 24) & 0xFF) / 255f * (hiddenPass ? 0.25f : 1f);
		return ca > 0f;
	}
}
