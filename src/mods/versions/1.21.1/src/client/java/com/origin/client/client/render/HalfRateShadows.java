package com.origin.client.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.hud.FrameStats;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.ref.WeakReference;
import java.util.Set;

/**
 * SETTINGS > Performance > Half-Rate Shadows (1.21.1, default OFF).
 *
 * <p>What it does (Iris 1.8.8, read from the jar 2026-10-19): with a shader pack on, Iris clears and
 * redraws the whole shadow map every frame (IrisRenderingPipeline.beginLevelRendering clears the shadow
 * targets and runs the pack's shadow compute programs; ShadowRenderer.renderShadows walks and draws every
 * shadow caster, then runs shadowcomp). With this on, every second frame keeps the last frame's shadow
 * map instead: the clear block, the computes and renderShadows are all skipped, so that frame pays none
 * of the shadow pass's CPU or GPU cost.
 *
 * <p>Why shadows don't swim on a kept frame: the main pass looks shadows up with the shadowModelView
 * uniform, which Iris rebuilds every frame from the camera (ShadowRenderer.createShadowModelView). On a
 * kept frame that matrix is swapped for the one the kept map was drawn with, shifted by how far the
 * camera moved since: a world point P drawn at M_d * (P - C_d) is looked up at
 * M_d * T(C - C_d) * (P - C) = M_d * (P - C_d), the same texel. What still lags by one frame is
 * anything that moved by itself (mobs, swaying leaves) -- the tradeoff the tooltip states.
 *
 * <p>Always a full redraw (never two kept frames in a row) and also forced on: the first frame after
 * turning it on, a world or dimension change, a shader reload, a window resize, a shadow-distance or
 * render-distance change, a camera jump over 4 blocks (teleport), any chunk rebuild scheduled or
 * uploaded since the last draw (block edits), a shadow target that needs its one-time full clear, and
 * packs that keep their own state across frames (cleared custom images, storage buffers, voxelization:
 * a kept frame would leave those wrong) -- those packs simply draw every frame.
 *
 * <p>Safety (mandate 4): every Iris field and method this reads is bound once by reflection (below);
 * a different Iris build that lacks one turns the feature off instead of failing a mixin. A frame is
 * only kept after every hook this needs has been seen running (the shadow draw's save, the matrix
 * swap); a hook that failed to apply just means "always draw". Any exception latches the feature off
 * for the session (logged once). No allocation per frame.
 */
public final class HalfRateShadows {
	private HalfRateShadows() {
	}

	public static final String KEY_ON = "halfRateShadows";

	/** Dev-only counters (CI measurement). False for players. */
	private static final boolean DEV = System.getenv("ORIGIN_SHOTS") != null;

	/** A camera move longer than this since the kept map was drawn counts as a jump (teleport). */
	private static final double JUMP_SQ = 4.0 * 4.0;

	// ---- Iris 1.8.8 members (javap 2026-10-19), bound once; any missing = feature off ----
	private static final String PIPE = "net.irisshaders.iris.pipeline.IrisRenderingPipeline";
	private static final String SR = "net.irisshaders.iris.shadows.ShadowRenderer";
	/** IrisRenderingPipeline: private final ShadowRenderer shadowRenderer. */
	private static final MethodHandle PIPE_SHADOW_RENDERER = getter(PIPE, "shadowRenderer", false);
	/** IrisRenderingPipeline: private final Set<GlImage> customImages (clear=true and clear=false images). */
	private static final MethodHandle PIPE_CUSTOM_IMAGES = getter(PIPE, "customImages", false);
	/** IrisRenderingPipeline: private ShaderStorageBufferHolder shaderStorageBufferHolder. */
	private static final MethodHandle PIPE_SSBO = getter(PIPE, "shaderStorageBufferHolder", false);
	/** ShadowRenderer: private boolean packHasVoxelization. */
	private static final MethodHandle SR_VOXEL = getter(SR, "packHasVoxelization", false);
	/** ShadowRenderer: public static Matrix4f MODELVIEW (a new object on every real draw). */
	private static final MethodHandle SR_MODELVIEW = getter(SR, "MODELVIEW", true);
	/** ShadowRenderer: public static boolean ACTIVE (true only inside the shadow pass). */
	private static final MethodHandle SR_ACTIVE = getter(SR, "ACTIVE", true);
	/** IrisVideoSettings: public static int shadowDistance (the video-settings slider). */
	private static final MethodHandle SHADOW_DISTANCE = getter("net.irisshaders.iris.gui.option.IrisVideoSettings", "shadowDistance", true);
	/** ShadowRenderTargets: public boolean isFullClearRequired(). */
	private static final MethodHandle FULL_CLEAR = method("net.irisshaders.iris.shadows.ShadowRenderTargets", "isFullClearRequired");

