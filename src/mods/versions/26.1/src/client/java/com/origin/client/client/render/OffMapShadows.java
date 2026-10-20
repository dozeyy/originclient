package com.origin.client.client.render;

import com.origin.client.client.hud.FrameStats;
import com.origin.client.client.mixin.SodiumSortedRenderListsAccessor;
import com.origin.client.client.mods.Mods;
import net.caffeinemc.mods.sodium.client.render.chunk.LocalSectionIndex;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.util.iterator.ByteIterator;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;

/**
 * SETTINGS > Performance > Off-Map Shadows (26.1 port of 1.21.1, default OFF).
 *
 * <p>Why it saves work (Iris 1.11.4 / Sodium 0.9.2 +mc26.2, read from the jars 2026-10-20): Iris's
 * shadow pass culls with AdvancedShadowCullingFrustum, which is the player's view stretched toward
 * the sun plus, only when the pack sets shadowDistanceRenderMul, a world-axis distance box. Its
 * constructor takes the shadow projection and never stores it, so nothing culls against the shadow
 * map's own left/right/top/bottom edges. Every chunk section, entity and block entity between the map
 * edge and the Iris "Shadow Distance" is drawn into the shadow map and then clipped away by the GPU.
 *
 * <p>The test: the box's light-space x/y range under ShadowRenderer.PROJECTION * MODELVIEW (the exact
 * matrices Iris draws the shadow pass with, camera-relative; both are set in renderShadows before the
 * Sodium walk). A box entirely past one edge is skipped. Only for an orthographic shadow projection.
 *
 * <p>PER-VERSION (26.1.2, Sodium 0.9.2): the shadow walk is RenderSectionManager.renderOutOfGraph (Iris
 * asks setupTerrain to update immediately), a TraversableTree walk that tests boxes through
 * Viewport.getBoxIntersectionDirect (tree nodes) and Viewport.isBoxVisibleDirect (leaf sections), both
 * with camera-relative center floats and a cube half size. 1.21.1's Viewport.isBoxVisible(IIIFFF) hook
 * point no longer exists. The walk is armed with ITS viewport object, and the Viewport hooks act only
 * when (this == the armed viewport): the async main-pass cull threads use other Viewport objects, so
 * they are never touched even while a shadow walk runs.
 *
 * <p>PER-VERSION (26.1): the same jar also runs Sodium 0.8.9 (26.1 / 26.1.1), whose Viewport has the same
 * two tests and whose shadow walk (createTerrainRenderList, Iris 1.10.9) takes the same TraversableTree
 * path; there SodiumWalkEndMixin arms at createTerrainRenderList HEAD, disarms at its RETURN and counts
 * at finalizeRenderLists(Viewport) RETURN. On 0.9.2 SodiumRenderSectionManagerHooksMixin does it.
 *
 * <p>What it changes on screen: a caster only shades receivers at its own light-space x/y, so ground
 * inside the map looks the same. A pack that distorts its shadow map squeezes the area past the edge
 * into a thin outer ring; ground out there loses shadows from casters past the edge. EDGE keeps a 10%
 * margin for filter kernels near the edge.
 *
 * <p>Safety (mandate 4): Iris is reached by reflection (bound once; missing or changed Iris = feature
 * off). The hooks only ever answer "skip" inside Iris's shadow pass. Any exception latches the feature
 * off for the session and is logged once. No allocation per frame.
 */
public final class OffMapShadows {
	private OffMapShadows() {
	}

	public static final String KEY_ON = "offMapShadows";

	/** Dev-only counting with the option OFF too (CI measurement). False for players. */
	private static final boolean DEV = System.getenv("ORIGIN_SHOTS") != null;

	/** Keep anything within 10% past the edge (in map units, 1 = the edge). */
	private static final float EDGE = 1.1f;

	private static final MethodHandle IS_SHADOW_PASS = IrisState.SHADOW_PASS;
	private static final MethodHandle MODELVIEW = staticGetter("MODELVIEW");
	private static final MethodHandle PROJECTION = staticGetter("PROJECTION");

