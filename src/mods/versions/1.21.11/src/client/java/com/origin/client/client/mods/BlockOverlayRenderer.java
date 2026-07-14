package com.origin.client.client.mods;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.origin.client.client.gui.OriginColorPicker;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.BlockOutlineRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

// Block Outline + Block Overlay, drawn from LevelRendererMixin's direct hook on
// vanilla's renderBlockOutline (the Lunar model: own the draw path; Fabric's
// BEFORE_BLOCK_OUTLINE event proved fragile on 1.21.11).
//   outline  — custom-coloured selection outline; width 1..10 via offset passes.
//   overlay  — a translucent fill over the block's shape (overlayColor).
//   side     — with overlay on, fill ONLY the face the crosshair is on.
// renderDirect returns TRUE when Origin drew (the mixin then cancels vanilla's
// white outline); false defers to vanilla entirely.
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
	public static void renderFromWorldEvent(net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext context) {
		// One-time diagnostic breadcrumbs: each branch logs once so a real-world
		// session pinpoints exactly where this path dies (Sodium/Iris debugging).
		if (!loggedEvent) {
			loggedEvent = true;
			com.origin.client.OriginClient.LOGGER.info("Origin[outline]: END_MAIN fired (matrices={}, consumers={})",
					context.matrices() != null, context.consumers() != null);
		}
		BlockOutlineRenderState octx = context.worldState().blockOutlineRenderState;
		if (octx == null) {
			return; // no block targeted this frame (or state not extracted)
		}
		if (!loggedTarget) {
			loggedTarget = true;
			com.origin.client.OriginClient.LOGGER.info("Origin[outline]: target state present at {}", octx.pos());
		}
		if (context.matrices() == null || context.consumers() == null) {
			return;
		}
		try {
			boolean drew = renderDirect(context.matrices(), context.consumers(), context.worldState().cameraRenderState.pos, octx);
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

	private static boolean loggedNoDraw = false;
	private static boolean loggedThrow = false;

	private static boolean loggedEvent = false;
	private static boolean loggedTarget = false;

	/** True when the mod would take over outline drawing for this target. */
	public static boolean wouldDraw(BlockOutlineRenderState octx) {
		return octx != null && Mods.on("blockoverlay")
				&& (Mods.bool("blockoverlay", "outline") || Mods.bool("blockoverlay", "overlay"))
				&& (octx.shape() != null || Mods.bool("blockoverlay", "showHiddenFoliage"));
	}

	/** Returns true when Origin drew the outline/overlay (caller cancels vanilla). */
	public static boolean renderDirect(PoseStack poseStack, MultiBufferSource consumers, Vec3 cam, BlockOutlineRenderState octx) {
		if (octx == null || !Mods.on("blockoverlay")) {
			return false;
		}
		boolean outline = Mods.bool("blockoverlay", "outline");
		boolean overlay = Mods.bool("blockoverlay", "overlay");
		if (!outline && !overlay) {
			return false;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return false;
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
			VertexConsumer q = consumers.getBuffer(RenderTypes.debugQuads());
			Direction faceOnly = only;
			shape.forAllBoxes((minX, minY, minZ, maxX, maxY, maxZ) ->
					fillBox(q, pose, minX + ox, minY + oy, minZ + oz, maxX + ox, maxY + oy, maxZ + oz, col, faceOnly));
		}

		if (outline) {
			int col = OriginColorPicker.liveColor("blockoverlay", "color");
			int passes = (int) Math.max(1, Math.min(10, Mods.num("blockoverlay", "thickness")));
			VertexConsumer lines = consumers.getBuffer(RenderTypes.lines());
			float r = ((col >> 16) & 0xFF) / 255f, g = ((col >> 8) & 0xFF) / 255f, b = (col & 0xFF) / 255f;
			float a = ((col >>> 24) & 0xFF) / 255f;
			if (a <= 0f) {
				a = 1f;
			}
			for (int i = 0; i < passes; i++) {
				float grow = i * 0.005f;
				float fr = r, fg = g, fb = b, fa = a;
				shape.forAllEdges((x1, y1, z1, x2, y2, z2) -> {
					float ax = (float) (x1 + ox) + (x1 < 0.5 ? -grow : grow);
					float ay = (float) (y1 + oy) + (y1 < 0.5 ? -grow : grow);
					float az = (float) (z1 + oz) + (z1 < 0.5 ? -grow : grow);
					float bx = (float) (x2 + ox) + (x2 < 0.5 ? -grow : grow);
					float by = (float) (y2 + oy) + (y2 < 0.5 ? -grow : grow);
					float bz = (float) (z2 + oz) + (z2 < 0.5 ? -grow : grow);
					float nx = bx - ax, ny = by - ay, nz = bz - az;
					float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
					if (len > 0) {
						nx /= len;
						ny /= len;
						nz /= len;
					}
					// 1.21.11: lines format requires a per-vertex LineWidth element, else
					// "Missing elements in vertex: LineWidth" crashes at the buffer flush.
					lines.addVertex(pose, ax, ay, az).setColor(fr, fg, fb, fa).setNormal(pose, nx, ny, nz).setLineWidth(Math.max(1.0f, passes));
					lines.addVertex(pose, bx, by, bz).setColor(fr, fg, fb, fa).setNormal(pose, nx, ny, nz).setLineWidth(Math.max(1.0f, passes));
				});
			}
		}
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
		float e = 0.002f;
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
