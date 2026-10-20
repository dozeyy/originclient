package com.origin.client.client.render;

import com.origin.client.client.hud.FrameStats;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
// Before 1.19.3 Iris's MODELVIEW is Mojang's own matrix (com.mojang.math), not JOML; its fields are protected.
import com.mojang.math.Matrix4f;
import com.mojang.math.Vector4f;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * SETTINGS > Performance > Off-Map Shadows (pre-1.20 port of 1.21.1, default OFF).
 *
 * <p>Why it saves work (Iris 1.6.11+mc1.18.2 / Sodium 0.4.1+mc1.18.2, read from the jars 2026-10-20): Iris's
 * shadow pass culls with AdvancedShadowCullingFrustum (the player's view stretched toward the sun) plus,
 * only when the pack sets shadowDistanceRenderMul, a world-axis distance box. Nothing culls against the
 * shadow map's own left/right/top/bottom edges, so every chunk section, entity and block entity between
 * the pack's shadowDistance (the map's half width) and the shadow walk's reach is drawn into the shadow
 * map and then clipped away by the GPU.
 *
 * <p>The test: the box's light-space x/y range under ortho(halfPlaneLength) * MODELVIEW, camera-relative.
 * PER-VERSION (pre-1.20): this Iris has no static PROJECTION field. ShadowRenderer.renderShadows sets
 * MODELVIEW (offset 101) before the shadow walk (invokeSetupRender, offset 271) and only builds the
 * projection after it (offset 336: fov != null ? createPerspectiveMatrix(fov) :
 * ShadowMatrices.createOrthoMatrix(halfPlaneLength), whose x/y scale is 1/halfPlaneLength with no
 * x/y offset). So the x/y rows are MODELVIEW's rows 0 and 1 divided by the instance's halfPlaneLength,
 * read at renderShadows HEAD (onShadowPass). A pack with a shadow fov is left alone.
 *
 * <p>Sodium 0.4 hooks (SodiumShadowMapCullMixin): a whole 8x4x8-section region the shadow frustum
 * called visible but that lies off the map becomes OUTSIDE (RenderRegion.updateVisibility), a region the
 * frustum called INSIDE that crosses the map edge becomes INTERSECT so its sections get tested, and each
 * tested section (ChunkGraphInfo.isCulledByFrustum) off the map is culled.
 *
 * <p>What it changes on screen: a caster only shades receivers at its own light-space x/y, so for any
 * ground inside the map the result is the same. A pack that distorts its shadow map squeezes the area past
 * the edge into a thin outer ring; ground out there loses shadows from casters past the edge. EDGE keeps a
 * 10% margin for filter kernels near the edge.
 *
 * <p>Safety (mandate 4): Iris is reached by reflection (bound once; missing or changed Iris = feature off).
 * The hooks only ever answer "skip" inside Iris's shadow pass. Any exception latches the feature off and is
 * logged once. No allocation per frame.
 */
public final class OffMapShadows {
	private OffMapShadows() {
	}

	public static final String KEY_ON = "offMapShadows";

	/** Dev-only counting with the option OFF too (CI measurement). False for players. */
	private static final boolean DEV = System.getenv("ORIGIN_SHOTS") != null;

	/** Keep anything within 10% past the edge (in map units, 1 = the edge). */
	private static final float EDGE = 1.1f;

	private static final String SR = "net.coderbot.iris.pipeline.ShadowRenderer";
	private static final MethodHandle IS_SHADOW_PASS = IrisState.SHADOW_PASS;
	/** ShadowRenderer: public static com.mojang.math.Matrix4f MODELVIEW (a new object on every real draw). */
	private static final MethodHandle MODELVIEW = getter("MODELVIEW", true, Object.class);
	/** ShadowRenderer: private final float halfPlaneLength (the map's half width in blocks). */
	private static final MethodHandle HALF_PLANE = getter("halfPlaneLength", false, float.class);
	/** ShadowRenderer: private final Float fov (non-null = perspective shadow projection). */
	private static final MethodHandle FOV = getter("fov", false, Object.class);