	private static MethodHandle staticGetter(String field) {
		try {
			Class<?> c = Class.forName("net.irisshaders.iris.shadows.ShadowRenderer");
			return MethodHandles.publicLookup().findStaticGetter(c, field, Matrix4f.class);
		} catch (Throwable t) {
			return null;
		}
	}

	private static boolean broken = IS_SHADOW_PASS == null || MODELVIEW == null || PROJECTION == null;

	// Light-space x and y rows of PROJECTION * MODELVIEW, rebuilt when Iris installs new matrices (a new
	// MODELVIEW object every shadow frame, renderShadows offset 189).
	private static Matrix4f lastMv, lastProj;
	private static final Matrix4f COMBINED = new Matrix4f();
	private static boolean ortho;
	private static float ax, ay, az, aw, bx, by, bz, bw, aax, aay, aaz, abx, aby, abz;
	private static float halfPlane;

	/** Dev-shots: the shadow map's half width in blocks (1 / PROJECTION.m00), from the last matrices seen. */
	public static float devHalfPlane() {
		return halfPlane;
	}

	private static long settingsFrame = Long.MIN_VALUE;
	private static boolean cachedOn;

	/**
	 * The viewport of the shadow walk in progress (set at renderOutOfGraph HEAD with the option on, an
	 * ortho projection and Iris in its shadow pass; cleared at RETURN). Null = every Viewport hook
	 * returns its value untouched after one reference compare.
	 */
	private static Object armedViewport;

	// Per-frame skip counts (last finished frame) for the F3 line. Sections count box CHECKS the shadow
	// walk answered "off the map" (tree nodes and leaf sections, empty air included), not drawn sections
	// removed (dev-shots measures that: afterWalk).
	private static long frameId = -1, shadowFrame = Long.MIN_VALUE;
	private static int curSkipS, curSkipE, lastSkipS, lastSkipE;

	// Dev totals: shadow walks, sections with geometry they drew and how many were fully off-map;
	// entities the shadow pass kept, how many were off-map, how many this feature skipped; box checks
	// this feature answered "off" (devSkipBoxes) or turned from "inside" into "test the children"
	// (devSplitBoxes).
	public static long devWalks, devDrawnS, devOffS, devSeenE, devOffE, devSkipE, devSkipBoxes, devSplitBoxes;

	private static boolean settingOn() {
		long f = FrameStats.frames();
		if (f != settingsFrame) {
			settingsFrame = f;
			cachedOn = Mods.bool(Mods.PERFORMANCE_ID, KEY_ON);
		}
		return cachedOn;
	}

	private static void rollFrame() {
		long f = FrameStats.frames();
		if (f != frameId) {
			frameId = f;
			lastSkipS = curSkipS;
			lastSkipE = curSkipE;
			curSkipS = curSkipE = 0;
		}
	}

	/** True when the current shadow matrices are known and orthographic. */
	private static boolean matrices() throws Throwable {
		Matrix4f mv = (Matrix4f) MODELVIEW.invokeExact();
		Matrix4f pr = (Matrix4f) PROJECTION.invokeExact();
		if (mv == null || pr == null) {
			return false;
		}
		if (mv != lastMv || pr != lastProj) {
			lastMv = mv;
			lastProj = pr;
			pr.mul(mv, COMBINED);
			Matrix4f m = COMBINED;
			ortho = Math.abs(m.m03()) < 1e-6f && Math.abs(m.m13()) < 1e-6f && Math.abs(m.m23()) < 1e-6f
					&& Math.abs(m.m33() - 1f) < 1e-6f;
			ax = m.m00(); ay = m.m10(); az = m.m20(); aw = m.m30();
			bx = m.m01(); by = m.m11(); bz = m.m21(); bw = m.m31();
			aax = Math.abs(ax); aay = Math.abs(ay); aaz = Math.abs(az);
			abx = Math.abs(bx); aby = Math.abs(by); abz = Math.abs(bz);
			halfPlane = pr.m00() != 0f ? 1f / pr.m00() : 0f;
		}
		return ortho;
	}

