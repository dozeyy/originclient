package com.origin.client.client.render;

import com.origin.client.client.hud.FrameStats;
import com.origin.client.client.mixin.SodiumRenderSectionManagerAccessor;
import com.origin.client.client.mods.Mods;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.SectionCollector;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;

import java.lang.invoke.MethodHandle;
import java.util.ArrayDeque;

/**
 * SETTINGS > Performance > Smart Terrain Updates (1.21.11 port of 1.21.1, default OFF). Shader packs only.
 * Runs only on Sodium 0.8.12 + Iris 1.10.7 or 0.8.14 + 1.10.8 (SodiumIrisBuilds.SMART_TERRAIN; Mods hides the toggle otherwise).
 *
 * <p>What it saves (read from Sodium 0.8.12+mc1.21.11 / Iris 1.10.7+mc1.21.11 bytecode, 2026-10-20): Sodium
 * walks its chunk graph (which sections are visible) only when something changed. With a shader pack,
 * Iris's shadow pass runs SodiumWorldRenderer.setupTerrain with the shadow matrices, so the main pass's
 * setupTerrain always sees a "changed" projection (lastProjectionMatrix is shared) and calls
 * markGraphDirty; Iris's ShadowRenderer also calls LevelRenderer.needsUpdate() every frame. So the MAIN
 * pass redoes the whole walk every frame, even standing still. This skips the main walk when it would
 * produce exactly the last result.
 *
 * <p>Reuse is allowed only when ALL of this holds since the last real main walk (any doubt = walk):
 * <ul>
 * <li>Sodium 0.8.12 + Iris 1.10.7, or Sodium 0.8.14 + Iris 1.10.8 (the internals below were read from those builds);</li>
 * <li>same RenderSectionManager (a render-distance change, shader reload or dimension change makes
 *     a new one) and a shader pack in use;</li>
 * <li>camera: same position, pitch, yaw, rotation, ChunkRenderMatrices projection + modelView, Sodium
 *     fog parameters, smartCull and spectator flag (Sodium's own camera checks plus more), and the same
 *     culling frustum (LevelRenderer.prepareCullFrustum's position + both matrices, bit-exact);</li>
 * <li>no graph change: no section added/removed, no rebuild or sort that changed a pending update, no
 *     chunk build result uploaded, no markGraphDirty outside the shadow pass except Sodium's own
 *     camera-change call (covered by the camera checks above), not an "update chunks immediately" frame;</li>
 * <li>Sodium's rebuild queues are empty, and the last real walk queued nothing (a queue is capped, so a
 *     walk that queued work is followed by walks until one finds nothing left).</li>
 * </ul>
 *
 * <p>Where it decides (PER-VERSION, 1.21.11): RenderSectionManager.update() does lastUpdatedFrame++ and
 * then needsGraphUpdate = createTerrainRenderList(...). The decision is at createTerrainRenderList HEAD
 * (main pass only), which answers false (= "no revisit needed", what a quiet walk returns) without
 * walking. What a real main walk leaves behind, and why a reused frame matches it:
 * <ul>
 * <li><b>Render lists.</b> Iris 1.10.7 keeps the shadow walk's output apart from the main walk's:
 *     MixinRenderSectionManagerShadow sends the shadow pass's renderLists / taskLists to its own fields,
 *     and swaps every RenderRegion to a second ChunkRenderList + batch cache around the shadow walk. So
 *     unlike 1.21.1-1.21.4 (one region list shared by both walks, snapshot + restore needed), the main
 *     renderLists, taskLists and region lists still hold the last main walk's output; nothing is copied.
 *     finalizeRenderLists only rebuilds renderLists when a walk set sectionCollector, so it keeps them.</li>
 * <li><b>Collector for mid-frame builds.</b> An "immediate presentation" build result is added to
 *     sectionCollector, or lastSectionCollector between walks. After the shadow walk that is the
 *     shadow walk's collector, so a reused frame points lastSectionCollector back at the last main
 *     walk's collector -- exactly what Sodium itself does on a frame without a walk.</li>
 * <li><b>Section stamps.</b> The walk stamps every section it reaches with lastUpdatedFrame; main-pass
 *     entity culling (SodiumWorldRenderer.isEntityVisible -> isSectionVisible) draws an entity only in a
 *     section stamped with the current number. The counter is shared with the shadow walk's update()
 *     (Iris's MixinRenderSection keeps the shadow stamps in a separate field, and the shadow walk takes
 *     Sodium's tree path, which does not stamp), so the main stamps are one or two frames old. A reused
 *     frame stamps, with this update's number, exactly the sections the last real main walk stamped
 *     (recorded at OcclusionCuller's three setLastVisibleFrame calls). Entity culling then gives the same
 *     answer for every entity as with the feature off. On 1.21.11 the main entity extraction runs after
 *     the main walk in the same frame (LevelRenderer.renderLevel: cullTerrain, then
 *     extractVisibleEntities), so it sees this frame's stamps.</li>
 * </ul>
 *
 * <p>Safety (mandate 4): every hook is try/catch; any error latches the feature off for the session
 * and logs once. With the toggle off the hooks return after one cached boolean read.
 */