	private static MethodHandle getter(String field, boolean isStatic, Class<?> type) {
		try {
			java.lang.reflect.Field f = Class.forName(SR).getDeclaredField(field);
			f.setAccessible(true);
			MethodHandle h = MethodHandles.lookup().unreflectGetter(f);
			return h.asType(isStatic ? MethodType.methodType(type) : MethodType.methodType(type, Object.class));
		} catch (Throwable t) {
			return null;
		}
	}

	private static final boolean MISSING = IS_SHADOW_PASS == null || MODELVIEW == null || HALF_PLANE == null || FOV == null;
	private static boolean broken = MISSING;

	// The current shadow renderer's projection, read at its renderShadows HEAD this frame.
	private static long passFrame = Long.MIN_VALUE;
	private static boolean passOrtho;
	private static float passHalfPlane;

	// Light-space x and y rows, rebuilt when Iris installs a new MODELVIEW or the half plane changes.
	private static Matrix4f lastMv;
	private static final Vector4f COL = new Vector4f();
	private static float lastHalf;
	private static float ax, ay, az, aw, bx, by, bz, bw, aax, aay, aaz, abx, aby, abz;
	private static float halfPlane;

	/** Dev-shots: the shadow map's half width in blocks, from the last shadow pass seen. */
	public static float devHalfPlane() {
		return halfPlane;
	}

	private static long settingsFrame = Long.MIN_VALUE;
	private static boolean cachedOn;

	/** True only between the HEAD and RETURN of a shadow-pass walk with the option on and an ortho map. */
	private static boolean armed;
	/** Dev: a shadow-pass walk with known matrices is running (whatever the toggle). */
	private static boolean devWalk;
	/** Camera position for this walk (the shadow pass is camera-relative). */
	private static double camX, camY, camZ;

	// Per-frame skip counts (last finished frame) for the F3 line. Checks count section and region tests
	// the shadow walk answered "off the map" (a region test covers up to 256 sections at once), so this
	// is not the number of drawn sections removed (dev-shots measures that: devSection).
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

	/**
	 * Iris ShadowRenderer.renderShadows HEAD (IrisShadowRendererHalfRateMixin): read this renderer's
	 * projection (two field reads per frame, no allocation).
	 */
	public static void onShadowPass(Object shadowRenderer) {
		if (broken || (!DEV && !settingOn())) {
			return;
		}
		try {
			float half = (float) HALF_PLANE.invokeExact(shadowRenderer);
			Object fov = (Object) FOV.invokeExact(shadowRenderer);
			passOrtho = fov == null && half > 0f;
			passHalfPlane = half;
			passFrame = FrameStats.frames();
		} catch (Throwable t) {
			fail(t);
		}
	}

	/** True when this frame's shadow matrices are known and orthographic. */
	private static boolean matrices() throws Throwable {
		if (passFrame != FrameStats.frames() || !passOrtho) {
			return false;
		}
		Matrix4f mv = (Matrix4f) (Object) MODELVIEW.invokeExact();
		if (mv == null) {
			return false;
		}
		if (mv != lastMv || passHalfPlane != lastHalf) {
			lastMv = mv;
			lastHalf = passHalfPlane;
			float s = 1f / passHalfPlane;
			// Mojang's matrix fields are protected: read rows 0 and 1 off the transformed basis vectors
			// (column j = M * e_j). Runs only when Iris installs a new MODELVIEW, not per test.
			COL.set(1f, 0f, 0f, 0f);
			COL.transform(mv);
			ax = COL.x() * s; bx = COL.y() * s;
			COL.set(0f, 1f, 0f, 0f);
			COL.transform(mv);
			ay = COL.x() * s; by = COL.y() * s;
			COL.set(0f, 0f, 1f, 0f);
			COL.transform(mv);
			az = COL.x() * s; bz = COL.y() * s;
			COL.set(0f, 0f, 0f, 1f);
			COL.transform(mv);
			aw = COL.x() * s; bw = COL.y() * s;
			aax = Math.abs(ax); aay = Math.abs(ay); aaz = Math.abs(az);
			abx = Math.abs(bx); aby = Math.abs(by); abz = Math.abs(bz);
			halfPlane = passHalfPlane;
		}
		return true;
	}

	/** Box (camera-relative center, half extents) entirely past one edge of the shadow map (with margin). */
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

