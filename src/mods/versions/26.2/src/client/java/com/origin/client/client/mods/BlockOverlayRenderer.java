package com.origin.client.client.mods;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.origin.client.client.gui.OriginColorPicker;
import com.origin.client.client.render.OriginRenderTypes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.BlockOutlineRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

// Block Outline + Block Overlay, drawn from the END_MAIN world event
// (renderFromWorldEvent) off the extracted blockOutlineRenderState -- the path
// proven to run under Sodium+Iris; Fabric's BEFORE_BLOCK_OUTLINE event proved
// fragile on 1.21.11. LevelRendererMixin's renderBlockOutline hook only CANCELS
// vanilla's outline whenever wouldDraw() says Origin is drawing it.
//   outline  — custom-coloured selection outline; width 1..10 (ThickLine boxes).
//   overlay  — a translucent fill over the block's shape (overlayColor).
//   side     — with overlay on, fill ONLY the face the crosshair is on.
// renderDirect returns TRUE when Origin drew. With the mod (or both sub-toggles)
// off it draws the thin "hairline" version of vanilla's black outline, like every
// other version; vanilla keeps only empty shapes (no outline on grass) and the
// accessibility High Contrast outline. Only drawn on frames where vanilla itself
// would outline (outlineAllowedThisFrame).
//
// Everything is drawn through OriginRenderTypes' private buffer and flushed
// before returning, i.e. on the spot with the frame's matrices and depth --
// see OriginRenderTypes for why the frame's shared buffer showed the hidden
// back edges through the block.
public final class BlockOverlayRenderer {
	private BlockOverlayRenderer() {
	}

	/**
	 * END_MAIN entry point — the ONE world-render path proven to execute in the
	 * real launcher instance under Sodium+Iris (the chunk-border LineWidth crash
	 * fired from it). Vanilla still extracts blockOutlineRenderState every frame
	 * even when Sodium bypasses vanilla's own outline draw, so we read it from
	 * the world state here and draw ourselves. Vanilla's outline is suppressed
	 * separately by LevelRendererMixin (cancel-only, no drawing there).
	 */
	public static void renderFromWorldEvent(net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext context) {
		// One-time diagnostic breadcrumbs: each branch logs once so a real-world
		// session pinpoints exactly where this path dies (Sodium/Iris debugging).
		if (!loggedEvent) {
			loggedEvent = true;
			com.origin.client.OriginClient.LOGGER.info("Origin[outline]: END_MAIN fired (matrices={}, consumers={})",
					context.poseStack() != null, context.consumers() != null);
		}
		// Vanilla's own per-frame "draw the outline?" decision (GameRenderer.
		// shouldRenderBlockOutline: off with F1 / hidden GUI, in spectator without an
		// interactable target, on blocks adventure mode can't touch...). The extracted
		// outline state alone does NOT carry it -- extractBlockOutline fills it for any
		// targeted block -- so without this check the outline showed through F1.
		// Captured from END_EXTRACTION (outlineAllowedThisFrame), which fires earlier
		// in the same renderLevel call.
		if (!outlineAllowedThisFrame) {
			return;
		}
		BlockOutlineRenderState octx = context.levelState().blockOutlineRenderState;
		if (octx == null) {
			return; // no block targeted this frame (or state not extracted)
		}
		if (!loggedTarget) {
			loggedTarget = true;
			com.origin.client.OriginClient.LOGGER.info("Origin[outline]: target state present at {}", octx.pos());
		}
		if (context.poseStack() == null) {
			return;
		}
		try {
			boolean drew = renderDirect(context.poseStack(), context.levelState().cameraRenderState.pos, octx);
			if (!drew && !loggedNoDraw) {
				loggedNoDraw = true;
				com.origin.client.OriginClient.LOGGER.info("Origin[outline]: renderDirect returned false (on={}, outline={}, overlay={})",
						Mods.on("blockoverlay"), Mods.bool("blockoverlay", "outline"), Mods.bool("blockoverlay", "overlay"));
			}
		} catch (Throwable t) {
			if (!loggedThrow) {
				loggedThrow = true;
				com.origin.client.OriginClient.LOGGER.error("Origin[outline]: draw threw", t);
			}
		}
	}

	/** Vanilla's renderLevel(..., renderBlockOutline, ...) flag for the current frame,
	 *  published by OriginClientMod's END_EXTRACTION handler (Fabric's
	 *  WorldExtractionContext.blockOutlines() is exactly that parameter). */
	public static volatile boolean outlineAllowedThisFrame = true;

	private static boolean loggedNoDraw = false;
	private static boolean loggedThrow = false;

	private static boolean loggedEvent = false;
	private static boolean loggedTarget = false;

