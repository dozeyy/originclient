package com.origin.client.client.render;

import com.origin.client.client.hud.FrameStats;
import com.origin.client.client.mixin.SodiumRenderSectionManagerAccessor;
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
 * SETTINGS > Performance > Off-Map Shadows (1.21.8 port of 1.21.1 via 1.21.6, default OFF).
 *
 * <p>Why it saves work (Iris 1.9.6 / Sodium 0.7.3 +mc1.21.8, read from the jars 2026-10-20): Iris's shadow
 * pass culls with AdvancedShadowCullingFrustum, which is the player's view stretched toward the sun
 * plus, only when the pack sets shadowDistanceRenderMul, a world-axis distance box. Its constructor
 * takes the shadow projection and never uses it, so nothing culls against the shadow map's own
 * left/right/top/bottom edges. With the default render multiplier the shadow walk reaches the Iris
 * "Shadow Distance" video setting (capped by render distance) while the map only covers the pack's
 * shadowDistance (halved again by Shader Performance Mode): every chunk section, entity and block
 * entity in between is drawn into the shadow map and then clipped away by the GPU.
 *
 * <p>The test: the box's light-space x/y range under ShadowRenderer.PROJECTION * MODELVIEW (the exact
 * matrices Iris draws the shadow pass with, camera-relative). A box entirely past one edge is
 * skipped. Only for an orthographic shadow projection (packs that set a shadow fov are left alone).
 *
 * <p>PER-VERSION (0.7.3): Iris forces Sodium's out-of-graph path in the shadow pass, so the shadow walk is
 * Sodium's section-tree traversal, not OcclusionCuller: it tests tree nodes with
 * Viewport.getBoxIntersectionDirect and leaf sections with isBoxVisibleDirect (keepBox / intersectBox
 * below; SodiumViewportShadowMapMixin). keepSection stays for OcclusionCuller's isBoxVisible.
 *
 * <p>What it changes on screen: a caster only shades receivers at its own light-space x/y, so for any
 * ground inside the map the result is the same. A pack that distorts its shadow map (most do) squeezes
 * the area past the edge into a thin outer ring; ground out there that the pack still shades loses
 * shadows from casters past the edge. EDGE keeps a 10% margin for filter kernels near the edge.
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

	// Off for good when this module's compat gates say the installed Sodium/Iris isn't one these hooks
	// were proven on (compat/CompatGates; DEV forcing can't switch it back on).
	private static boolean broken = !com.origin.client.client.compat.CompatGates.on(KEY_ON) || IS_SHADOW_PASS == null || MODELVIEW == null || PROJECTION == null;

	// Light-space x and y rows of PROJECTION * MODELVIEW, rebuilt when Iris installs new matrices (a new
	// MODELVIEW object every shadow frame, ShadowRenderer.renderShadows).
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
	 * True only between the HEAD and RETURN of a shadow-pass walk with the option on and an ortho
	 * projection (Iris sets both matrices before the walk). The per-section hook then pays one static
	 * read in every other walk, and no Iris call per section in this one.
	 */
	private static boolean armed;

	// Per-frame skip counts (last finished frame) for the F3 line. Sections count section CHECKS the
	// shadow walk answered "off the map": Sodium's walk can check one section more than once (from
	// several neighbours, and its nearby-section pass), and empty air sections count too, so this is
	// not the number of drawn sections removed (dev-shots measures that: afterWalk).
	private static long frameId = -1, shadowFrame = Long.MIN_VALUE;
	private static int curSkipS, curSkipE, lastSkipS, lastSkipE;

	// Dev totals: shadow walks, sections with geometry they drew and how many were fully off-map;
	// entities the shadow pass kept, how many were off-map, how many this feature skipped.
	public static long devWalks, devDrawnS, devOffS, devSeenE, devOffE, devSkipE;

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

	/**
	 * Sodium's section frustum test (Viewport.isBoxVisible), already answered "visible": false when
	 * this is the shadow pass and the section box lies entirely off the shadow map.
	 */
	public static boolean keepSection(CameraTransform t, int ox, int oy, int oz, float sx, float sy, float sz) {
		if (!armed) {
			return true;
		}
		if (offMap((ox - t.intX) - t.fracX, (oy - t.intY) - t.fracY, (oz - t.intZ) - t.fracZ, sx, sy, sz)) {
			curSkipS++;
			return false;
		}
		return true;
	}

	/** Section-tree leaf test (Viewport.isBoxVisibleDirect: camera-relative center, half size), already "visible". */
	public static boolean keepBox(float cx, float cy, float cz, float half) {
		if (!armed) {
			return true;
		}
		if (offMap(cx, cy, cz, half, half, half)) {
			curSkipS++;
			return false;
		}
		return true;
	}

	/**
	 * Section-tree node test (Viewport.getBoxIntersectionDirect, JOML codes: -2 inside, -1 intersecting,
	 * 0..5 outside). Off the map = outside (0, the first plane's code: TraversableTree treats any code but
	 * -2 and -1 as outside). Inside the frustum but past an edge = intersecting, so the tree tests the
	 * node's children instead of taking the whole subtree.
	 */
	public static int intersectBox(int result, float cx, float cy, float cz, float half) {
		if (!armed || result >= 0) {
			return result;
		}
		if (offMap(cx, cy, cz, half, half, half)) {
			curSkipS++;
			return 0;
		}
		if (result == -2 && !onMap(cx, cy, cz, half)) {
			return -1;
		}
		return result;
	}

	/** Cube entirely inside the kept area (the map plus EDGE). */
	private static boolean onMap(float cx, float cy, float cz, float half) {
		float x = ax * cx + ay * cy + az * cz + aw;
		float rx = (aax + aay + aaz) * half;
		float y = bx * cx + by * cy + bz * cz + bw;
		float ry = (abx + aby + abz) * half;
		return x - rx >= -EDGE && x + rx <= EDGE && y - ry >= -EDGE && y + ry <= EDGE;
	}

	/** Sodium createTerrainRenderList HEAD (SodiumGraphReuseMixin): arm the box tests for a shadow walk. */
	public static void walkBegin() {
		armed = false;
		if (broken || !settingOn()) {
			return;
		}
		try {
			if ((boolean) IS_SHADOW_PASS.invokeExact() && matrices()) {
				shadowFrame = FrameStats.frames();
				rollFrame();
				armed = true;
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
	 * Dev only: after a shadow-pass walk (createTerrainRenderList RETURN), count the sections with
	 * geometry it put in the shadow render lists and how many of them lie fully off the map (no margin).
	 * PER-VERSION (0.7.3): the walk's region lists are in RenderSectionManager.sectionCollector at that
	 * point (finalizeRenderLists turns them into render lists later); Iris leaves that field unswapped.
	 */
	public static void afterWalk(RenderSectionManager rsm, Viewport viewport) {
		armed = false;
		if (!DEV || broken) {
			return;
		}
		try {
			if (!(boolean) IS_SHADOW_PASS.invokeExact() || !matrices()) {
				return;
			}
			CameraTransform t = viewport.getTransform();
			var collector = ((SodiumRenderSectionManagerAccessor) rsm).originclient$sectionCollector();
			if (collector == null) {
				return;
			}
			var lists = collector.getUnsortedRenderLists();
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
		armed = false;
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
			// PER-VERSION (1.21.6-1.21.8): Iris is a separate install here, so say why the option does nothing.
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
