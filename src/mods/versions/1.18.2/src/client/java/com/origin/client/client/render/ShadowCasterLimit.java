package com.origin.client.client.render;

import com.origin.client.client.hud.FrameStats;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.lang.invoke.MethodHandle;

/**
 * SETTINGS > Performance > Limit Far Shadows (pre-1.20 port of 1.21.1, default OFF).
 *
 * <p>Why it saves work (read from Iris 1.6.11+mc1.18.2 / Sodium 0.4.1+mc1.18.2 bytecode, 2026-10-20):
 * with a shader pack on, Iris's net.coderbot ShadowRenderer draws the world a second time into the
 * shadow map every frame. For entities, renderEntities / renderPlayerEntity walk every loaded entity,
 * keep the ones EntityRenderDispatcher.shouldRender passes for the shadow frustum and rebuild each
 * model's geometry on the CPU. For block entities, renderBlockEntities runs every block entity Sodium
 * found in the shadow walk (ShadowRenderer.visibleBlockEntities) through BlockEntityRenderDispatcher.render.
 * That shadow frustum is the player's view stretched toward the sun with no occlusion culling, so far
 * mobs, chests, signs and banners are rebuilt every frame for a few shadow-map texels.
 *
 * <p>What it changes on screen: only things farther than the distance the player picks stop casting
 * shadows (they still draw and still receive shadows). Anything closer is untouched. At sunset a long
 * shadow of a far caster can reach a little inside the circle; that is the one visible edge.
 *
 * <p>Safety (mandate 4): both hooks are plain vanilla dispatcher calls (refmapped), and they only ever
 * answer "skip" while Iris reports it is drawing the shadow pass. Iris is reached through its public
 * v0 API by reflection (IrisState.SHADOW_PASS, bound once), so a missing or changed Iris turns the
 * feature off instead of failing to load. Any exception latches the feature off and is logged once.
 */
public final class ShadowCasterLimit {
	private ShadowCasterLimit() {
	}

	public static final String KEY_ON = "farShadowLimit";
	public static final String KEY_DISTANCE = "farShadowDistance";

	/** Dev-only counting with the limit OFF too (CI measurement). False for players: the branch folds away. */
	private static final boolean DEV = System.getenv("ORIGIN_SHOTS") != null;

	/** IrisApi.getInstance().isRenderingShadowPass(), bound; null when Iris (or its v0 API) is absent. */
	private static final MethodHandle IS_SHADOW_PASS = IrisState.SHADOW_PASS;

	private static boolean broken;

	// Per-frame counts (last finished frame) for the F3 line, and running totals for dev measurement.
	private static long frameId = -1;
	private static int curSeenE, curSkipE, curSeenBE, curSkipBE;
	private static int lastSeenE, lastSkipE, lastSeenBE, lastSkipBE;
	public static long totalSeenE, totalSkipE, totalSeenBE, totalSkipBE;

	private static boolean enabled() {
		return !broken && IS_SHADOW_PASS != null && Mods.bool(Mods.PERFORMANCE_ID, KEY_ON);
	}

	private static boolean inShadowPass() throws Throwable {
		return (boolean) IS_SHADOW_PASS.invokeExact();
	}

	private static void rollFrame() {
		long f = FrameStats.frames();
		if (f != frameId) {
			frameId = f;
			lastSeenE = curSeenE;
			lastSkipE = curSkipE;
			lastSeenBE = curSeenBE;
			lastSkipBE = curSkipBE;
			curSeenE = curSkipE = curSeenBE = curSkipBE = 0;
		}
	}

	// The toggle and distance, read from Mods once per frame: shouldRender runs for every entity in
	// the MAIN pass too, which should pay one long compare here, not two map lookups.
	private static long settingsFrame = Long.MIN_VALUE;
	private static boolean cachedOn;
	private static double cachedLimitSq;

	private static boolean settingOn() {
		long f = FrameStats.frames();
		if (f != settingsFrame) {
			settingsFrame = f;
			cachedOn = Mods.bool(Mods.PERFORMANCE_ID, KEY_ON);
			double d = Mods.num(Mods.PERFORMANCE_ID, KEY_DISTANCE);
			cachedLimitSq = d * d;
		}
		return cachedOn;
	}