	private static final boolean MISSING = PIPE_SHADOW_RENDERER == null || PIPE_CUSTOM_IMAGES == null || PIPE_SSBO == null
			|| SR_VOXEL == null || SR_MODELVIEW == null || SR_ACTIVE == null || SHADOW_DISTANCE == null || FULL_CLEAR == null;
	private static boolean broken = MISSING;

	/** Field getter as (Object)Object, or ()Object for a static field; null if absent. */
	private static MethodHandle getter(String owner, String field, boolean isStatic) {
		try {
			java.lang.reflect.Field f = Class.forName(owner).getDeclaredField(field);
			f.setAccessible(true);
			MethodHandle h = MethodHandles.lookup().unreflectGetter(f);
			return h.asType(isStatic ? MethodType.methodType(Object.class) : MethodType.methodType(Object.class, Object.class));
		} catch (Throwable t) {
			return null;
		}
	}

	private static MethodHandle method(String owner, String name) {
		try {
			java.lang.reflect.Method m = Class.forName(owner).getMethod(name);
			return MethodHandles.lookup().unreflect(m).asType(MethodType.methodType(boolean.class, Object.class));
		} catch (Throwable t) {
			return null;
		}
	}

	// ---- decided once per frame (IrisRenderingPipeline.beginLevelRendering) ----
	private static long decidedFrame = Long.MIN_VALUE;
	private static boolean keeping;

	// ---- the last real shadow draw ----
	private static boolean haveDraw;
	private static long drawFrame = Long.MIN_VALUE;
	private static final Matrix4f DRAWN_MV = new Matrix4f();
	private static Matrix4f lastMvObject;
	private static double drawX, drawY, drawZ;
	/**
	 * Iris makes a new ShadowRenderer per pipeline, so a different one = a shader reload. Held weakly so
	 * an old world or pipeline isn't kept alive after a disconnect or reload; replaced only when they
	 * change (never per frame).
	 */
	private static WeakReference<Object> drawRenderer = new WeakReference<>(null), drawLevel = new WeakReference<>(null);
	/** Pack-state check, cached per pipeline (its custom images and buffers are fixed at creation). */
	private static WeakReference<Object> stateCheckedFor = new WeakReference<>(null);
	private static boolean pipelineKeepsState;
	private static int drawW, drawH, drawShadowDist, drawRd;
	private static boolean drawPackKeepsState;

	/** Set when Sodium schedules or uploads a chunk rebuild; cleared by the next real shadow draw. */
	private static boolean chunksChanged;
	/** The shadowModelView hook has run at least once (so a kept frame's lookup really is corrected). */
	private static boolean modelViewHookSeen;

	// ---- counts ----
	private static long secStart, lastSecAt;
	private static int secKept, secDrawn, lastKept, lastDrawn;
	private static boolean lastPackKeepsState;

