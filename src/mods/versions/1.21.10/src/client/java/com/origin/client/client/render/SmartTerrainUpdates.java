package com.origin.client.client.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.origin.client.client.hud.FrameStats;
import com.origin.client.client.mixin.SodiumRenderSectionManagerAccessor;
import com.origin.client.client.mods.Mods;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.RenderSectionVisitor;
import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.OcclusionCuller;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
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
 * SETTINGS > Performance > Smart Terrain Updates (1.21.10 port of 1.21.1 via 1.21.6, default OFF). Shader packs only.
 * This module serves 1.21.10, which gets Sodium 0.7.3+mc1.21.10 / Iris 1.9.7+mc1.21.10
 * (PerformanceModCatalog.Data.cs). Any other build: VERSIONS_OK turns it off and Mods hides the toggle.
 *
 * <p>What it saves (read from Sodium 0.7.3+mc1.21.10 / Iris 1.9.7+mc1.21.10 bytecode, 2026-10-20): with a
 * shader pack, Iris's shadow pass calls SodiumWorldRenderer.setupTerrain with the shadow camera and
 * projection right before the main pass calls it with the player's. Sodium's setupTerrain compares the
 * camera and projection with ITS last call and marks the chunk graph dirty when they differ, so every
 * frame the main pass sees "camera changed" and redoes the whole chunk-graph walk
 * (RenderSectionManager.createTerrainRenderList -> OcclusionCuller.findVisible), even standing still.
 *
 * <p>PER-VERSION (0.7.3): 1.21.1/1.21.4 skipped the whole main update() and restored a copy of the
 * region render lists. That can't work here: 0.7 walks into a SectionCollector that update() turns into
 * the render lists afterwards (finalizeRenderLists, processChunkBuildResults reuse lastSectionCollector),
 * and Iris 1.9.7 swaps every region between a main and a shadow ChunkRenderList inside a wrapper around
 * createTerrainRenderList. So update() always runs here; only the expensive part, the graph search inside
 * OcclusionCuller.findVisible, is REPLAYED: the last real main walk's results are fed to this frame's
 * collector in the same order (every visit(section) call, every setLastVisibleFrame stamp, with this
 * frame's number). Everything after it -- Iris's list swap, the collector, the task lists, Sodium's
 * render lists, needsGraphUpdate -- then runs exactly as after a real walk.
 *
 * <p>Replay is allowed only when ALL of this holds since the last real main walk (any doubt = walk):
 * <ul>
 * <li>Sodium 0.7.3+mc1.21.10 and Iris 1.9.7+mc1.21.10 exactly;</li>
 * <li>same RenderSectionManager (a render-distance change, shader reload or dimension change makes
 *     a new one) and a shader pack in use;</li>
 * <li>camera: same position, pitch, yaw, rotation, setupTerrain projection + model-view matrices, fog
 *     end + alpha (the walk's search distance), smartCull, spectator flag, and the same culling frustum
 *     (LevelRenderer.prepareCullFrustum's position + both matrices, bit-exact); findVisible's own
 *     arguments (search distance, occlusion flag, camera transform) equal to the recorded walk's;</li>
 * <li>no graph change: no section added/removed, no rebuild or sort that changed a pending update, no
 *     chunk build result uploaded, no markGraphDirty outside the shadow pass except Sodium's own
 *     camera-change call (covered by the camera checks above), not an "update chunks immediately" frame;</li>
 * <li>Sodium's main task lists are empty (nothing waits to be picked up by a walk).</li>
 * </ul>
 *
 * <p>Why a replay is the same as a walk: SectionCollector.visit reads only live section state (flags,
 * region, pending update, running job), and every change to that state is one of the graph changes
 * above. The walk's own result (which sections, in which order) depends only on the graph, the
 * camera and the frustum. Entity culling (isSectionVisible: stamp == lastUpdatedFrame) sees the same
 * stamps, because the replay stamps the same sections with the frame number update() just made. Iris's
 * shadow walk stamps a separate field (Iris MixinRenderSection), so it never disturbs them.
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

	// ---- replay state ----
	/** Something that can change the walk's result happened since the last real main walk. */
	private static boolean dirty = true;
	/** The RenderSectionManager the recording belongs to. */
	private static Object manager;
	/** A complete recording of the last real main walk is held. */
	private static boolean primed;
	/** Inside Sodium's own camera-change markGraphDirty call in setupTerrain. */
	private static boolean cameraMark;
	/** Decided at the main update() HEAD for this update's findVisible: replay it / record it. */
	private static boolean replayNext, recordNext;
	/** This createTerrainRenderList reached findVisible (false = Sodium's out-of-graph path). */
	private static boolean reachedCuller;
	/** This createTerrainRenderList replayed instead of walking. */
	private static boolean replayed;

	// ---- the recording: findVisible's arguments, stamped sections, visited sections (in order) ----
	private static final RenderSection[] NO_SECTIONS = {};
	private static RenderSection[] stamped = NO_SECTIONS, visited = NO_SECTIONS;
	private static int stampN, visitN;
	private static float recDist;
	private static boolean recOcclusion;
	private static int recIntX, recIntY, recIntZ;
	private static float recFracX, recFracY, recFracZ;
	/** Inside a recorded walk: OcclusionCuller's stamps are recorded. */
	private static boolean recording;

	/** Stands in for the walk's collector while recording: records each visit, then passes it on. No allocation. */
	private static final class Recorder implements RenderSectionVisitor {
		RenderSectionVisitor inner;

		@Override
		public void visit(RenderSection section) {
			if (visitN == visited.length) {
				visited = java.util.Arrays.copyOf(visited, Math.max(1024, visitN * 2));
			}
			visited[visitN++] = section;
			inner.visit(section);
		}
	}

	private static final Recorder RECORDER = new Recorder();

	// ---- camera fingerprint (main pass, previous frame) ----
	private static boolean haveCam;
	private static double camX, camY, camZ;
	private static float xRot, yRot, fogEnd, fogAlpha;
	private static final Quaternionf ROT = new Quaternionf();
	private static final Matrix4f PROJ = new Matrix4f();
	private static final Matrix4f VIEW = new Matrix4f();
	private static boolean smartCull, spectator;

	// ---- cull frustum fingerprint (prepareCullFrustum, previous frame) ----
	private static boolean haveCull;
	private static double cullX, cullY, cullZ;
	private static final Matrix4f CULL_VIEW = new Matrix4f();
	private static final Matrix4f CULL_PROJ = new Matrix4f();

	// ---- counters (render thread only) ----
	/** totalRestoreNanos: CPU time of the replays (the name is shared with the 1.21.1/1.21.4 dev harness). */
	public static long totalMainWalks, totalReused, totalMainNanos, totalShadowWalks, totalShadowNanos, totalRestoreNanos;
	private static long walkStart;
	private static long secStart, secWalks, secReused, secMainNanos, secReplayNanos, secShadowWalks, secShadowNanos;
	private static long lastWalks, lastReused, lastMainNanos, lastReplayNanos, lastShadowWalks, lastShadowNanos, lastSecAt;
	/** Dev proof that each graph-change hook is live: one bit per hook, set the first time it fires. */
	public static int hooksSeen;
	public static final String[] HOOK_NAMES = {"sectionAdded", "sectionRemoved", "scheduleRebuild", "scheduleSort",
			"buildResults", "markGraphDirty", "cameraMark", "cullFrustum", "setupTerrain",
			"stampVisit", "stampNearby", "stampInit", "findVisible"};
	/**
	 * Hooks that fire during any world load (chunks added, built and re-meshed as neighbours arrive,
	 * Sodium's own markGraphDirty calls, the camera hooks) and in every recorded main walk (the visitNode
	 * and initWithinWorld stamps, the findVisible wrap): replay waits until all of them have fired once,
	 * so a mixin that failed to apply turns the feature off instead of letting a change go unseen.
	 * stampNearby is not required: addNearbySections stamps nothing when the walk already reached its
	 * sections.
	 */
	private static final int REQUIRED_HOOKS = (1 << 0) | (1 << 2) | (1 << 4) | (1 << 5) | (1 << 7) | (1 << 8)
			| (1 << 9) | (1 << 11) | (1 << 12);

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
			"tasksPending", "walkArgs"};
	private static final String[] CAM_NAMES = {"pos", "angle", "rotation", "projection", "fog", "cullOrSpectator"};
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

	/** RenderSectionManager.markGraphDirty: a change unless it is the shadow pass's or Sodium's camera call. */
	public static void onMarkGraphDirty() {
		seen(5);
		if (cameraMark) {
			return;   // Sodium's camera-change mark; the camera fingerprint decides instead
		}
		try {
			if (SHADOW_PASS != null && shadowPass()) {
				return;   // Iris's LevelRenderer.needsUpdate() from ShadowRenderer.renderShadows
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

	/** LevelRenderer.prepareCullFrustum (main frame only; GameRenderer calls it once per frame). */
	public static void onCullFrustum(Vec3 pos, Matrix4f view, Matrix4f proj, boolean captured) {
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
					&& CULL_VIEW.equals(view) && CULL_PROJ.equals(proj);
			if (!same || captured) {
				markDirty(captured ? 10 : 7);
			}
			cullX = pos.x;
			cullY = pos.y;
			cullZ = pos.z;
			CULL_VIEW.set(view);
			CULL_PROJ.set(proj);
			haveCull = true;
		} catch (Throwable t) {
			fail(t);
		}
	}

	/** SodiumWorldRenderer.setupTerrain HEAD: the main pass's camera state against the previous frame's. */
	public static void onSetupTerrain(Camera camera, FogParameters walkFog, boolean spectatorNow, boolean immediate,
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
			Vec3 p = camera.getPosition();
			Quaternionf r = camera.rotation();
			// PER-VERSION (0.7.3): the projection Sodium itself compares is the ChunkRenderMatrices argument
			// (no RenderSystem projection read in setupTerrain), and the walk's search distance comes from
			// the Sodium FogParameters argument (getEffectiveRenderDistance reads renderEnd + alpha).
			// Records, read in place: no allocation.
			Matrix4fc proj = matrices == null ? null : matrices.projection();
			Matrix4fc view = matrices == null ? null : matrices.modelView();
			float fog = walkFog == null ? Float.NaN : walkFog.renderEnd();
			float alpha = walkFog == null ? Float.NaN : walkFog.alpha();
			boolean cull = Minecraft.getInstance().smartCull;
			// NaN never equals itself: a missing fog record always counts as changed.
			boolean sameFog = fog == fogEnd && alpha == fogAlpha;
			boolean sameMat = proj != null && view != null && PROJ.equals(proj, 0f) && VIEW.equals(view, 0f);
			boolean same = haveCam && p.x == camX && p.y == camY && p.z == camZ
					&& camera.getXRot() == xRot && camera.getYRot() == yRot
					&& r.equals(ROT) && sameMat && sameFog
					&& cull == smartCull && spectatorNow == spectator;
			if (!same || immediate) {
				markDirty(immediate ? 8 : 6);
				if (DEV && haveCam) {
					// Which part of the camera moved (dev diagnosis of replay misses).
					if (p.x != camX || p.y != camY || p.z != camZ) camWhy[0]++;
					if (camera.getXRot() != xRot || camera.getYRot() != yRot) camWhy[1]++;
					if (!r.equals(ROT)) camWhy[2]++;
					if (!sameMat) camWhy[3]++;
					if (!sameFog) camWhy[4]++;
					if (cull != smartCull || spectatorNow != spectator) camWhy[5]++;
				}
			}
			camX = p.x;
			camY = p.y;
			camZ = p.z;
			xRot = camera.getXRot();
			yRot = camera.getYRot();
			ROT.set(r);
			if (proj != null && view != null) {
				PROJ.set(proj);
				VIEW.set(view);
			}
			fogEnd = fog;
			fogAlpha = alpha;
			smartCull = cull;
			spectator = spectatorNow;
			haveCam = true;
		} catch (Throwable t) {
			fail(t);
		}
	}

	// ======================= the walk =======================

	/** RenderSectionManager.update HEAD (never cancelled): decide this update's findVisible (main pass only). */
	public static void beforeUpdate(RenderSectionManager rsm) {
		replayNext = recordNext = false;
		if (!live()) {
			releaseIfHeld();
			return;
		}
		try {
			if (shadowPass()) {
				return;
			}
			rollSecond();
			if (DEV) {
				entityFrameDone();
			}
			boolean on = settingOn();
			boolean pack = packInUse();
			if (DEV) {
				int w = !on ? 0 : !primed ? 1 : dirty ? 2 : rsm != manager ? 3
						: (hooksSeen & REQUIRED_HOOKS) != REQUIRED_HOOKS ? 4 : !pack ? 5 : !tasksEmpty(rsm) ? 6 : -1;
				if (w >= 0) {
					walkWhy[w]++;
				}
			}
			replayNext = on && primed && !dirty && rsm == manager && (hooksSeen & REQUIRED_HOOKS) == REQUIRED_HOOKS
					&& pack && tasksEmpty(rsm);
			// A walk (no shader pack = no recording: replay needs a pack anyway) records for later frames.
			recordNext = !replayNext && on && pack;
			// Everything seen so far is consumed by this update (a replay only runs when nothing was seen).
			dirty = false;
		} catch (Throwable t) {
			fail(t);
		}
	}

	/**
	 * The OcclusionCuller.findVisible call inside createTerrainRenderList (SodiumWalkReplayMixin). Replays the
	 * recorded walk into this frame's collector, records a real walk, or just walks.
	 */
	public static void findVisible(RenderSectionManager rsm, OcclusionCuller culler, RenderSectionVisitor visitor,
			Viewport viewport, float dist, boolean occlusion, int frame, Operation<Void> original) {
		boolean replay = replayNext, record = recordNext;
		replayNext = recordNext = false;
		if (broken || !(replay || record)) {
			original.call(culler, visitor, viewport, dist, occlusion, frame);
			return;
		}
		reachedCuller = true;
		seen(12);
		if (replay) {
			CameraTransform t = viewport.getTransform();
			if (primed && rsm == manager && dist == recDist && occlusion == recOcclusion
					&& t.intX == recIntX && t.intY == recIntY && t.intZ == recIntZ
					&& t.fracX == recFracX && t.fracY == recFracY && t.fracZ == recFracZ) {
				try {
					// The walk's two side effects, in its own order: every section it reached gets this
					// frame's stamp, and the collector sees every visible section in walk order.
					for (int i = 0; i < stampN; i++) {
						stamped[i].setLastVisibleFrame(frame);
					}
					for (int i = 0; i < visitN; i++) {
						visitor.visit(visited[i]);
					}
					replayed = true;
				} catch (Throwable e) {
					// The collector may be half filled: this one frame draws what it got (re-walking now
					// would visit sections twice); the next frame walks normally.
					fail(e);
				}
				return;
			}
			if (DEV) {
				walkWhy[7]++;
			}
			// The walk's own inputs differ from the recording's: walk and record instead.
		}
		// Record: the stamps (SodiumOcclusionStampMixin) and visits (Recorder) of this walk.
		java.util.Arrays.fill(stamped, 0, stampN, null);
		java.util.Arrays.fill(visited, 0, visitN, null);
		stampN = visitN = 0;
		primed = false;
		manager = null;
		RECORDER.inner = visitor;
		recording = true;
		try {
			original.call(culler, RECORDER, viewport, dist, occlusion, frame);
		} finally {
			recording = false;
			RECORDER.inner = null;
		}
		try {
			CameraTransform t = viewport.getTransform();
			recDist = dist;
			recOcclusion = occlusion;
			recIntX = t.intX;
			recIntY = t.intY;
			recIntZ = t.intZ;
			recFracX = t.fracX;
			recFracY = t.fracY;
			recFracZ = t.fracZ;
			// A walk that recorded no stamps or no visits (a stamp redirect missing) is never replayed.
			primed = stampN > 0 && visitN > 0;
			manager = primed ? rsm : null;
		} catch (Throwable e) {
			fail(e);
		}
	}

	/** RenderSectionManager.update RETURN (both passes; the shadow pass is skipped). */
	public static void afterUpdate(RenderSectionManager rsm) {
		replayNext = recordNext = false;
		if (!live()) {
			return;
		}
		try {
			if (shadowPass()) {
				return;
			}
			if (replayed) {
				totalReused++;
				secReused++;
			} else {
				totalMainWalks++;
				secWalks++;
			}
			replayed = false;
			if (!tasksEmpty(rsm)) {
				// This walk queued rebuild/sort work. A queue is capped (TaskQueueType.queueSizeLimit),
				// so sections past the cap stay pending and only a later walk picks them up: keep
				// walking until a walk finds nothing left to queue.
				markDirty(9);
			}
			if (!settingOn()) {
				releaseIfHeld();
			}
		} catch (Throwable t) {
			fail(t);
		}
	}

	/** createTerrainRenderList HEAD: start the walk timer. */
	public static void walkBegin() {
		reachedCuller = false;
		replayed = false;
		if (!live()) {
			return;
		}
		walkStart = System.nanoTime();
	}

	/** OcclusionCuller stamped a section (any pass); recorded only inside a recorded main walk. */
	public static void onStamp(RenderSection section, int hook) {
		if (recording) {
			hooksSeen |= 1 << hook;
			if (stampN == stamped.length) {
				stamped = java.util.Arrays.copyOf(stamped, Math.max(1024, stampN * 2));
			}
			stamped[stampN++] = section;
		}
	}

	/** createTerrainRenderList RETURN: close the timer (split walk / replay / shadow walk). */
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
			} else if (replayed) {
				totalRestoreNanos += dt;
				secReplayNanos += dt;
			} else {
				totalMainNanos += dt;
				secMainNanos += dt;
				if (!reachedCuller && primed) {
					// Sodium's out-of-graph path (camera above/below the world) built this frame's lists
					// without a graph walk: the recording no longer matches the last main walk.
					unprime();
				}
			}
		} catch (Throwable t) {
			fail(t);
		}
	}

	private static boolean packInUse() throws Throwable {
		return PACK_IN_USE != null && (boolean) PACK_IN_USE.invokeExact();
	}

	/** Sodium's main task lists (Iris 1.9.7 redirects the shadow pass's into its own field, so this is the main one). */
	private static boolean tasksEmpty(RenderSectionManager rsm) {
		for (ArrayDeque<?> q : ((SodiumRenderSectionManagerAccessor) rsm).originclient$taskLists().values()) {
			if (!q.isEmpty()) {
				return false;
			}
		}
		return true;
	}

	/** Drop everything held for a manager (recorded sections reference its ClientLevel). */
	private static void release() {
		primed = false;
		manager = null;
		replayNext = recordNext = false;
		stamped = visited = NO_SECTIONS;
		stampN = visitN = 0;
		recording = false;
		RECORDER.inner = null;
	}

	/** Keep the arrays (this can repeat every frame, so no allocation) but hold no section. */
	private static void unprime() {
		primed = false;
		manager = null;
		java.util.Arrays.fill(stamped, 0, stampN, null);
		java.util.Arrays.fill(visited, 0, visitN, null);
		stampN = visitN = 0;
	}

	private static void releaseIfHeld() {
		if (primed || manager != null || stamped.length != 0 || visited.length != 0) {
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
			lastReplayNanos = secReplayNanos;
			lastShadowWalks = secShadowWalks;
			lastShadowNanos = secShadowNanos;
			lastSecAt = now;
			secWalks = secReused = secMainNanos = secReplayNanos = secShadowWalks = secShadowNanos = 0;
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
			return "Origin Smart Terrain: off (needs Sodium 0.7.3 + Iris 1.9.7)";
		}
		if (lastSecAt == 0 || System.nanoTime() - lastSecAt > 3_000_000_000L) {
			return null;   // no shader frames lately
		}
		long frames = lastWalks + lastReused;
		return String.format(java.util.Locale.ROOT,
				"Origin Smart Terrain: replayed %d/%d main chunk walks/s, walk %.2f ms, replay %.2f ms, shadow walk %.2f ms",
				lastReused, frames,
				lastWalks == 0 ? 0.0 : lastMainNanos / 1e6 / lastWalks,
				lastReused == 0 ? 0.0 : lastReplayNanos / 1e6 / lastReused,
				lastShadowWalks == 0 ? 0.0 : lastShadowNanos / 1e6 / lastShadowWalks);
	}

	// ======================= dev: main-pass entity culling counts =======================
	// Counted from one main update() to the next (= one frame's main entity draw), shadow pass excluded.
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