public final class SmartTerrainUpdates {
	private SmartTerrainUpdates() {
	}

	public static final String KEY_ON = "smartTerrainUpdates";

	/** Dev-only measurement with the toggle OFF too (CI). False for players: the branches fold away. */
	private static final boolean DEV = System.getenv("ORIGIN_SHOTS") != null;

	private static final MethodHandle SHADOW_PASS = IrisState.SHADOW_PASS;
	private static final MethodHandle PACK_IN_USE = IrisState.PACK_IN_USE;

	/** The exact builds whose internals this was written against; anything else = feature off. */
	private static final boolean VERSIONS_OK = SodiumIrisBuilds.SMART_TERRAIN;

	private static boolean broken;

	// ---- reuse state ----
	/** Something that can change the walk's result happened since the last real main walk. */
	private static boolean dirty = true;
	/** The RenderSectionManager the held walk belongs to. */
	private static Object manager;
	/** The last real main walk is held (its stamps recorded, its collector kept). */
	private static boolean primed;
	/** The last real main walk's SectionCollector (lastSectionCollector on a reused frame). */
	private static SectionCollector heldCollector;
	/** Inside Sodium's own camera-change markGraphDirty call in setupTerrain. */
	private static boolean cameraMark;
	/** This update()'s walk was reused: its RETURN hook must not prime from it. */
	private static boolean reusedThisUpdate;

	// ---- sections the last real main walk stamped (entity culling reads the stamps) ----
	private static final RenderSection[] NO_SECTIONS = {};
	private static RenderSection[] stamped = NO_SECTIONS;
	private static int stampN;
	/** Inside a main-pass walk with the toggle on: OcclusionCuller's stamps are recorded. */
	private static boolean recording;

	// ---- camera fingerprint (main pass, previous frame) ----
	private static boolean haveCam;
	private static double camX, camY, camZ;
	private static float xRot, yRot;
	private static float fogRenderEnd, fogEnvEnd, fogCull, fogAlpha, fogRenderStart, fogEnvStart;
	private static final Quaternionf ROT = new Quaternionf();
	private static final Matrix4f PROJ = new Matrix4f();
	private static final Matrix4f MODEL_VIEW = new Matrix4f();
	private static boolean smartCull, spectator;

	// ---- cull frustum fingerprint (prepareCullFrustum, previous frame) ----
	private static boolean haveCull;
	private static double cullX, cullY, cullZ;
	private static final Matrix4f CULL_A = new Matrix4f();
	private static final Matrix4f CULL_B = new Matrix4f();