	/** Squared distance from (x,y,z) to the nearest point of the box: big things count by their near edge. */
	private static double distSq(double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
			double x, double y, double z) {
		double dx = Math.max(Math.max(minX - x, x - maxX), 0);
		double dy = Math.max(Math.max(minY - y, y - maxY), 0);
		double dz = Math.max(Math.max(minZ - z, z - maxZ), 0);
		return dx * dx + dy * dy + dz * dz;
	}

	/**
	 * True when this entity, which vanilla already decided to draw, must be left out of the
	 * shadow map. Called from EntityRenderDispatcher.shouldRender (ShadowCasterEntityMixin).
	 */
	public static boolean skipEntity(Entity entity, double camX, double camY, double camZ) {
		if (broken || IS_SHADOW_PASS == null) {
			return false;
		}
		boolean on = settingOn();
		if (!on && !DEV) {
			return false;
		}
		try {
			if (!inShadowPass()) {
				return false;
			}
			rollFrame();
			curSeenE++;
			totalSeenE++;
			if (!on) {
				return false;
			}
			AABB bb = cullBox(entity);
			if (distSq(bb.minX, bb.minY, bb.minZ, bb.maxX, bb.maxY, bb.maxZ, camX, camY, camZ) > cachedLimitSq) {
				curSkipE++;
				totalSkipE++;
				return true;
			}
			return false;
		} catch (Throwable t) {
			fail(t);
			return false;
		}
	}

	/**
	 * True when this block entity must be left out of the shadow map. Called at the head of
	 * BlockEntityRenderDispatcher.render (ShadowCasterBlockEntityMixin), before any geometry.
	 */
	public static boolean skipBlockEntity(BlockEntity be) {
		if (broken || IS_SHADOW_PASS == null) {
			return false;
		}
		boolean on = settingOn();
		if (!on && !DEV) {
			return false;
		}
		try {
			if (!inShadowPass()) {
				return false;
			}
			rollFrame();
			curSeenBE++;
			totalSeenBE++;
			if (!on) {
				return false;
			}
			// The shadow pass camera is the player's camera (Iris renderShadows reads playerCamera.getPosition()).
			Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
			BlockPos p = be.getBlockPos();
			if (distSq(p.getX(), p.getY(), p.getZ(), p.getX() + 1, p.getY() + 1, p.getZ() + 1,
					cam.x, cam.y, cam.z) > cachedLimitSq) {
				curSkipBE++;
				totalSkipBE++;
				return true;
			}
			return false;
		} catch (Throwable t) {
			fail(t);
			return false;
		}
	}

	/** The entity's culling box: the box vanilla's own frustum test uses (Entity.getBoundingBoxForCulling before 1.21.4). */
	public static AABB cullBox(Entity entity) {
		return entity.getBoundingBoxForCulling();
	}

	private static void fail(Throwable t) {
		if (!broken) {
			broken = true;
			com.origin.client.OriginClient.LOGGER.warn("[Origin] Limit Far Shadows turned itself off after an error", t);
		}
	}

	/** One F3 line while the limit is on and a shader pack is drawing shadows; null otherwise. */
	public static String debugLine() {
		if (!enabled()) {
			// Iris is a separate install on this version, so say why the option does nothing.
			if ((broken || IS_SHADOW_PASS == null) && Mods.bool(Mods.PERFORMANCE_ID, KEY_ON)) {
				return IS_SHADOW_PASS == null
						? "Origin Far Shadows: off (Iris missing or not supported; shadows draw normally)"
						: "Origin Far Shadows: off after an error (shadows draw normally)";
			}
			return null;
		}
		// Counts are from the last frame that drew a shadow pass; stale (no pass for a while) = no line.
		if (FrameStats.frames() - frameId > 2) {
			return null;
		}
		return "Origin Far Shadows: skipped " + lastSkipE + "/" + lastSeenE + " entities, "
				+ lastSkipBE + "/" + lastSeenBE + " block entities";
	}
}
