package com.origin.client.client.mods;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.origin.client.client.gui.OriginColorPicker;
import com.origin.client.client.render.ThickLine;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.BlockOutlineRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

// Block Outline + Block Overlay for 26.2. Driven from Fabric's BEFORE_BLOCK_OUTLINE
// event: returning FALSE cancels vanilla's white selection box, so Origin owns the
// draw with NO LevelRenderer mixin needed (unlike 1.21.11, which had to). Geometry
// goes through the submit pipeline — submitCustomGeometry + RenderTypes.debugQuads()
// (POSITION_COLOR, so no LineWidth element) — the same mechanism OriginWorldRender
// uses for chunk borders.
//   outline — thick coloured selection edges (ThickLine, real geometry), width 1..10.
//   overlay — a translucent fill over the block's shape (overlayColor).
//   side    — with overlay on, fill ONLY the face the crosshair is on.
// Coordinates are camera-relative (the pose is the view transform with the camera at
// the origin), matching how vanilla submits world geometry.
public final class BlockOverlayRenderer {
	private BlockOverlayRenderer() {
	}

	/** BEFORE_BLOCK_OUTLINE handler. Returns TRUE to let vanilla draw its outline,
	 *  FALSE when Origin drew the outline/overlay itself. Fail-soft to vanilla. */
	public static boolean beforeBlockOutline(LevelRenderContext ctx, BlockOutlineRenderState octx) {
		try {
			if (octx == null || !Mods.on("blockoverlay")) {
				return true;
			}
			boolean outline = Mods.bool("blockoverlay", "outline");
			boolean overlay = Mods.bool("blockoverlay", "overlay");
			if (!outline && !overlay) {
				return true;
			}
			Minecraft mc = Minecraft.getInstance();
			if (mc.level == null) {
				return true;
			}
			BlockPos pos = octx.pos();
			VoxelShape shape = octx.shape();
			if (shape == null || shape.isEmpty()) {
				// Show Hidden Foliage: grass/crops with an empty collision shape get no
				// outline normally — fall back to a full-block box so they're highlighted.
				if (!Mods.bool("blockoverlay", "showHiddenFoliage")) {
					return true;
				}
				shape = Shapes.block();
			}
			Vec3 cam = mc.gameRenderer.mainCamera().position();
			double ox = pos.getX() - cam.x, oy = pos.getY() - cam.y, oz = pos.getZ() - cam.z;
			VoxelShape fshape = shape;

			ctx.submitNodeCollector().submitCustomGeometry(ctx.poseStack(), RenderTypes.debugQuads(), (pose, q) -> {
				if (overlay) {
					int col = OriginColorPicker.liveColor("blockoverlay", "overlayColor");
					Direction only = null;
					if (Mods.bool("blockoverlay", "side") && mc.hitResult instanceof BlockHitResult bhr
							&& bhr.getBlockPos().equals(pos)) {
						only = bhr.getDirection();
					}
					Direction faceOnly = only;
					fshape.forAllBoxes((minX, minY, minZ, maxX, maxY, maxZ) ->
							fillBox(q, pose, minX + ox, minY + oy, minZ + oz, maxX + ox, maxY + oy, maxZ + oz, col, faceOnly));
				}
				// Overlay OVERRIDES outline: with faces painted there is nothing for an
				// outline to add, and stacking both left a visible seam (Will). So the
				// outline only draws when overlay is off.
				if (outline && !overlay) {
					int col = OriginColorPicker.liveColor("blockoverlay", "color");
					float r = ((col >> 16) & 0xFF) / 255f, g = ((col >> 8) & 0xFF) / 255f, b = (col & 0xFF) / 255f;
					float a = ((col >>> 24) & 0xFF) / 255f;
					if (a <= 0f) {
						a = 1f;
					}
					double thickness = Math.max(1, Math.min(10, Mods.num("blockoverlay", "thickness")));
					double t = 0.004 + (thickness - 1) * 0.005;
					net.minecraft.world.phys.AABB bounds = fshape.bounds();
					double cx = (bounds.minX + bounds.maxX) / 2.0 + ox;
					double cy = (bounds.minY + bounds.maxY) / 2.0 + oy;
					double cz = (bounds.minZ + bounds.maxZ) / 2.0 + oz;
					float fr = r, fg = g, fb = b, fa = a;
					fshape.forAllEdges((x1, y1, z1, x2, y2, z2) ->
							ThickLine.edge(q, pose, x1 + ox, y1 + oy, z1 + oz, x2 + ox, y2 + oy, z2 + oz,
									cx, cy, cz, t, fr, fg, fb, fa));
				}
			});
			return false; // Origin drew — cancel vanilla's outline
		} catch (Throwable t) {
			return true; // fail-soft: let vanilla draw
		}
	}

	// Fills a box's faces with a translucent colour (QUADS). `only` limits to a single
	// face; null fills all six. Faces are outset a hair to avoid z-fighting.
	private static void fillBox(VertexConsumer q, PoseStack.Pose pose,
								double x0, double y0, double z0, double x1, double y1, double z1,
								int color, Direction only) {
		float r = ((color >> 16) & 0xFF) / 255f, g = ((color >> 8) & 0xFF) / 255f, b = (color & 0xFF) / 255f;
		float a = ((color >>> 24) & 0xFF) / 255f;
		if (a <= 0f) {
			a = 0.35f;
		}
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