	// ---- counters (render thread only) ----
	public static long totalMainWalks, totalReused, totalMainNanos, totalShadowWalks, totalShadowNanos, totalRestoreNanos;
	private static long walkStart;
	private static long secStart, secWalks, secReused, secMainNanos, secShadowWalks, secShadowNanos;
	private static long lastWalks, lastReused, lastMainNanos, lastShadowWalks, lastShadowNanos, lastSecAt;
	/** Dev proof that each graph-change hook is live: one bit per hook, set the first time it fires. */
	public static int hooksSeen;
	public static final String[] HOOK_NAMES = {"sectionAdded", "sectionRemoved", "scheduleRebuild", "scheduleSort",
			"buildResults", "markGraphDirty", "cameraMark", "cullFrustum", "setupTerrain",
			"stampVisit", "stampNearby", "stampInit"};
	/**
	 * Hooks that fire during any world load (chunks added, built and re-meshed as neighbours arrive, Iris's
	 * per-frame mark, the camera hooks) and in every main walk with the toggle on (the visitNode and
	 * initWithinWorld stamps): reuse waits until all of them have fired once, so a mixin that failed to
	 * apply (the config is defaultRequire 0) turns the feature off instead of letting a change go unseen.
	 * stampNearby is not required: addNearbySections stamps nothing when the walk already reached its
	 * sections. scheduleSort is not required: a still world never re-sorts.
	 */
	private static final int REQUIRED_HOOKS = (1 << 0) | (1 << 2) | (1 << 4) | (1 << 5) | (1 << 7) | (1 << 8)
			| (1 << 9) | (1 << 11);

	// ---- settings, cached once per frame ----
	private static long settingsFrame = Long.MIN_VALUE;
	private static boolean cachedOn;

	private static boolean settingOn() {
		long f = FrameStats.frames();
		if (f != settingsFrame) {
			settingsFrame = f;
			cachedOn = Mods.bool(Mods.PERFORMANCE_ID, KEY_ON);
		}
		return cachedOn;
	}

	/** Hooks run at all: toggle on (or a dev run measuring), right builds, no earlier error. */
	private static boolean live() {
		return (settingOn() || DEV) && VERSIONS_OK && !broken && SHADOW_PASS != null;
	}

	private static boolean shadowPass() throws Throwable {
		return (boolean) SHADOW_PASS.invokeExact();
	}

	private static void fail(Throwable t) {
		if (!broken) {
			broken = true;
			release();
			com.origin.client.OriginClient.LOGGER.warn("[Origin] Smart Terrain Updates turned itself off after an error", t);
		}
	}

	// Dev diagnosis: what set the dirty flag, why a main update walked, which camera part moved.
	private static final String[] DIRTY_NAMES = {"sectionAdded", "sectionRemoved", "scheduleRebuild", "scheduleSort",
			"buildResults", "markGraphDirty", "camera", "cullFrustum", "immediate", "tasksQueued", "captured"};
	private static final String[] WALK_NAMES = {"off", "notPrimed", "dirty", "newManager", "hooksMissing", "noPack",
			"tasksPending", "collectorSet"};
	private static final String[] CAM_NAMES = {"pos", "angle", "rotation", "matrices", "fog", "cullOrSpectator"};
	public static final long[] dirtyWhy = new long[DIRTY_NAMES.length];
	public static final long[] walkWhy = new long[WALK_NAMES.length];
	public static final long[] camWhy = new long[CAM_NAMES.length];

	private static void markDirty(int src) {
		dirty = true;
		if (DEV) {
			dirtyWhy[src]++;
		}
	}

	/** Dev log: counts since boot of every dirty source, walk reason and camera change. */
	public static String whyLine() {
		return "dirtyBy{" + join(DIRTY_NAMES, dirtyWhy) + "} walkedBecause{" + join(WALK_NAMES, walkWhy)
				+ "} cameraChanged{" + join(CAM_NAMES, camWhy) + "}";
	}

	public static void resetWhy() {
		java.util.Arrays.fill(dirtyWhy, 0);
		java.util.Arrays.fill(walkWhy, 0);
		java.util.Arrays.fill(camWhy, 0);
	}