	/** Dev totals: frames kept / drawn, and why a draw was forced. */
	public static long devKept, devDrawn, devAlternate, devFirst, devWorld, devReload, devResize, devDistance,
			devJump, devChunks, devFullClear, devPack, devNotReady;
	/** Dev: true when the last decided frame kept the map (read between frames by OriginDevWorld). */
	public static boolean devLastKept;
	/**
	 * Dev swim check, on kept frames only: for the world point under the camera at draw time, how far
	 * (in shadow-view blocks) its lookup lands from the texel it was drawn at -- with the swap (should be
	 * ~0) and with Iris's own fresh matrix (what an uncorrected kept frame would do).
	 */
	public static double devMaxErrFixed, devMaxErrFresh;
	public static long devSwimSamples;
	private static final Matrix4f DEV_FRESH = new Matrix4f();
	private static final Vector4f DEV_A = new Vector4f(), DEV_B = new Vector4f();

	private static boolean settingOn() {
		return Mods.bool(Mods.PERFORMANCE_ID, KEY_ON);
	}

	/**
	 * IrisRenderingPipeline.beginLevelRendering, at its read of shadowRenderTargets for the
	 * "if (shadowRenderTargets != null)" around the shadow clear + computes. Returns true when this frame
	 * keeps the last shadow map (the caller then hides the targets so that whole block is skipped).
	 * Called once per frame with a shader pack on.
	 */
	public static boolean decide(Object pipeline, Object targets) {
		if (broken) {
			decidedFrame = FrameStats.frames();
			keeping = false;
			devLastKept = false;
			return false;
		}
		try {
			Object renderer = PIPE_SHADOW_RENDERER.invokeExact(pipeline);
			if (stateCheckedFor.get() != pipeline) {
				// Packs with custom images (cleared every frame or kept across frames, e.g. voxel / flood-fill
				// ping-pong) or storage buffers may write them from the shadow pass: a kept frame would leave
				// them cleared or stale, so those packs never keep.
				Object imagesObj = PIPE_CUSTOM_IMAGES.invokeExact(pipeline);
				Set<?> images = (Set<?>) imagesObj;
				Object ssbo = PIPE_SSBO.invokeExact(pipeline);
				pipelineKeepsState = (images != null && !images.isEmpty()) || ssbo != null;
				stateCheckedFor = new WeakReference<>(pipeline);
			}
			boolean fullClear = targets != null && (boolean) FULL_CLEAR.invokeExact(targets);
			Object sd = SHADOW_DISTANCE.invokeExact();
			int shadowDistance = (Integer) sd;
			return decide(renderer, targets != null && renderer != null, fullClear, pipelineKeepsState, shadowDistance);
		} catch (Throwable t) {
			fail(t);
			return false;
		}
	}

	private static boolean decide(Object shadowRenderer, boolean hasTargets, boolean fullClearPending,
			boolean packKeepsState, int shadowDistance) {
		long f = FrameStats.frames();
		decidedFrame = f;
		keeping = false;
		devLastKept = false;
		if (broken || !hasTargets) {
			return false;
		}
		try {
			if (!settingOn()) {
				haveDraw = false;   // turning it back on starts with a fresh draw
				return false;
			}
			Minecraft mc = Minecraft.getInstance();
			lastPackKeepsState = packKeepsState || drawPackKeepsState;
			int why = whyDraw(mc, f, shadowRenderer, fullClearPending, packKeepsState, shadowDistance);
			if (DEV) {
				switch (why) {
					case 0 -> devKept++;
					case 1 -> devAlternate++;
					case 2 -> devFirst++;
					case 3 -> devReload++;
					case 4 -> devWorld++;
					case 5 -> devResize++;
					case 6 -> devDistance++;
					case 7 -> devJump++;
					case 8 -> devChunks++;
					case 9 -> devFullClear++;
					case 10 -> devPack++;
					default -> devNotReady++;
				}
				if (why != 0) {
					devDrawn++;
				}
			}
			keeping = why == 0;
			devLastKept = keeping;
			count(keeping);
			return keeping;
		} catch (Throwable t) {
			fail(t);
			return false;
		}
	}