	/** True when Origin draws the outline for this target (and so cancels vanilla's):
	 *  the custom outline/overlay when the mod is on, otherwise the hairline. */
	public static boolean wouldDraw(BlockOutlineRenderState octx) {
		if (octx == null) {
			return false;
		}
		boolean modOn = Mods.on("blockoverlay");
		boolean outline = modOn && Mods.bool("blockoverlay", "outline");
		boolean overlay = modOn && Mods.bool("blockoverlay", "overlay");
		boolean hasShape = octx.shape() != null && !octx.shape().isEmpty();
		if (!outline && !overlay) {
			// Hairline default (see renderDirect); High Contrast stays vanilla's.
			return hasShape && !octx.highContrast();
		}
		return hasShape || Mods.bool("blockoverlay", "showHiddenFoliage");
	}

	/** Returns true when Origin drew the outline/overlay (caller cancels vanilla). */
	public static boolean renderDirect(PoseStack poseStack, Vec3 cam, BlockOutlineRenderState octx) {
		if (!wouldDraw(octx)) {
			return false;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return false;
		}
		boolean modOn = Mods.on("blockoverlay");
		boolean outline = modOn && Mods.bool("blockoverlay", "outline");
		boolean overlay = modOn && Mods.bool("blockoverlay", "overlay");

		// Mod off (or both sub-toggles off): the "normal" outline -- vanilla's black
		// 40% look, but drawn as a HAIRLINE (Will: thinner than vanilla, whose GL
		// line scales up to ~2.5px with resolution), exactly like every other
		// version. Same ThickLine quads as the custom outline at a fixed tiny
		// thickness, depth-tested like vanilla. wouldDraw() already excluded empty
		// shapes (vanilla's "no outline on grass") and High Contrast.
		if (!outline && !overlay) {
			VoxelShape vs = octx.shape();
			BlockPos hpPos = octx.pos();
			double hx = hpPos.getX() - cam.x, hy = hpPos.getY() - cam.y, hz = hpPos.getZ() - cam.z;
			net.minecraft.world.phys.AABB vb = vs.bounds();
			double vcx = (vb.minX + vb.maxX) / 2.0 + hx;
			double vcy = (vb.minY + vb.maxY) / 2.0 + hy;
			double vcz = (vb.minZ + vb.maxZ) / 2.0 + hz;
			PoseStack.Pose hp = poseStack.last();
			VertexConsumer vq = OriginRenderTypes.begin();
			vs.forAllEdges((x1, y1, z1, x2, y2, z2) ->
					com.origin.client.client.render.ThickLine.edge(vq, hp,
							x1 + hx, y1 + hy, z1 + hz, x2 + hx, y2 + hy, z2 + hz,
							vcx, vcy, vcz, 0.002, 0f, 0f, 0f, 0.4f));
			OriginRenderTypes.draw();
			return true;
		}
		BlockPos pos = octx.pos();
		// 1.21.11: the render state's 4-arg ctor only fills shape(); the other
		// shape fields (interactionShape etc.) are NULL in real gameplay — that
		// NPE was the silently-swallowed killer of every outline attempt.
		VoxelShape shape = octx.shape();
		if (shape == null || shape.isEmpty()) {
			// Show Hidden Foliage: grass/crops with an empty collision shape get
			// no outline normally — fall back to a full-block box so they're
			// highlighted. Off (default) keeps vanilla's "no outline" behavior.
			if (!Mods.bool("blockoverlay", "showHiddenFoliage")) {
				return false;
			}
			shape = net.minecraft.world.phys.shapes.Shapes.block();
		}
		double ox = pos.getX() - cam.x;
		double oy = pos.getY() - cam.y;
		double oz = pos.getZ() - cam.z;
		PoseStack.Pose pose = poseStack.last();

		if (overlay) {
			int col = OriginColorPicker.liveColor("blockoverlay", "overlayColor");
			Direction only = null;
			if (Mods.bool("blockoverlay", "side") && mc.hitResult instanceof BlockHitResult bhr
					&& bhr.getBlockPos().equals(pos)) {
				only = bhr.getDirection();
			}
			VertexConsumer q = OriginRenderTypes.begin();
			Direction faceOnly = only;
			shape.forAllBoxes((minX, minY, minZ, maxX, maxY, maxZ) ->
					fillBox(q, pose, minX + ox, minY + oy, minZ + oz, maxX + ox, maxY + oy, maxZ + oz, col, faceOnly));
		}

		// The overlay OVERRIDES the outline. The toggle stays on and keeps its
		// setting, but when the overlay is painting the block's faces there is
		// nothing for an outline to add -- drawing both stacked a line on top of a
		// filled face and, because the two use different geometry and depth
		// handling, left a visible seam. Skipping it here is what makes
		// "outline + overlay" look identical to "overlay" alone (Will).
		if (outline && !overlay) {
			int col = OriginColorPicker.liveColor("blockoverlay", "color");
			float r = ((col >> 16) & 0xFF) / 255f, g = ((col >> 8) & 0xFF) / 255f, b = (col & 0xFF) / 255f;
			float a = ((col >>> 24) & 0xFF) / 255f;
			if (a <= 0f) {
				a = 1f;
			}
			// ONE thick line per edge -- not a stack of thin ones. Each edge is a
			// solid box (ThickLine) drawn as POSITION_COLOR quads (no LineWidth
			// element, so the 1.21.11 lines-format crash can't apply),
			// sized by the slider and biased inward so its outer face stays on the
			// block surface. The centre comes from the SHAPE's bounds, not the
			// block's, so slabs/stairs/fences thicken instead of translating.
			double thickness = Math.max(1, Math.min(10, Mods.num("blockoverlay", "thickness")));
			double t = 0.004 + (thickness - 1) * 0.005;
			net.minecraft.world.phys.AABB bounds = shape.bounds();
			double cx = (bounds.minX + bounds.maxX) / 2.0 + ox;
			double cy = (bounds.minY + bounds.maxY) / 2.0 + oy;
			double cz = (bounds.minZ + bounds.maxZ) / 2.0 + oz;
			VertexConsumer q = OriginRenderTypes.begin();
			// The alpha guard above reassigns `a`, so it can't be captured directly.
			float fr = r, fg = g, fb = b, fa = a;
			shape.forAllEdges((x1, y1, z1, x2, y2, z2) ->
					com.origin.client.client.render.ThickLine.edge(q, pose,
							x1 + ox, y1 + oy, z1 + oz, x2 + ox, y2 + oy, z2 + oz,
							cx, cy, cz, t, fr, fg, fb, fa));
		}
		OriginRenderTypes.draw();
		if (!loggedFirstDraw) {
			loggedFirstDraw = true;
			// One-time breadcrumb: proves in the log whether the outline draw path
			// actually runs in a real world (visibility debugging on 1.21.11).
			com.origin.client.OriginClient.LOGGER.info("Origin: block outline/overlay drew (outline={}, overlay={})", outline, overlay);
		}
		return true;
	}

