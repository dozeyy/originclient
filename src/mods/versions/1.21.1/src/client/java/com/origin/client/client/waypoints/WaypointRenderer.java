package com.origin.client.client.waypoints;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.origin.client.client.mods.Mods;
import com.origin.client.client.render.OriginRenderTypes;
import com.origin.client.client.render.ThickLine;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

// The WORLD-SPACE half of waypoint rendering: the beam and the block highlight —
// the parts that belong in the world and are allowed to be hidden by terrain
// (the beam is deliberately the ONLY occluded part; the highlight hugs a block
// face so it shows whenever the block does). Runs on WorldRenderEvents.LAST.
//
// The icon + name + distance are NOT drawn here: they render as a HUD overlay
// (WaypointHud) projected from world position to screen — always on top,
// visible through blocks, and immune to world lighting/shader pipelines (the
// old SEE_THROUGH world text was depth-tested and re-lit under Iris, which is
// exactly what made it invisible). Each frame this pass hands the camera
// matrices to WaypointHud for that projection.
public final class WaypointRenderer {
	private WaypointRenderer() {
	}

	// ---- shader-pack path: draw AFTER the pack's composite (its clouds) ----
	//
	// With an Iris shader pack on, a draw made at WorldRenderEvents.LAST lands in
	// the pack's gbuffers (position_color -> gbuffers_basic) BEFORE the pack's
	// composite passes, and packs paint their volumetric clouds in composite --
	// on top of it (Eclipse: composite3/4). This beam writes no depth, so a pack
	// can't even place it in front of a cloud. So while a pack is in use, the
	// LAST pass only records the frame's matrices + camera, and the world part is
	// drawn from GameRendererPostShadersMixin, right after LevelRenderer.renderLevel
	// returns: Iris has run composite + final into the main target by then, its
	// shader overrides are off (isRenderingWorld=false), and the main target's
	// depth texture still holds the scene depth (Iris's depthtex0 IS that
	// texture), while vanilla's hand depth clear comes only after. Result: the
	// beam is depth-tested against terrain exactly as before, but nothing a pack
	// composites (clouds, fog, volumetrics) can cover it -- the HUD-waypoint
	// behaviour Lunar / Xaero have.
	//
	// Shaders OFF: unchanged -- drawn at LAST, same calls, same order.
	// Fail-soft: the deferred path is used only once the post hook has been seen
	// to run; if that mixin ever fails to apply, beams keep drawing at LAST.
	private static boolean postHookAlive;
	private static boolean pending;
	private static final Matrix4f PEND_POSE = new Matrix4f();
	private static final Matrix3f PEND_NORMAL = new Matrix3f();
	private static final Matrix4f PEND_VIEW = new Matrix4f();
	private static final Matrix4f PEND_PROJ = new Matrix4f();
	private static VertexSorting pendSorting = VertexSorting.DISTANCE_TO_ORIGIN;
	private static Vec3 pendCam = Vec3.ZERO;
	// The deferred pass's own line buffer: the frame's shared bufferSource is
	// Iris's fully-buffered one during renderLevel (see OriginRenderTypes).
	private static final MultiBufferSource.BufferSource POST_LINES =
			MultiBufferSource.immediate(new ByteBufferBuilder(1 << 14));
	// Reused every deferred frame (reset to identity first): no per-frame alloc.
	private static final PoseStack POST_POSE = new PoseStack();
	// Frames a shader pack has been on while the post hook has never run. Past
	// ~60 the hook almost certainly failed to apply: say so once in the log.
	private static int hookMissingFrames;
	private static boolean warnedHookMissing, warnedFailure;

	/** First failure of either pass goes to the log once, with its stack. */
	public static void warnOnce(String where, Throwable t) {
		if (!warnedFailure) {
			warnedFailure = true;
			com.origin.client.OriginClient.LOGGER.warn("Origin: waypoint {} pass failed (logged once)", where, t);
		}
	}

	public static void render(WorldRenderContext ctx) {
		try {
			renderLast(ctx);
		} catch (Throwable t) {
			warnOnce("world (LAST)", t);
		}
	}