	/** 0 = keep the map; otherwise the reason this frame must draw (see the dev counters). */
	private static int whyDraw(Minecraft mc, long f, Object shadowRenderer, boolean fullClearPending,
			boolean packKeepsState, int shadowDistance) {
		if (!haveDraw) {
			return 2;
		}
		if (shadowRenderer != drawRenderer.get()) {
			return 3;
		}
		if (mc.level != drawLevel.get() || mc.level == null) {
			return 4;
		}
		var main = mc.getMainRenderTarget();
		if (main.width != drawW || main.height != drawH) {
			return 5;
		}
		if (shadowDistance != drawShadowDist || mc.options.getEffectiveRenderDistance() != drawRd) {
			return 6;
		}
		if (packKeepsState || drawPackKeepsState) {
			return 10;
		}
		// The event reasons come before the every-other-frame one so a forced redraw is always counted
		// under its cause (dev-shots asserts an edit and a teleport each force one).
		Vec3 c = mc.gameRenderer.getMainCamera().getPosition();
		double dx = c.x - drawX, dy = c.y - drawY, dz = c.z - drawZ;
		if (dx * dx + dy * dy + dz * dz > JUMP_SQ) {
			return 7;
		}
		if (chunksChanged) {
			return 8;
		}
		if (fullClearPending) {
			return 9;
		}
		if (drawFrame != f - 1) {
			return 1;   // the last frame was kept (or skipped): draw this one
		}
		if (!modelViewHookSeen) {
			return 11;
		}
		return 0;
	}

	/** True on a frame that keeps the last shadow map (decided this frame). */
	private static boolean keepingNow() {
		return keeping && decidedFrame == FrameStats.frames() && !broken;
	}

	/** ShadowRenderer.renderShadows HEAD: true = cancel (this frame keeps the last map). */
	public static boolean skipShadowDraw() {
		return keepingNow();
	}

	/**
	 * ShadowRenderer.renderShadows RETURN (a frame that drew). Saves what the kept frame needs: the
	 * shadow model-view the map was drawn with and where the camera was. Iris installs a new MODELVIEW
	 * object on every real draw; the same object again means it returned early without drawing.
	 */
	public static void afterShadowDraw(Object shadowRenderer) {
		if (broken) {
			return;
		}
		try {
			Object mvObj = SR_MODELVIEW.invokeExact();
			Matrix4f modelView = (Matrix4f) mvObj;
			Object voxObj = SR_VOXEL.invokeExact(shadowRenderer);
			boolean packHasVoxelization = (Boolean) voxObj;
			Object sdObj = SHADOW_DISTANCE.invokeExact();
			int shadowDistance = (Integer) sdObj;
			if (modelView == null || modelView == lastMvObject) {
				haveDraw = false;
				return;
			}
			Minecraft mc = Minecraft.getInstance();
			lastMvObject = modelView;
			DRAWN_MV.set(modelView);
			Vec3 c = mc.gameRenderer.getMainCamera().getPosition();
			drawX = c.x;
			drawY = c.y;
			drawZ = c.z;
			if (drawRenderer.get() != shadowRenderer) {
				drawRenderer = new WeakReference<>(shadowRenderer);
			}
			if (drawLevel.get() != mc.level) {
				drawLevel = new WeakReference<>(mc.level);
			}
			var main = mc.getMainRenderTarget();
			drawW = main.width;
			drawH = main.height;
			drawShadowDist = shadowDistance;
			drawRd = mc.options.getEffectiveRenderDistance();
			drawPackKeepsState = packHasVoxelization;
			drawFrame = FrameStats.frames();
			chunksChanged = false;
			haveDraw = true;
		} catch (Throwable t) {
			fail(t);
		}
	}

