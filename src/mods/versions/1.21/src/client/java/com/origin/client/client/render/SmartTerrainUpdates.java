package com.origin.client.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.origin.client.client.hud.FrameStats;
import com.origin.client.client.mixin.SodiumChunkRenderListAccessor;
import com.origin.client.client.mixin.SodiumRenderSectionManagerAccessor;
import com.origin.client.client.mixin.SodiumSortedRenderListsAccessor;
import com.origin.client.client.mods.Mods;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.lang.invoke.MethodHandle;
import java.util.ArrayDeque;

/**
 * SETTINGS > Performance > Smart Terrain Updates (1.21 module, default OFF). Shader packs only.
 * Since 2026-10-25 the 1.21 catalog ships the same Sodium 0.6.13 + Iris 1.8.8 jars as 1.21.1, so this
 * is 1.21.1's version again (the 0.5.11 port's frame-argument logic is gone); MC 1.21 and 1.21.1 share
 * every LevelRenderer offset quoted below.
 *
 * <p>What it saves (read from Sodium 0.6.13 / Iris 1.8.8 bytecode, 2026-10-19): Sodium normally
 * walks its chunk graph (which sections are visible) only when something changed. With a shader
 * pack, Iris's shadow pass calls LevelRenderer.needsUpdate() (= RenderSectionManager.markGraphDirty)
 * every frame and then cancels RenderSectionManager.update() right after createTerrainRenderList,
 * BEFORE the line that clears the dirty flag. So the flag is always true and the MAIN pass redoes
 * the whole walk every frame, even standing still. This skips the main walk when it would produce
 * exactly the last result.
 *
 * <p>Reuse is allowed only when ALL of this holds since the last real main walk (any doubt = walk):
 * <ul>
 * <li>Sodium 0.6.13 and Iris 1.8.8 exactly (the internals below were read from those builds);</li>
 * <li>same RenderSectionManager (a render-distance change, shader reload or dimension change makes
 *     a new one) and a shader pack in use;</li>
 * <li>camera: same position, pitch, yaw, rotation, RenderSystem projection, fog end, smartCull,
 *     spectator flag (Sodium's own camera checks plus more), and the same culling frustum
 *     (LevelRenderer.prepareCullFrustum's position + both matrices, bit-exact);</li>
 * <li>no graph change: no section added/removed, no rebuild or sort scheduled, no chunk build
 *     result uploaded, no markGraphDirty outside the shadow pass except Sodium's own camera-change
 *     call (covered by the camera checks above), not an "update chunks immediately" frame;</li>
 * <li>Sodium's rebuild queues are empty (nothing waits to be picked up by a walk).</li>
 * </ul>
 *
 * <p>Frame order (LevelRenderer.renderLevel bytecode, Iris MixinLevelRenderer): Iris runs the
 * whole shadow pass at the renderSky call (offset 437) -- shadow setupTerrain, shadow walk, shadow
 * draw -- BEFORE the main pass's setupRender (offset 495), which runs the main walk; the main
 * terrain and entities draw after that. So each frame is: shadow walk, then main walk, then the
 * main draw. Both walks write two shared things, and a reused frame must leave both exactly as a
 * real main walk would:
 * <ul>
 * <li><b>Region lists.</b> Each RenderRegion has ONE ChunkRenderList, written by both walks. The
 *     shadow walk (earlier in the frame) refills the lists of the regions it reaches; a real main
 *     walk then refills every region it reaches. On a reused frame the main walk's region lists are
 *     copied back from the snapshot taken right after the last real main walk, at the main
 *     update() call, i.e. after this frame's shadow walk.</li>
 * <li><b>Section stamps.</b> update() bumps lastUpdatedFrame and the walk stamps every section it
 *     reaches with it; main-pass entity culling (SodiumWorldRenderer.isEntityVisible ->
 *     RenderSectionManager.isSectionVisible) draws an entity only in a section stamped with the
 *     current lastUpdatedFrame. The shadow walk has just bumped the counter and stamped ITS
 *     sections, so a reused frame does what update() does: bump lastUpdatedFrame once more and
 *     stamp, with that number, exactly the sections the last real main walk stamped (recorded at
 *     OcclusionCuller's three setLastVisibleFrame calls). Entity culling then gives the same answer
 *     for every entity as with the feature off. It also clears needsGraphUpdate, as update() does.</li>
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
	private static final boolean VERSIONS_OK = versionIs("sodium", "0.6.13") && versionIs("iris", "1.8.8");

	private static boolean broken;

	// ---- reuse state ----
	/** Something that can change the walk's result happened since the last real main walk. */
	private static boolean dirty = true;
	/** The RenderSectionManager the snapshot belongs to. */
	private static Object manager;
	/** A snapshot of the last real main walk's region lists is held. */
	private static boolean primed;
	/** Inside Sodium's own camera-change markGraphDirty call in setupTerrain. */
	private static boolean cameraMark;

	// ---- sections the last real main walk stamped (entity culling reads the stamps) ----
	private static final net.caffeinemc.mods.sodium.client.render.chunk.RenderSection[] NO_SECTIONS = {};
	private static net.caffeinemc.mods.sodium.client.render.chunk.RenderSection[] stamped = NO_SECTIONS;
	private static int stampN;
	/** Inside a main-pass walk with the toggle on: OcclusionCuller's stamps are recorded. */
	private static boolean recording;

	// ---- camera fingerprint (main pass, previous frame) ----
	private static boolean haveCam;
	private static double camX, camY, camZ;
	private static float xRot, yRot, fogEnd;
	private static final Quaternionf ROT = new Quaternionf();
	private static final Matrix4f PROJ = new Matrix4f();
	private static boolean smartCull, spectator;

	// ---- cull frustum fingerprint (prepareCullFrustum, previous frame) ----
	private static boolean haveCull;
	private static double cullX, cullY, cullZ;
	private static final Matrix4f CULL_VIEW = new Matrix4f();
	private static final Matrix4f CULL_PROJ = new Matrix4f();

	// ---- snapshot of the main render lists ----
	private static ChunkRenderList[] lists = new ChunkRenderList[0];
	private static byte[][] geo = new byte[0][], spr = new byte[0][], ent = new byte[0][];
	private static int[] geoN = new int[0], sprN = new int[0], entN = new int[0], size = new int[0];
	private static int count;

	// ---- counters (render thread only) ----
	public static long totalMainWalks, totalReused, totalMainNanos, totalShadowWalks, totalShadowNanos, totalRestoreNanos;
	private static long walkStart;
	private static long secStart, secWalks, secReused, secMainNanos, secShadowWalks, secShadowNanos;
	private static long lastWalks, lastReused, lastMainNanos, lastShadowWalks, lastShadowNanos, lastSecAt;
	/** Dev proof that each graph-change hook is live: one bit per hook, set the first time it fires. */
	public static int hooksSeen;
	/** Indices match 1.21.1's so the dev-shots hook lines compare slot for slot. */
	public static final String[] HOOK_NAMES = {"sectionAdded", "sectionRemoved", "scheduleRebuild", "scheduleSort",
			"buildResults", "markGraphDirty", "cameraMark", "cullFrustum", "setupTerrain",
			"stampVisit", "stampNearby", "stampInit"};
	/**
	 * Hooks that fire during any world load (chunks added, built and re-meshed as neighbours arrive, Iris's per-frame mark, the camera
	 * hooks) and in every main walk with the toggle on (the visitNode and initWithinWorld stamps):
	 * reuse waits until all of them have fired once, so a mixin that failed to apply (the config is
	 * defaultRequire 0) turns the feature off instead of letting a change go unseen. stampNearby is
	 * not required: addNearbySections stamps nothing when the walk already reached its sections.
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

	private static boolean versionIs(String mod, String prefix) {
		try {
			return net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer(mod)
					.map(c -> c.getMetadata().getVersion().getFriendlyString().startsWith(prefix + "+")
							|| c.getMetadata().getVersion().getFriendlyString().equals(prefix))
					.orElse(false);
		} catch (Throwable t) {
			return false;
		}
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
	private static final String[] WALK_NAMES = {"off", "notPrimed", "dirty", "newManager", "hooksMissing", "noPack", "tasksPending"};
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
	public static void onSetupTerrain(Camera camera, boolean spectatorNow, boolean immediate) {
		if (!live()) {
			haveCam = false;
			return;
		}
		try {
			if (shadowPass()) {
				return;   // the shadow pass's own setupTerrain: same camera, not a main frame
			}
			seen(8);
			Vec3 p = camera.getPosition();
			Quaternionf r = camera.rotation();
			Matrix4f proj = RenderSystem.getProjectionMatrix();
			float fog = RenderSystem.getShaderFogEnd();
			boolean cull = Minecraft.getInstance().smartCull;
			boolean same = haveCam && p.x == camX && p.y == camY && p.z == camZ
					&& camera.getXRot() == xRot && camera.getYRot() == yRot
					&& r.equals(ROT) && PROJ.equals(proj) && fog == fogEnd
					&& cull == smartCull && spectatorNow == spectator;
			if (!same || immediate) {
				markDirty(immediate ? 8 : 6);
				if (DEV && haveCam) {
					// Which part of the camera moved (dev diagnosis of reuse misses).
					if (p.x != camX || p.y != camY || p.z != camZ) camWhy[0]++;
					if (camera.getXRot() != xRot || camera.getYRot() != yRot) camWhy[1]++;
					if (!r.equals(ROT)) camWhy[2]++;
					if (!PROJ.equals(proj)) camWhy[3]++;
					if (fog != fogEnd) camWhy[4]++;
					if (cull != smartCull || spectatorNow != spectator) camWhy[5]++;
				}
			}
			camX = p.x;
			camY = p.y;
			camZ = p.z;
			xRot = camera.getXRot();
			yRot = camera.getYRot();
			ROT.set(r);
			PROJ.set(proj);
			fogEnd = fog;
			smartCull = cull;
			spectator = spectatorNow;
			haveCam = true;
		} catch (Throwable t) {
			fail(t);
		}
	}

	// ======================= the walk =======================

	/**
	 * RenderSectionManager.update HEAD. True = skip this main walk (the snapshot was restored).
	 * The shadow pass's update is never touched.
	 */
	public static boolean beforeUpdate(RenderSectionManager rsm) {
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
			if (DEV) {
				int w = !on ? 0 : !primed ? 1 : dirty ? 2 : rsm != manager ? 3
						: (hooksSeen & REQUIRED_HOOKS) != REQUIRED_HOOKS ? 4 : !packInUse() ? 5 : !tasksEmpty(rsm) ? 6 : -1;
				if (w >= 0) {
					walkWhy[w]++;
				}
			}
			if (on && primed && !dirty && rsm == manager && (hooksSeen & REQUIRED_HOOKS) == REQUIRED_HOOKS
					&& packInUse() && tasksEmpty(rsm)) {
				long t0 = System.nanoTime();
				// Exactly what update() does around the walk: bump the frame, (walk), clear the flag.
				SodiumRenderSectionManagerAccessor m = (SodiumRenderSectionManagerAccessor) rsm;
				int f = m.originclient$lastUpdatedFrame() + 1;
				m.originclient$setLastUpdatedFrame(f);
				restore(f);
				for (int i = 0; i < stampN; i++) {
					stamped[i].setLastVisibleFrame(f);   // the main walk's stamps over this frame's shadow walk's
				}
				m.originclient$setNeedsGraphUpdate(false);
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

	/** RenderSectionManager.update RETURN (main pass only: Iris cancels the shadow pass's update earlier). */
	public static void afterUpdate(RenderSectionManager rsm) {
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
				// This walk queued rebuild/sort work. A queue is capped (ChunkUpdateType max queue
				// size), so sections past the cap stay pending and only a later walk picks them up:
				// keep walking until a walk finds nothing left to queue.
				markDirty(9);
			}
			if (settingOn()) {
				// A walk that recorded no stamps (a stamp redirect missing, or no shader pack so
				// nothing was recorded) is never reused.
				primed = stampN > 0 && packInUse() && snapshot(rsm);
				manager = primed ? rsm : null;
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

	/** createTerrainRenderList HEAD / RETURN: CPU time of each walk, split main / shadow. */
	public static void walkBegin() {
		// Every walk starts unrecorded: walkEnd lives in its own mixin (SodiumWalkEndMixin), and if it
		// ever failed to apply, a shadow walk must still never be recorded as main-walk stamps.
		recording = false;
		if (!live()) {
			return;
		}
		try {
			walkStart = System.nanoTime();
			if (settingOn() && !shadowPass() && packInUse()) {
				// This main walk replaces what is held; afterUpdate primes again from it. No pack =
				// no per-section recording (reuse needs a pack anyway).
				primed = false;
				java.util.Arrays.fill(stamped, 0, stampN, null);
				stampN = 0;
				recording = true;
			}
		} catch (Throwable t) {
			fail(t);
		}
	}

	/** OcclusionCuller stamped a section (any pass); recorded only inside a main walk with the toggle on. */
	public static void onStamp(net.caffeinemc.mods.sodium.client.render.chunk.RenderSection section, int hook) {
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
		for (ArrayDeque<?> q : ((SodiumRenderSectionManagerAccessor) rsm).originclient$taskLists().values()) {
			if (!q.isEmpty()) {
				return false;
			}
		}
		return true;
	}

	/** Copy the main walk's region lists. False (= don't reuse) if anything looks off. */
	private static boolean snapshot(RenderSectionManager rsm) {
		SodiumRenderSectionManagerAccessor m = (SodiumRenderSectionManagerAccessor) rsm;
		int walkFrame = m.originclient$lastUpdatedFrame();
		ObjectArrayList<ChunkRenderList> src = ((SodiumSortedRenderListsAccessor) m.originclient$renderLists()).originclient$lists();
		int n = src.size();
		ensure(n);
		for (int i = 0; i < n; i++) {
			ChunkRenderList list = src.get(i);
			if (list.getLastVisibleFrame() != walkFrame) {
				return false;   // a list this walk didn't fill: not the state we think it is (caller releases)
			}
			SodiumChunkRenderListAccessor a = (SodiumChunkRenderListAccessor) list;
			lists[i] = list;
			geoN[i] = a.originclient$geoCount();
			sprN[i] = a.originclient$spriteCount();
			entN[i] = a.originclient$entityCount();
			size[i] = a.originclient$size();
			System.arraycopy(a.originclient$geo(), 0, geo[i], 0, geoN[i]);
			System.arraycopy(a.originclient$sprites(), 0, spr[i], 0, sprN[i]);
			System.arraycopy(a.originclient$entities(), 0, ent[i], 0, entN[i]);
		}
		for (int i = n; i < count; i++) {
			lists[i] = null;   // don't keep dead regions' lists alive
		}
		count = n;
		return true;
	}

	/** Put the main walk's sections back into every region list it drew (after this frame's shadow walk). */
	private static void restore(int f) {
		for (int i = 0; i < count; i++) {
			SodiumChunkRenderListAccessor a = (SodiumChunkRenderListAccessor) lists[i];
			System.arraycopy(geo[i], 0, a.originclient$geo(), 0, geoN[i]);
			System.arraycopy(spr[i], 0, a.originclient$sprites(), 0, sprN[i]);
			System.arraycopy(ent[i], 0, a.originclient$entities(), 0, entN[i]);
			a.originclient$setGeoCount(geoN[i]);
			a.originclient$setSpriteCount(sprN[i]);
			a.originclient$setEntityCount(entN[i]);
			a.originclient$setSize(size[i]);
			a.originclient$setLastVisibleFrame(f);
		}
	}

	/** Drop everything held for a manager (snapshot lists, recorded sections): they reference its ClientLevel. */
	private static void release() {
		primed = false;
		manager = null;
		count = 0;
		lists = new ChunkRenderList[0];
		geo = spr = ent = new byte[0][];
		geoN = sprN = entN = size = new int[0];
		stamped = NO_SECTIONS;
		stampN = 0;
		recording = false;
	}

	/**
	 * Priming failed: hold nothing for this walk but keep the arrays (this can repeat every frame,
	 * so no allocation). References are cleared so no old level stays reachable through them.
	 */
	private static void unprime() {
		primed = false;
		manager = null;
		java.util.Arrays.fill(lists, null);
		count = 0;
		java.util.Arrays.fill(stamped, 0, stampN, null);
		stampN = 0;
	}

	private static void releaseIfHeld() {
		if (primed || manager != null || lists.length != 0 || stamped.length != 0) {
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

	private static void ensure(int n) {
		if (lists.length >= n) {
			return;
		}
		int cap = Math.max(n, lists.length * 2);
		ChunkRenderList[] nl = new ChunkRenderList[cap];
		System.arraycopy(lists, 0, nl, 0, lists.length);
		byte[][] ng = new byte[cap][], ns = new byte[cap][], ne = new byte[cap][];
		for (int i = 0; i < cap; i++) {
			// ChunkRenderList's arrays are byte[256] (one byte per section of a 8x4x8 region).
			ng[i] = i < geo.length ? geo[i] : new byte[256];
			ns[i] = i < spr.length ? spr[i] : new byte[256];
			ne[i] = i < ent.length ? ent[i] : new byte[256];
		}
		lists = nl;
		geo = ng;
		spr = ns;
		ent = ne;
		geoN = java.util.Arrays.copyOf(geoN, cap);
		sprN = java.util.Arrays.copyOf(sprN, cap);
		entN = java.util.Arrays.copyOf(entN, cap);
		size = java.util.Arrays.copyOf(size, cap);
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
			return "Origin Smart Terrain: off (needs Sodium 0.6.13 + Iris 1.8.8)";
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