	private static void renderLast(WorldRenderContext ctx) {
		if (!Mods.on("waypoints")) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) {
			return;
		}
		PoseStack pose = ctx.matrixStack();
		if (pose == null) {
			return;
		}
		// Hand the view/projection matrices + camera position to the HUD overlay.
		WaypointHud.capture(ctx);

		Vec3 c = ctx.camera().getPosition();
		boolean shaders = ShaderPack.inUse();
		if (shaders && !postHookAlive && !warnedHookMissing && ++hookMissingFrames > 60) {
			warnedHookMissing = true;
			com.origin.client.OriginClient.LOGGER.warn("Origin: GameRendererPostShadersMixin never ran with a shader pack on;"
					+ " waypoint beams are drawn at WorldRenderEvents.LAST, under the pack's clouds");
		}
		if (shaders && postHookAlive) {
			// Record exactly what world geometry is transformed by right now.
			PEND_POSE.set(pose.last().pose());
			PEND_NORMAL.set(pose.last().normal());
			PEND_VIEW.set(RenderSystem.getModelViewMatrix());
			PEND_PROJ.set(RenderSystem.getProjectionMatrix());
			pendSorting = RenderSystem.getVertexSorting();
			pendCam = c;
			pending = true;
			return;
		}
		drawWorld(mc, pose, c, mc.renderBuffers().bufferSource());
	}

	/**
	 * Called from GameRendererPostShadersMixin after LevelRenderer.renderLevel,
	 * every frame. Draws the world part recorded by render() when a shader pack
	 * is in use; otherwise does nothing.
	 */
	public static void renderAfterShaders() {
		try {
			renderPost();
		} catch (Throwable t) {
			warnOnce("after-shaders", t);
		}
	}

	private static void renderPost() {
		postHookAlive = true;
		if (!pending) {
			return;
		}
		pending = false;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) {
			return;
		}
		// By reference: setProjectionMatrix stores a fresh copy in RenderSystem's
		// field (javap, 1.21.1), so this object is never written while we hold it.
		Matrix4f oldProj = RenderSystem.getProjectionMatrix();
		VertexSorting oldSorting = RenderSystem.getVertexSorting();
		Matrix4fStack mv = RenderSystem.getModelViewStack();
		mv.pushMatrix();
		try {
			mc.getMainRenderTarget().bindWrite(false);
			RenderSystem.setProjectionMatrix(PEND_PROJ, pendSorting);   // copied inside
			mv.set(PEND_VIEW);
			RenderSystem.applyModelViewMatrix();
			// Copy, don't mulPose: mulPose allocates a Matrix3f for non-translations.
			POST_POSE.setIdentity();
			POST_POSE.last().pose().set(PEND_POSE);
			POST_POSE.last().normal().set(PEND_NORMAL);
			drawWorld(mc, POST_POSE, pendCam, POST_LINES);
		} finally {
			mv.popMatrix();
			RenderSystem.applyModelViewMatrix();
			RenderSystem.setProjectionMatrix(oldProj, oldSorting);
		}
	}

	private static void drawWorld(Minecraft mc, PoseStack pose, Vec3 c, MultiBufferSource.BufferSource buf) {
		String dim = mc.level.dimension().location().toString();
		// Beam hard-cutoff distance (no fade): the player's render distance, in blocks.
		double cutoff = mc.options.getEffectiveRenderDistance() * 16.0;

		try {
			for (Waypoints.Waypoint w : Waypoints.all()) {
				if (!w.enabled || !dim.equals(w.dimension)) {
					continue;
				}
				double dx = (w.x + 0.5) - c.x;
				double dz = (w.z + 0.5) - c.z;
				double dist = Math.sqrt(dx * dx + (w.y + 1 - c.y) * (w.y + 1 - c.y) + dz * dz);

				// Beam column (y-3 .. y+160), depth-tested camera-facing quad.
				if (w.showBeam && dist <= cutoff) {
					drawBeam(pose, dx, dz, w.y - 3 - c.y, w.y + 160 - c.y, w.color);
				}
				if (w.highlightBlock) {
					drawBlockOutline(pose, buf, (float) (w.x - c.x), (float) (w.y - c.y), (float) (w.z - c.z), w.color);
				}
			}
		} finally {
			// Always flush, also after a throw mid-loop, so no half-built vertices
			// stay in either buffer for the next caller (normal path: same two
			// calls, same order, as before).
			try {
				buf.endBatch();
			} finally {
				OriginRenderTypes.draw();   // the beams
			}
		}
	}

	// The beam is REAL geometry (ThickLine.vertical), not a RenderType.lines()
	// segment: lines are widened in screen space from their projected endpoints,
	// and a 163-block segment with one end behind the camera projected
	// sign-flipped -- the beam swung and bent as the camera turned. Its width
	// grows with horizontal distance (~2.5px on screen, like the old line) so it
	// stays readable out to the render-distance cutoff, never thinner than
	// 0.2 blocks up close. Depth-tested, so terrain still hides it.
	private static void drawBeam(PoseStack pose, double x, double z, double y0, double y1, int color) {
		int argb = (color >>> 24) == 0 ? (0xFF000000 | (color & 0xFFFFFF)) : color;
		float r = ((argb >> 16) & 0xFF) / 255f, g = ((argb >> 8) & 0xFF) / 255f, b = (argb & 0xFF) / 255f;
		float a = ((argb >>> 24) & 0xFF) / 255f;
		double halfWidth = Math.max(0.1, Math.sqrt(x * x + z * z) * 0.0016);
		ThickLine.vertical(OriginRenderTypes.begin(), pose.last(), x, z, y0, y1, halfWidth, r, g, b, a);
	}

	// "Highlight Block": a clean 12-edge box outline around the waypoint's block,
	// in the waypoint's colour. Entirely self-contained — independent of the Block
	// Outline mod (which only styles the crosshair-target block).
	private static void drawBlockOutline(PoseStack pose, MultiBufferSource buf,
										 float x, float y, float z, int color) {
		VertexConsumer vc = buf.getBuffer(RenderType.lines());
		var last = pose.last();
		Matrix4f m = last.pose();
		int argb = (color >>> 24) == 0 ? (0xFF000000 | (color & 0xFFFFFF)) : color;
		float x1 = x + 1f, y1 = y + 1f, z1 = z + 1f;
		// bottom square, top square, then the four verticals
		edge(vc, m, last, x, y, z, x1, y, z, argb);
		edge(vc, m, last, x1, y, z, x1, y, z1, argb);
		edge(vc, m, last, x1, y, z1, x, y, z1, argb);
		edge(vc, m, last, x, y, z1, x, y, z, argb);
		edge(vc, m, last, x, y1, z, x1, y1, z, argb);
		edge(vc, m, last, x1, y1, z, x1, y1, z1, argb);
		edge(vc, m, last, x1, y1, z1, x, y1, z1, argb);
		edge(vc, m, last, x, y1, z1, x, y1, z, argb);
		edge(vc, m, last, x, y, z, x, y1, z, argb);
		edge(vc, m, last, x1, y, z, x1, y1, z, argb);
		edge(vc, m, last, x1, y, z1, x1, y1, z1, argb);
		edge(vc, m, last, x, y, z1, x, y1, z1, argb);
	}

	private static void edge(VertexConsumer vc, Matrix4f m, PoseStack.Pose pose,
							 float ax, float ay, float az, float bx, float by, float bz, int argb) {
		float nx = bx - ax, ny = by - ay, nz = bz - az;
		float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
		if (len < 1.0e-5f) {
			return;
		}
		nx /= len;
		ny /= len;
		nz /= len;
		vc.addVertex(m, ax, ay, az).setColor(argb).setNormal(pose, nx, ny, nz);
		vc.addVertex(m, bx, by, bz).setColor(argb).setNormal(pose, nx, ny, nz);
	}

	/** Is an Iris shader pack rendering the world? Iris is bundled jar-in-jar
	 *  on 1.21.1 (build.gradle include), so this is the direct API call, as in
	 *  OriginRender.shaderPackOn(); the loaded check is resolved once. */
	static final class ShaderPack {
		private static final boolean IRIS =
				net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("iris");

		static boolean inUse() {
			if (!IRIS) {
				return false;
			}
			try {
				return net.irisshaders.iris.api.v0.IrisApi.getInstance().isShaderPackInUse();
			} catch (Throwable t) {
				return false;
			}
		}
	}
}
