package com.origin.client.client.mods;

import com.origin.client.client.gui.OriginColorPicker;
import com.origin.client.client.render.OriginRenderTypes;
import com.origin.client.client.render.ThickLine;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MovingObjectPosition;

// Block Outline + Block Overlay, via Forge's DrawBlockHighlightEvent (fired by
// EntityRenderer.renderWorldPass right where vanilla would call
// RenderGlobal.drawSelectionBox, under the camera's GL model-view, with the
// depth buffer of the solid world -- the exact place and state vanilla's own
// outline uses).
//   outline  — custom-coloured selection outline; width 1..10 (ThickLine boxes).
//   overlay  — a translucent fill over the block's selection box (overlayColor).
//   side     — with overlay on, fill ONLY the face the crosshair is on.
// Origin ALWAYS replaces vanilla's outline (the hook cancels the event) whenever
// there is a box to outline: with the mod off it still draws, as the thin
// "hairline" version of vanilla's black outline.
//
// Everything is drawn through OriginRenderTypes' private buffer and flushed
// before returning, at outline time with the right matrices and depth.
//
// 1.8.9: blocks have ONE selection box (Block.getSelectedBoundingBox after
// setBlockBoundsBasedOnState), not a VoxelShape -- stairs/fences outline their
// bounding box, as vanilla 1.8.9 does. Vertices are camera-relative: the block
// minus the player's interpolated position (what drawSelectionBox subtracts).
// Every raytraced block has a box on 1.8.9 (grass included), so "Show Hidden
// Foliage" has nothing to un-hide here.
public final class BlockOverlayRenderer {
	private BlockOverlayRenderer() {
	}

	/**
	 * From WorldHooks' DrawBlockHighlightEvent handler. Returns true when vanilla
	 * should draw its own outline (nothing for Origin to do), false when Origin
	 * drew and the event must be cancelled.
	 */
	public static boolean onBlockOutline(EntityPlayer player, MovingObjectPosition target, int subId, float partialTicks) {
		Minecraft mc = Minecraft.getMinecraft();
		if (mc.theWorld == null || player == null || target == null
				|| target.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) {
			return true;
		}
		if (subId != 0) {
			// vanilla's drawSelectionBox only draws for pass 0; never draw twice
			return false;
		}
		BlockPos pos = target.getBlockPos();
		Block block = mc.theWorld.getBlockState(pos).getBlock();
		if (block.getMaterial() == Material.air || !mc.theWorld.getWorldBorder().contains(pos)) {
			return true;   // vanilla draws nothing here either
		}
		block.setBlockBoundsBasedOnState(mc.theWorld, pos);
		AxisAlignedBB sel = block.getSelectedBoundingBox(mc.theWorld, pos);
		if (sel == null) {
			return true;
		}
		double px = player.lastTickPosX + (player.posX - player.lastTickPosX) * partialTicks;
		double py = player.lastTickPosY + (player.posY - player.lastTickPosY) * partialTicks;
		double pz = player.lastTickPosZ + (player.posZ - player.lastTickPosZ) * partialTicks;
		AxisAlignedBB box = sel.offset(-px, -py, -pz);

		boolean modOn = Mods.on("blockoverlay");
		boolean outline = modOn && Mods.bool("blockoverlay", "outline");
		boolean overlay = modOn && Mods.bool("blockoverlay", "overlay");

		double cx = (box.minX + box.maxX) / 2.0;
		double cy = (box.minY + box.maxY) / 2.0;
		double cz = (box.minZ + box.maxZ) / 2.0;

		// Mod off (or both sub-toggles off): the "normal" outline — vanilla's black
		// 40% look, but drawn as a HAIRLINE (Will: thinner than vanilla, whose GL
		// line is ~2px wide). Same ThickLine quads as the custom outline at a fixed
		// tiny thickness, depth-tested like vanilla.
		if (!outline && !overlay) {
			WorldRenderer vq = OriginRenderTypes.begin();
			edges(vq, box, cx, cy, cz, 0.002, 0f, 0f, 0f, 0.4f);
			OriginRenderTypes.draw();
			return false;
		}

		if (overlay) {
			int col = OriginColorPicker.liveColor("blockoverlay", "overlayColor");
			EnumFacing only = null;
			if (Mods.bool("blockoverlay", "side")) {
				only = target.sideHit;
			}
			fillBox(OriginRenderTypes.begin(), box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, col, only);
		}

		// The overlay OVERRIDES the outline. The toggle stays on and keeps its
		// setting, but when the overlay is painting the block's faces there is
		// nothing for an outline to add -- drawing both stacked a line on top of a
		// filled face and left a visible seam. Skipping it here is what makes
		// "outline + overlay" look identical to "overlay" alone (Will).
		if (outline && !overlay) {
			int col = OriginColorPicker.liveColor("blockoverlay", "color");
			float r = ((col >> 16) & 0xFF) / 255f, g = ((col >> 8) & 0xFF) / 255f, b = (col & 0xFF) / 255f;
			float a = ((col >>> 24) & 0xFF) / 255f;
			if (a <= 0f) {
				a = 1f;
			}
			// ONE thick line per edge -- not a stack of thin ones. Each edge is a
			// solid box (ThickLine), sized by the slider and biased inward so its
			// outer face stays on the block surface.
			double thickness = Math.max(1, Math.min(10, Mods.num("blockoverlay", "thickness")));
			double t = 0.004 + (thickness - 1) * 0.005;
			edges(OriginRenderTypes.begin(), box, cx, cy, cz, t, r, g, b, a);
		}
		OriginRenderTypes.draw();
		return false;
	}