	/** Box (camera-relative center, half extents) entirely past one edge of the shadow map. */
	private static boolean offMap(float cx, float cy, float cz, float ex, float ey, float ez) {
		float x = ax * cx + ay * cy + az * cz + aw;
		float rx = aax * ex + aay * ey + aaz * ez;
		if (x - rx > EDGE || x + rx < -EDGE) {
			return true;
		}
		float y = bx * cx + by * cy + bz * cz + bw;
		float ry = abx * ex + aby * ey + abz * ez;
		return y - ry > EDGE || y + ry < -EDGE;
	}

	/** Cube (camera-relative center, half size h) entirely inside the kept map area (both axes). */
	private static boolean insideMap(float cx, float cy, float cz, float h) {
		float x = ax * cx + ay * cy + az * cz + aw;
		float rx = (aax + aay + aaz) * h;
		if (x - rx < -EDGE || x + rx > EDGE) {
			return false;
		}
		float y = bx * cx + by * cy + bz * cz + bw;
		float ry = (abx + aby + abz) * h;
		return y - ry >= -EDGE && y + ry <= EDGE;
	}

	/**
	 * Viewport.isBoxVisibleDirect RETURN (leaf section test of the tree walk): false when this is the
	 * armed shadow walk's viewport and the box lies entirely off the shadow map.
	 */
	public static boolean keepBox(Object viewport, boolean visible, float cx, float cy, float cz, float h) {
		if (!visible || viewport != armedViewport) {
			return visible;
		}
		if (offMap(cx, cy, cz, h, h, h)) {
			curSkipS++;
			if (DEV) {
				devSkipBoxes++;
			}
			return false;
		}
		return true;
	}

	/**
	 * Viewport.getBoxIntersectionDirect RETURN (tree node test: -2 = fully inside the frustum, so the
	 * walk stops testing this node's children; -1 = crosses a plane, test children; anything else =
	 * outside). For the armed shadow walk: a node fully off the map answers 0 (outside); a node the
	 * frustum calls fully inside but that crosses the map edge answers -1, so its children still get
	 * the off-map test instead of all being accepted untested.
	 */
	public static int intersectBox(Object viewport, int result, float cx, float cy, float cz, float h) {
		if (viewport != armedViewport || (result != -2 && result != -1)) {
			return result;
		}
		if (offMap(cx, cy, cz, h, h, h)) {
			curSkipS++;
			if (DEV) {
				devSkipBoxes++;
			}
			return 0;
		}
		if (result == -2 && !insideMap(cx, cy, cz, h)) {
			if (DEV) {
				devSplitBoxes++;
			}
			return -1;
		}
		return result;
	}

	/** Sodium 0.8 createTerrainRenderList RETURN: the walk is over, disarm (the counts come at finalizeRenderLists). */
	public static void walkEnd() {
		armedViewport = null;
	}

	/**
	 * Arm the box tests for a shadow walk on this viewport: Sodium 0.9 renderOutOfGraph HEAD, Sodium 0.8
	 * createTerrainRenderList HEAD.
	 */
	public static void walkBegin(Object viewport) {
		armedViewport = null;
		if (broken || !settingOn()) {
			return;
		}
		try {
			if ((boolean) IS_SHADOW_PASS.invokeExact() && matrices()) {
				shadowFrame = FrameStats.frames();
				rollFrame();
				armedViewport = viewport;
			}
		} catch (Throwable e) {
			fail(e);
		}
	}