	private static String join(String[] n, long[] v) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < n.length; i++) {
			if (v[i] != 0) {
				sb.append(sb.length() == 0 ? "" : " ").append(n[i]).append('=').append(v[i]);
			}
		}
		return sb.toString();
	}

	private static void seen(int hook) {
		hooksSeen |= 1 << hook;
	}

	// ======================= graph-change hooks (any pass) =======================

	/** onSectionAdded / onSectionRemoved / scheduleRebuild / scheduleSort / processChunkBuildResults. */
	public static void graphChanged(int hook) {
		markDirty(hook);
		seen(hook);
	}

	/** RenderSectionManager.markGraphDirty: a change unless it is Iris's forced shadow-pass call or Sodium's camera call. */
	public static void onMarkGraphDirty() {
		seen(5);
		if (cameraMark) {
			return;   // Sodium's camera-change mark; the camera fingerprint decides instead
		}
		try {
			if (SHADOW_PASS != null && shadowPass()) {
				return;   // Iris's per-frame LevelRenderer.needsUpdate() from ShadowRenderer.renderShadows
			}
		} catch (Throwable t) {
			fail(t);
		}
		markDirty(5);
	}

	public static void cameraMark(boolean inside) {
		cameraMark = inside;
		if (inside) {
			seen(6);
		}
	}

	// ======================= camera / frustum fingerprint =======================

	/** LevelRenderer.prepareCullFrustum (main frame only; renderLevel calls it once per frame). */
	public static void onCullFrustum(Matrix4f a, Matrix4f b, Vec3 pos, boolean captured) {
		if (!live()) {
			haveCull = false;
			return;
		}
		try {
			if (shadowPass()) {
				return;   // not the main frame's frustum
			}
			seen(7);
			boolean same = haveCull && pos.x == cullX && pos.y == cullY && pos.z == cullZ
					&& CULL_A.equals(a) && CULL_B.equals(b);
			if (!same || captured) {
				markDirty(captured ? 10 : 7);
			}
			cullX = pos.x;
			cullY = pos.y;
			cullZ = pos.z;
			CULL_A.set(a);
			CULL_B.set(b);
			haveCull = true;
		} catch (Throwable t) {
			fail(t);
		}
	}

	/** SodiumWorldRenderer.setupTerrain HEAD: the main pass's camera state against the previous frame's. */
	public static void onSetupTerrain(Camera camera, FogParameters fog, boolean spectatorNow, boolean immediate,
			ChunkRenderMatrices matrices) {
		if (!live()) {
			haveCam = false;
			return;
		}
		try {
			if (shadowPass()) {
				return;   // the shadow pass's own setupTerrain: the shadow camera, not a main frame
			}
			seen(8);
			Vec3 p = camera.position();
			Quaternionf r = camera.rotation();
			// PER-VERSION (1.21.11): Sodium 0.8 hands setupTerrain the matrices it compares
			// (ChunkRenderMatrices.projection vs lastProjectionMatrix) and its own FogParameters record, whose
			// cullDistance it compares and whose end/alpha feed the walk's search distance. All fingerprinted;
			// records read in place, no allocation.
			Matrix4fc proj = matrices == null ? null : matrices.projection();
			Matrix4fc mv = matrices == null ? null : matrices.modelView();
			float rEnd = fog == null ? Float.NaN : fog.renderEnd();
			float eEnd = fog == null ? Float.NaN : fog.environmentalEnd();
			float cull = fog == null ? Float.NaN : fog.cullDistance();
			float alpha = fog == null ? Float.NaN : fog.alpha();
			float rStart = fog == null ? Float.NaN : fog.renderStart();
			float eStart = fog == null ? Float.NaN : fog.environmentalStart();
			boolean smart = Minecraft.getInstance().smartCull;
			// NaN never equals itself: a missing fog record always counts as changed.
			boolean sameFog = rEnd == fogRenderEnd && eEnd == fogEnvEnd && cull == fogCull && alpha == fogAlpha
					&& rStart == fogRenderStart && eStart == fogEnvStart;
			boolean sameMatrices = proj != null && mv != null && PROJ.equals(proj) && MODEL_VIEW.equals(mv);
			boolean same = haveCam && p.x == camX && p.y == camY && p.z == camZ
					&& camera.xRot() == xRot && camera.yRot() == yRot
					&& r.equals(ROT) && sameMatrices && sameFog
					&& smart == smartCull && spectatorNow == spectator;
			if (!same || immediate) {
				markDirty(immediate ? 8 : 6);
				if (DEV && haveCam) {
					// Which part of the camera moved (dev diagnosis of reuse misses).
					if (p.x != camX || p.y != camY || p.z != camZ) camWhy[0]++;
					if (camera.xRot() != xRot || camera.yRot() != yRot) camWhy[1]++;
					if (!r.equals(ROT)) camWhy[2]++;
					if (!sameMatrices) camWhy[3]++;
					if (!sameFog) camWhy[4]++;
					if (smart != smartCull || spectatorNow != spectator) camWhy[5]++;
				}
			}
			camX = p.x;
			camY = p.y;
			camZ = p.z;
			xRot = camera.xRot();
			yRot = camera.yRot();
			ROT.set(r);
			if (proj != null && mv != null) {
				PROJ.set(proj);
				MODEL_VIEW.set(mv);
			}
			fogRenderEnd = rEnd;
			fogEnvEnd = eEnd;
			fogCull = cull;
			fogAlpha = alpha;
			fogRenderStart = rStart;
			fogEnvStart = eStart;
			smartCull = smart;
			spectator = spectatorNow;
			haveCam = proj != null && mv != null;
		} catch (Throwable t) {
			fail(t);
		}
	}

	// ======================= the walk =======================

	/**
	 * RenderSectionManager.createTerrainRenderList HEAD. True = skip this main walk (the caller returns
	 * false from it); the shadow pass's walk is never touched. update() has already bumped lastUpdatedFrame.
	 */
	public static boolean beforeWalk(RenderSectionManager rsm) {
		reusedThisUpdate = false;
		if (!live()) {
			releaseIfHeld();
			return false;
		}
		try {
			if (shadowPass()) {
				return false;
			}
			rollSecond();
			if (DEV) {
				entityFrameDone();
			}
			boolean on = settingOn();
			SodiumRenderSectionManagerAccessor m = (SodiumRenderSectionManagerAccessor) rsm;
			boolean collectorFree = m.originclient$sectionCollector() == null;
			if (DEV) {
				int w = !on ? 0 : !primed ? 1 : dirty ? 2 : rsm != manager ? 3
						: (hooksSeen & REQUIRED_HOOKS) != REQUIRED_HOOKS ? 4 : !packInUse() ? 5
						: !tasksEmpty(rsm) ? 6 : !collectorFree ? 7 : -1;
				if (w >= 0) {
					walkWhy[w]++;
				}
			}
			if (on && primed && !dirty && rsm == manager && (hooksSeen & REQUIRED_HOOKS) == REQUIRED_HOOKS
					&& packInUse() && tasksEmpty(rsm) && collectorFree && heldCollector != null) {
				long t0 = System.nanoTime();
				int f = m.originclient$lastUpdatedFrame();   // update() bumped it right before this call
				for (int i = 0; i < stampN; i++) {
					stamped[i].setLastVisibleFrame(f);   // the main walk's stamps, with this frame's number
				}
				m.originclient$setLastSectionCollector(heldCollector);
				reusedThisUpdate = true;
				long dt = System.nanoTime() - t0;
				totalRestoreNanos += dt;
				totalReused++;
				secReused++;
				return true;
			}
			// Walking: everything seen so far is consumed by this walk.
			dirty = false;
			return false;
		} catch (Throwable t) {
			fail(t);
			return false;
		}
	}

	/** RenderSectionManager.update RETURN: prime from a real main walk. */
	public static void afterUpdate(RenderSectionManager rsm) {
		if (reusedThisUpdate) {
			reusedThisUpdate = false;
			return;
		}
		if (!live()) {
			return;
		}
		try {
			if (shadowPass()) {
				return;
			}
			totalMainWalks++;
			secWalks++;
			if (!tasksEmpty(rsm)) {
				// This walk queued rebuild/sort work. A queue is capped, so sections past the cap stay
				// pending and only a later walk picks them up: keep walking until a walk queues nothing.
				markDirty(9);
			}
			if (settingOn()) {
				// A walk that recorded no stamps (a stamp redirect missing, no shader pack, or Sodium's tree
				// path, which stamps nothing) is never reused.
				SectionCollector c = ((SodiumRenderSectionManagerAccessor) rsm).originclient$sectionCollector();
				primed = stampN > 0 && c != null && packInUse();
				manager = primed ? rsm : null;
				heldCollector = primed ? c : null;
				if (!primed) {
					unprime();
				}
			} else {
				releaseIfHeld();
			}
		} catch (Throwable t) {
			fail(t);
		}
	}

	/** createTerrainRenderList HEAD (a walk that runs) / RETURN: CPU time of each walk, split main / shadow. */
	public static void walkBegin() {
		if (!live()) {
			return;
		}
		try {
			walkStart = System.nanoTime();
			if (settingOn() && !shadowPass() && packInUse()) {
				// This main walk replaces what is held; afterUpdate primes again from it. No pack =
				// no per-section recording (reuse needs a pack anyway).
				primed = false;
				heldCollector = null;
				java.util.Arrays.fill(stamped, 0, stampN, null);
				stampN = 0;
				recording = true;
			}
		} catch (Throwable t) {
			fail(t);
		}
	}

	/** OcclusionCuller stamped a section (any pass); recorded only inside a main walk with the toggle on. */
	public static void onStamp(RenderSection section, int hook) {
		if (recording) {
			hooksSeen |= 1 << hook;
			if (stampN == stamped.length) {
				stamped = java.util.Arrays.copyOf(stamped, Math.max(1024, stampN * 2));
			}
			stamped[stampN++] = section;
		}
	}

	public static void walkEnd() {
		recording = false;
		if (!live() || walkStart == 0) {
			return;
		}
		try {
			long dt = System.nanoTime() - walkStart;
			walkStart = 0;
			if (shadowPass()) {
				totalShadowWalks++;
				totalShadowNanos += dt;
				secShadowWalks++;
				secShadowNanos += dt;
			} else {
				totalMainNanos += dt;
				secMainNanos += dt;
			}
		} catch (Throwable t) {
			fail(t);
		}
	}

	private static boolean packInUse() throws Throwable {
		return PACK_IN_USE != null && (boolean) PACK_IN_USE.invokeExact();
	}

	private static boolean tasksEmpty(RenderSectionManager rsm) {
		var lists = ((SodiumRenderSectionManagerAccessor) rsm).originclient$taskLists();
		if (lists == null) {
			return true;
		}
		for (ArrayDeque<?> q : lists.values()) {
			if (!q.isEmpty()) {
				return false;
			}
		}
		return true;
	}

	/** Drop everything held for a manager (recorded sections, the collector): they reference its ClientLevel. */
	private static void release() {
		primed = false;
		manager = null;
		heldCollector = null;
		stamped = NO_SECTIONS;
		stampN = 0;
		recording = false;
	}

	/**
	 * Priming failed: hold nothing for this walk but keep the array (this can repeat every frame, so no
	 * allocation). References are cleared so no old level stays reachable through them.
	 */
	private static void unprime() {
		primed = false;
		manager = null;
		heldCollector = null;
		java.util.Arrays.fill(stamped, 0, stampN, null);
		stampN = 0;
	}

	private static void releaseIfHeld() {
		if (primed || manager != null || heldCollector != null || stamped.length != 0) {
			release();
		}
	}

	/** RenderSectionManager.destroy HEAD: leaving the world, a render-distance change, a shader reload. */
	public static void onDestroy() {
		try {
			releaseIfHeld();
			dirty = true;
		} catch (Throwable t) {
			fail(t);
		}
	}

	private static void rollSecond() {
		long now = System.nanoTime();
		if (secStart == 0) {
			secStart = now;
			return;
		}
		if (now - secStart >= 1_000_000_000L) {
			lastWalks = secWalks;
			lastReused = secReused;
			lastMainNanos = secMainNanos;
			lastShadowWalks = secShadowWalks;
			lastShadowNanos = secShadowNanos;
			lastSecAt = now;
			secWalks = secReused = secMainNanos = secShadowWalks = secShadowNanos = 0;
			secStart = now;
		}
	}

	/** One F3 line while the toggle is on and a shader pack is drawing; null otherwise. */
	public static String debugLine() {
		if (!settingOn()) {
			return null;
		}
		if (broken) {
			return "Origin Smart Terrain: off after an error";
		}
		if (!VERSIONS_OK) {
			return "Origin Smart Terrain: off (needs Sodium 0.8.14 + Iris 1.10.8)";
		}
		if (lastSecAt == 0 || System.nanoTime() - lastSecAt > 3_000_000_000L) {
			return null;   // no shader frames lately
		}
		long frames = lastWalks + lastReused;
		return String.format(java.util.Locale.ROOT,
				"Origin Smart Terrain: reused %d/%d main terrain passes/s, main walk %.2f ms, shadow walk %.2f ms",
				lastReused, frames,
				lastWalks == 0 ? 0.0 : lastMainNanos / 1e6 / lastWalks,
				lastShadowWalks == 0 ? 0.0 : lastShadowNanos / 1e6 / lastShadowWalks);
	}

	// ======================= dev: main-pass entity culling counts =======================
	// Counted from one main walk decision to the next (= one frame's main entity extraction), shadow pass excluded.
	public static final String HIDDEN_TAG = "origin_stu_hidden";
	/** Dev: the sealed pocket section (SectionPos long). Entity tags are server-only, so the client finds the pocket pigs by section. */
	public static long hiddenSection = Long.MIN_VALUE;
	private static long fChecks, fVisible, fSig, fHidChecks, fHidVisible;
	private static long wFrames, wChkMin, wChkMax, wVisMin, wVisMax, wSig, wSigDiffer, wHidChecks, wHidVisible;

	/** SodiumWorldRenderer.isEntityVisible RETURN. Players: returns the value untouched (DEV is false). */
	public static boolean countEntity(net.minecraft.world.entity.Entity e, boolean visible) {
		if (!DEV) {
			return visible;
		}
		try {
			if (SHADOW_PASS == null || shadowPass()) {
				return visible;
			}
			fChecks++;
			if (visible) {
				fVisible++;
				fSig += it.unimi.dsi.fastutil.HashCommon.mix((long) e.getId() + 1);
			}
			if (net.minecraft.core.SectionPos.asLong(e.blockPosition()) == hiddenSection) {
				fHidChecks++;
				if (visible) {
					fHidVisible++;
				}
			}
		} catch (Throwable ignored) {
			// dev counters only
		}
		return visible;
	}

	private static void entityFrameDone() {
		if (fChecks != 0) {
			if (wFrames == 0) {
				wChkMin = wChkMax = fChecks;
				wVisMin = wVisMax = fVisible;
				wSig = fSig;
			} else {
				wChkMin = Math.min(wChkMin, fChecks);
				wChkMax = Math.max(wChkMax, fChecks);
				wVisMin = Math.min(wVisMin, fVisible);
				wVisMax = Math.max(wVisMax, fVisible);
				if (fSig != wSig) {
					wSigDiffer++;
				}
			}
			wFrames++;
			wHidChecks += fHidChecks;
			wHidVisible += fHidVisible;
		}
		fChecks = fVisible = fSig = fHidChecks = fHidVisible = 0;
	}

	/** Dev: start a fresh entity window (the frame in progress is dropped, not mixed in). */
	public static void entityWindowReset() {
		fChecks = fVisible = fSig = fHidChecks = fHidVisible = 0;
		wFrames = wChkMin = wChkMax = wVisMin = wVisMax = wSig = wSigDiffer = wHidChecks = wHidVisible = 0;
	}

	/** Dev log: per-frame entity culling results since entityWindowReset(). */
	public static String entityLine() {
		return String.format(java.util.Locale.ROOT,
				"entities{frames=%d checked/frame=%d..%d visible/frame=%d..%d visibleSet=%016x framesWithOtherSet=%d hiddenPigs checked=%d drawn=%d}",
				wFrames, wChkMin, wChkMax, wVisMin, wVisMax, wSig, wSigDiffer, wHidChecks, wHidVisible);
	}

	/** Dev log: which graph-change hooks fired at least once. */
	public static String hooksLine() {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < HOOK_NAMES.length; i++) {
			sb.append(HOOK_NAMES[i]).append((hooksSeen & (1 << i)) != 0 ? "=yes " : "=NO ");
		}
		return sb.toString().trim() + (VERSIONS_OK ? "" : " (versions not matched: feature inert)");
	}
}