	/**
	 * ShadowRenderer.createShadowModelView RETURN (Iris calls it for the shadow draw and for the
	 * shadowModelView / shadowModelViewInverse uniforms). On a kept frame, outside the shadow pass,
	 * swap in the drawn matrix shifted by the camera's move since the draw.
	 */
	public static void onShadowModelView(PoseStack stack) {
		if (stack == null) {
			return;
		}
		try {
			Object activeObj = SR_ACTIVE.invokeExact();
			if ((Boolean) activeObj) {
				// The shadow draw's own call proves nothing about the main pass's lookup, so it must not
				// satisfy the "correction hook seen" guard (review 2026-10-20).
				return;
			}
			modelViewHookSeen = true;
			if (!keepingNow()) {
				return;
			}
			Matrix4f pose = stack.last().pose();
			Vec3 c = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
			double dx = c.x - drawX, dy = c.y - drawY, dz = c.z - drawZ;
			if (DEV) {
				DEV_FRESH.set(pose);
			}
			pose.set(DRAWN_MV).translate((float) dx, (float) dy, (float) dz);
			if (DEV) {
				swimCheck(pose, dx, dy, dz);
			}
		} catch (Throwable t) {
			fail(t);
		}
	}

	/** Dev: the point at the draw-time camera, P - C_d = 0, looked up from this frame's camera at -(C - C_d). */
	private static void swimCheck(Matrix4f fixed, double dx, double dy, double dz) {
		DRAWN_MV.transform(DEV_A.set(0f, 0f, 0f, 1f));                         // where it was drawn
		fixed.transform(DEV_B.set((float) -dx, (float) -dy, (float) -dz, 1f));  // where it is looked up
		double eFixed = dist(DEV_A, DEV_B);
		DEV_FRESH.transform(DEV_B.set((float) -dx, (float) -dy, (float) -dz, 1f));
		double eFresh = dist(DEV_A, DEV_B);
		devMaxErrFixed = Math.max(devMaxErrFixed, eFixed);
		devMaxErrFresh = Math.max(devMaxErrFresh, eFresh);
		devSwimSamples++;
	}

	private static double dist(Vector4f a, Vector4f b) {
		double x = a.x - b.x, y = a.y - b.y, z = a.z - b.z;
		return Math.sqrt(x * x + y * y + z * z);
	}

	/** Sodium scheduled a chunk rebuild or uploaded built chunks: the next frame draws the shadow map. */
	public static void onChunksChanged() {
		chunksChanged = true;
	}

	private static void count(boolean kept) {
		long now = System.nanoTime();
		if (secStart == 0) {
			secStart = now;
		}
		if (kept) {
			secKept++;
		} else {
			secDrawn++;
		}
		if (now - secStart >= 1_000_000_000L) {
			lastKept = secKept;
			lastDrawn = secDrawn;
			lastSecAt = now;
			secKept = secDrawn = 0;
			secStart = now;
		}
	}

	private static void fail(Throwable t) {
		if (!broken) {
			broken = true;
			keeping = false;
			haveDraw = false;
			org.slf4j.LoggerFactory.getLogger("originclient")
					.warn("[Origin] Half-Rate Shadows turned off after an error; shadows draw every frame", t);
		}
	}

	/** One F3 line while the toggle is on and a shader pack is drawing shadows; null otherwise. */
	public static String debugLine() {
		if (!settingOn()) {
			return null;
		}
		if (MISSING) {
			return "Origin Half-Rate Shadows: off (this Iris version isn't supported; shadows draw every frame)";
		}
		if (broken) {
			return "Origin Half-Rate Shadows: off after an error (shadows draw every frame)";
		}
		if (lastSecAt == 0 || System.nanoTime() - lastSecAt > 3_000_000_000L) {
			return null;   // no shader frames lately
		}
		if (lastPackKeepsState) {
			return "Origin Half-Rate Shadows: this shader needs shadows every frame, so it draws them all";
		}
		return "Origin Half-Rate Shadows: reused " + lastKept + " of " + (lastKept + lastDrawn)
				+ " shadow maps in the last second";
	}
}