	/** True when this entity, which the shadow pass already decided to draw, lies entirely off the map. */
	public static boolean skipEntity(Entity entity, net.minecraft.client.renderer.entity.EntityRenderer<?, ?> renderer,
			double camX, double camY, double camZ) {
		if (broken) {
			return false;
		}
		boolean on = settingOn();
		if (!on && !DEV) {
			return false;
		}
		try {
			if (!(boolean) IS_SHADOW_PASS.invokeExact() || !matrices()) {
				return false;
			}
			AABB bb = ShadowCasterLimit.cullBox(entity, renderer);
			boolean off = offMap((float) ((bb.minX + bb.maxX) * 0.5 - camX), (float) ((bb.minY + bb.maxY) * 0.5 - camY),
					(float) ((bb.minZ + bb.maxZ) * 0.5 - camZ), (float) ((bb.maxX - bb.minX) * 0.5),
					(float) ((bb.maxY - bb.minY) * 0.5), (float) ((bb.maxZ - bb.minZ) * 0.5));
			if (DEV) {
				devSeenE++;
				if (off) {
					devOffE++;
				}
			}
			if (on && off) {
				rollFrame();
				shadowFrame = FrameStats.frames();
				curSkipE++;
				if (DEV) {
					devSkipE++;
				}
				return true;
			}
			return false;
		} catch (Throwable e) {
			fail(e);
			return false;
		}
	}

	/**
	 * renderOutOfGraph RETURN: disarm, and (dev only) count the sections with geometry the shadow walk
	 * put in the render lists and how many of them lie fully off the map (no margin).
	 */
	public static void afterWalk(RenderSectionManager rsm, Viewport viewport) {
		armedViewport = null;
		if (!DEV || broken) {
			return;
		}
		try {
			if (!(boolean) IS_SHADOW_PASS.invokeExact() || !matrices()) {
				return;
			}
			CameraTransform t = viewport.getTransform();
			var lists = ((SodiumSortedRenderListsAccessor) rsm.getRenderLists()).originclient$lists();
			devWalks++;
			for (int i = 0; i < lists.size(); i++) {
				ChunkRenderList list = lists.get(i);
				RenderRegion region = list.getRegion();
				ByteIterator it = list.sectionsWithGeometryIterator(false);
				if (it == null) {
					continue;
				}
				while (it.hasNext()) {
					int idx = it.nextByteAsInt();
					int x = region.getOriginX() + LocalSectionIndex.unpackX(idx) * 16 + 8;
					int y = region.getOriginY() + LocalSectionIndex.unpackY(idx) * 16 + 8;
					int z = region.getOriginZ() + LocalSectionIndex.unpackZ(idx) * 16 + 8;
					devDrawnS++;
					float cx = (x - t.intX) - t.fracX, cy = (y - t.intY) - t.fracY, cz = (z - t.intZ) - t.fracZ;
					float px = ax * cx + ay * cy + az * cz + aw, rx = (aax + aay + aaz) * 8f;
					float py = bx * cx + by * cy + bz * cz + bw, ry = (abx + aby + abz) * 8f;
					if (px - rx > 1f || px + rx < -1f || py - ry > 1f || py + ry < -1f) {
						devOffS++;
					}
				}
			}
		} catch (Throwable e) {
			fail(e);
		}
	}

	private static void fail(Throwable t) {
		armedViewport = null;
		if (!broken) {
			broken = true;
			com.origin.client.OriginClient.LOGGER.warn("[Origin] Off-Map Shadows turned itself off after an error", t);
		}
	}

	/** One F3 line while the option is on and a shader pack is drawing shadows; null otherwise. */
	public static String debugLine() {
		if (!Mods.bool(Mods.PERFORMANCE_ID, KEY_ON)) {
			return null;
		}
		if (broken) {
			return IS_SHADOW_PASS == null || MODELVIEW == null || PROJECTION == null
					? "Origin Off-Map Shadows: off (Iris missing or not supported; shadows draw normally)"
					: "Origin Off-Map Shadows: off after an error (shadows draw normally)";
		}
		long f = FrameStats.frames();
		if (f - shadowFrame > 2) {
			return null;   // no orthographic shadow pass lately (no pack, or the pack sets a shadow fov)
		}
		return "Origin Off-Map Shadows: " + lastSkipS + " chunk checks, " + lastSkipE + " entities skipped (off the shadow map)";
	}
}