	private static boolean loggedFirstDraw = false;

	// Fills a box's faces with a translucent colour (QUADS). `only` limits to a
	// single face; null fills all six. Faces are outset a hair to avoid z-fight.
	private static void fillBox(VertexConsumer q, PoseStack.Pose pose,
								double x0, double y0, double z0, double x1, double y1, double z1,
								int color, Direction only) {
		float r = ((color >> 16) & 0xFF) / 255f, g = ((color >> 8) & 0xFF) / 255f, b = (color & 0xFF) / 255f;
		float a = ((color >>> 24) & 0xFF) / 255f;
		if (a <= 0f) {
			a = 0.35f;
		}
		// Each face is pushed out along its own normal by this much, purely to
		// escape z-fighting with the block it's painted on. It was 0.002 (2cm at
		// block scale), which made the shell visibly larger than the block and left
		// a lip standing proud at every edge. 0.0005 still clears the depth buffer
		// but reads as paint ON the surface rather than a skin stretched over it.
		float e = 0.0005f;
		if (only == null || only == Direction.DOWN) {
			quad(q, pose, x0, y0 - e, z0, x0, y0 - e, z1, x1, y0 - e, z1, x1, y0 - e, z0, r, g, b, a);
		}
		if (only == null || only == Direction.UP) {
			quad(q, pose, x0, y1 + e, z0, x1, y1 + e, z0, x1, y1 + e, z1, x0, y1 + e, z1, r, g, b, a);
		}
		if (only == null || only == Direction.NORTH) {
			quad(q, pose, x0, y0, z0 - e, x1, y0, z0 - e, x1, y1, z0 - e, x0, y1, z0 - e, r, g, b, a);
		}
		if (only == null || only == Direction.SOUTH) {
			quad(q, pose, x0, y0, z1 + e, x0, y1, z1 + e, x1, y1, z1 + e, x1, y0, z1 + e, r, g, b, a);
		}
		if (only == null || only == Direction.WEST) {
			quad(q, pose, x0 - e, y0, z0, x0 - e, y1, z0, x0 - e, y1, z1, x0 - e, y0, z1, r, g, b, a);
		}
		if (only == null || only == Direction.EAST) {
			quad(q, pose, x1 + e, y0, z0, x1 + e, y0, z1, x1 + e, y1, z1, x1 + e, y1, z0, r, g, b, a);
		}
	}

	private static void quad(VertexConsumer q, PoseStack.Pose pose,
							 double ax, double ay, double az, double bx, double by, double bz,
							 double cx, double cy, double cz, double dx, double dy, double dz,
							 float r, float g, float b, float a) {
		q.addVertex(pose, (float) ax, (float) ay, (float) az).setColor(r, g, b, a);
		q.addVertex(pose, (float) bx, (float) by, (float) bz).setColor(r, g, b, a);
		q.addVertex(pose, (float) cx, (float) cy, (float) cz).setColor(r, g, b, a);
		q.addVertex(pose, (float) dx, (float) dy, (float) dz).setColor(r, g, b, a);
	}
}
