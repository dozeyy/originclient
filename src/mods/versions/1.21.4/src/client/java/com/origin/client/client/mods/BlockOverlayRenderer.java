package com.origin.client.client.mods;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.origin.client.client.gui.OriginColorPicker;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

// Block Outline + Block Overlay, via Fabric's BLOCK_OUTLINE event (which hands
// us both the lines buffer AND the world matrix, so we can draw a filled
// overlay the old renderHitOutline mixin couldn't).
//   outline  — custom-coloured selection outline; width 1..10 via offset passes.
//   overlay  — a translucent fill over the block's shape (overlayColor).
//   side     — with overlay on, fill ONLY the face the crosshair is on.
// Returning false cancels vanilla's white outline so only ours shows; if both
// sub-toggles are off we defer to vanilla.
public final class BlockOverlayRenderer {
	private BlockOverlayRenderer() {
	}

	public static boolean onBlockOutline(WorldRenderContext wctx, WorldRenderContext.BlockOutlineContext octx) {
		if (!Mods.on("blockoverlay")) {
			return true;
		}
		boolean outline = Mods.bool("blockoverlay", "outline");
		boolean overlay = Mods.bool("blockoverlay", "overlay");
		if (!outline && !overlay) {
			return true;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || wctx.matrixStack() == null || wctx.consumers() == null) {
			return true;
		}
		BlockPos pos = octx.blockPos();
		BlockState state = octx.blockState();
		VoxelShape shape = state.getShape(mc.level, pos, CollisionContext.of(octx.entity()));
		if (shape.isEmpty()) {
			// Show Hidden Foliage: grass/crops with an empty collision shape get
			// no outline normally — fall back to a full-block box so they're
			// highlighted. Off (default) keeps vanilla's "no outline" behavior.
			if (!Mods.bool("blockoverlay", "showHiddenFoliage")) {
				return true;
			}
			shape = net.minecraft.world.phys.shapes.Shapes.block();
		}
		double ox = pos.getX() - octx.cameraX();
		double oy = pos.getY() - octx.cameraY();
		double oz = pos.getZ() - octx.cameraZ();
		PoseStack.Pose pose = wctx.matrixStack().last();

		if (overlay) {
			int col = OriginColorPicker.liveColor("blockoverlay", "overlayColor");
			Direction only = null;
			if (Mods.bool("blockoverlay", "side") && mc.hitResult instanceof BlockHitResult bhr
					&& bhr.getBlockPos().equals(pos)) {
				only = bhr.getDirection();
			}
			VertexConsumer q = wctx.consumers().getBuffer(RenderType.debugQuads());
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
			// ONE thick line per edge -- not a stack of thin ones.
			//
			// This loop used to run `thickness` passes of the whole wireframe,
			// each nudged a few mm further out, because RenderType.lines() pins GL
			// line width and can't be told to draw thicker. That never worked: the
			// copies converged into a single line at range and fanned out into
			// little inward dots at the corners, which is exactly what Will saw.
			// It also grew OUTWARD, floating the outline off the block as a cage.
			//
			// Now each edge is one solid box (ThickLine), sized by the slider and
			// biased inward so its outer face stays on the block surface. Raising
			// thickness makes the single line fatter, painted on the block.
			//
			// The centre comes from the SHAPE's bounds, not the block's. The old
			// code took its sign from `coord < 0.5` -- the block's midpoint -- so
			// any shape not straddling 0.5 (slab, stairs, fence, pane) had both
			// endpoints pushed the same way, TRANSLATING the outline off the
			// surface instead of thickening it.
			double thickness = Math.max(1, Math.min(10, Mods.num("blockoverlay", "thickness")));
			double t = 0.004 + (thickness - 1) * 0.005;
			net.minecraft.world.phys.AABB bounds = shape.bounds();
			double cx = (bounds.minX + bounds.maxX) / 2.0 + ox;
			double cy = (bounds.minY + bounds.maxY) / 2.0 + oy;
			double cz = (bounds.minZ + bounds.maxZ) / 2.0 + oz;
			VertexConsumer q = wctx.consumers().getBuffer(RenderType.debugQuads());
			// The alpha guard above reassigns `a`, so it can't be captured directly.
			float fr = r, fg = g, fb = b, fa = a;
			shape.forAllEdges((x1, y1, z1, x2, y2, z2) ->
					com.origin.client.client.render.ThickLine.edge(q, pose,
							x1 + ox, y1 + oy, z1 + oz, x2 + ox, y2 + oy, z2 + oz,
							cx, cy, cz, t, fr, fg, fb, fa));
		}
		return false;
	}

	// Fills a box's faces with a translucent colour (QUADS). `only` limits to a
	// single face; null fills all six.
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