	/** The 12 edges of a box as ThickLine boxes (1.16.5's VoxelShape.forAllEdges for one box). */
	private static void edges(WorldRenderer q, AxisAlignedBB box, double cx, double cy, double cz, double t,
							  float r, float g, float b, float a) {
		double[] xs = {box.minX, box.maxX}, ys = {box.minY, box.maxY}, zs = {box.minZ, box.maxZ};
		for (double y : ys) {
			for (double z : zs) {
				ThickLine.edge(q, box.minX, y, z, box.maxX, y, z, cx, cy, cz, t, r, g, b, a);
			}
		}
		for (double x : xs) {
			for (double z : zs) {
				ThickLine.edge(q, x, box.minY, z, x, box.maxY, z, cx, cy, cz, t, r, g, b, a);
			}
		}
		for (double x : xs) {
			for (double y : ys) {
				ThickLine.edge(q, x, y, box.minZ, x, y, box.maxZ, cx, cy, cz, t, r, g, b, a);
			}
		}
	}

	// Fills a box's faces with a translucent colour (QUADS). `only` limits to a
	// single face; null fills all six. Faces are outset a hair to avoid z-fight.
	private static void fillBox(WorldRenderer q,
								double x0, double y0, double z0, double x1, double y1, double z1,
								int color, EnumFacing only) {
		float r = ((color >> 16) & 0xFF) / 255f, g = ((color >> 8) & 0xFF) / 255f, b = (color & 0xFF) / 255f;
		float a = ((color >>> 24) & 0xFF) / 255f;
		if (a <= 0f) {
			a = 0.35f;
		}
		double e = 0.002;
		if (only == null || only == EnumFacing.DOWN) {
			quad(q, x0, y0 - e, z0, x0, y0 - e, z1, x1, y0 - e, z1, x1, y0 - e, z0, r, g, b, a);
		}
		if (only == null || only == EnumFacing.UP) {
			quad(q, x0, y1 + e, z0, x1, y1 + e, z0, x1, y1 + e, z1, x0, y1 + e, z1, r, g, b, a);
		}
		if (only == null || only == EnumFacing.NORTH) {
			quad(q, x0, y0, z0 - e, x1, y0, z0 - e, x1, y1, z0 - e, x0, y1, z0 - e, r, g, b, a);
		}
		if (only == null || only == EnumFacing.SOUTH) {
			quad(q, x0, y0, z1 + e, x0, y1, z1 + e, x1, y1, z1 + e, x1, y0, z1 + e, r, g, b, a);
		}
		if (only == null || only == EnumFacing.WEST) {
			quad(q, x0 - e, y0, z0, x0 - e, y1, z0, x0 - e, y1, z1, x0 - e, y0, z1, r, g, b, a);
		}
		if (only == null || only == EnumFacing.EAST) {
			quad(q, x1 + e, y0, z0, x1 + e, y0, z1, x1 + e, y1, z1, x1 + e, y1, z0, r, g, b, a);
		}
	}

	private static void quad(WorldRenderer q,
							 double ax, double ay, double az, double bx, double by, double bz,
							 double cx, double cy, double cz, double dx, double dy, double dz,
							 float r, float g, float b, float a) {
		q.pos(ax, ay, az).color(r, g, b, a).endVertex();
		q.pos(bx, by, bz).color(r, g, b, a).endVertex();
		q.pos(cx, cy, cz).color(r, g, b, a).endVertex();
		q.pos(dx, dy, dz).color(r, g, b, a).endVertex();
	}
}