	/** Box entirely within the map (with margin): its sections need no test of their own. */
	private static boolean onMap(float cx, float cy, float cz, float ex, float ey, float ez) {
		float x = ax * cx + ay * cy + az * cz + aw;
		float rx = aax * ex + aay * ey + aaz * ez;
		float y = bx * cx + by * cy + bz * cz + bw;
		float ry = abx * ex + aby * ey + abz * ez;
		return x + rx <= EDGE && x - rx >= -EDGE && y + ry <= EDGE && y - ry >= -EDGE;
	}

	/**
	 * Sodium RenderSectionManager.update HEAD (SodiumShadowWalkMixin): arm the tests for a shadow walk.
	 * Iris has already set this frame's MODELVIEW (renderShadows offset 101, before the walk).
	 */
	public static void walkBegin() {
		armed = false;
		devWalk = false;
		if (broken) {
			return;
		}
		boolean on = settingOn();
		if (!on && !DEV) {
			return;
		}
		try {
			if ((boolean) IS_SHADOW_PASS.invokeExact() && matrices()) {
				Vec3 c = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
				camX = c.x;
				camY = c.y;
				camZ = c.z;
				if (DEV) {
					devWalk = true;
					devWalks++;
				}
				if (on) {
					shadowFrame = FrameStats.frames();
					rollFrame();
					armed = true;
				}
			}
		} catch (Throwable e) {
			fail(e);
		}
	}

	/** Sodium RenderSectionManager.update RETURN: the walk is over. */
	public static void walkEnd() {
		armed = false;
		devWalk = false;
	}

	/**
	 * Sodium ChunkGraphInfo.isCulledByFrustum, already answered "not culled": false when this is a
	 * shadow walk and the 16-block section at this origin lies entirely off the shadow map.
	 */
	public static boolean keepSection(int ox, int oy, int oz) {
		if (!armed) {
			return true;
		}
		if (offMap((float) (ox + 8 - camX), (float) (oy + 8 - camY), (float) (oz + 8 - camZ), 8f, 8f, 8f)) {
			curSkipS++;
			return false;
		}
		return true;
	}

	/**
	 * Sodium RenderRegion.updateVisibility (region origin = its min corner; Sodium tests the box
	 * origin-8 .. origin+136 / +72 / +136). 0 = leave the frustum's answer, 1 = off the map (OUTSIDE),
	 * 2 = crosses the map edge (an INSIDE answer must become INTERSECT so its sections get tested).
	 */
	public static int classifyRegion(int ox, int oy, int oz) {
		if (!armed) {
			return 0;
		}
		float cx = (float) (ox + 64 - camX), cy = (float) (oy + 32 - camY), cz = (float) (oz + 64 - camZ);
		if (offMap(cx, cy, cz, 72f, 40f, 72f)) {
			curSkipS++;
			return 1;
		}
		return onMap(cx, cy, cz, 72f, 40f, 72f) ? 0 : 2;
	}

	/** Dev only: a section with geometry the shadow walk added to its render list (SodiumShadowWalkDevMixin). */
	public static void devSection(int ox, int oy, int oz) {
		if (!devWalk) {
			return;
		}
		devDrawnS++;
		float cx = (float) (ox + 8 - camX), cy = (float) (oy + 8 - camY), cz = (float) (oz + 8 - camZ);
		float px = ax * cx + ay * cy + az * cz + aw, rx = (aax + aay + aaz) * 8f;
		float py = bx * cx + by * cy + bz * cz + bw, ry = (abx + aby + abz) * 8f;
		if (px - rx > 1f || px + rx < -1f || py - ry > 1f || py + ry < -1f) {
			devOffS++;
		}
	}

	/** True when this entity, which the shadow pass already decided to draw, lies entirely off the map. */
	public static boolean skipEntity(Entity entity, double cX, double cY, double cZ) {
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
			AABB bb = ShadowCasterLimit.cullBox(entity);
			boolean off = offMap((float) ((bb.minX + bb.maxX) * 0.5 - cX), (float) ((bb.minY + bb.maxY) * 0.5 - cY),
					(float) ((bb.minZ + bb.maxZ) * 0.5 - cZ), (float) ((bb.maxX - bb.minX) * 0.5),
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

	private static void fail(Throwable t) {
		armed = false;
		devWalk = false;
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
			// Iris is a separate install on this version, so say why the option does nothing.
			return MISSING
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
