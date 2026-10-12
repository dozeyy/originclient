package com.origin.client.client.minimap;

import com.origin.client.client.OriginClientMod;
import com.origin.client.client.gui.OriginColorPicker;
import com.origin.client.client.gui.OriginUi;
import com.origin.client.client.mods.Mods;
import com.origin.client.client.theme.OriginTheme;
import com.origin.client.client.waypoints.Waypoints;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The full-screen map (the Minimap's "Open Map" key, M by default): the same terrain,
 * radar and waypoints as the HUD map, north-up and big, with scroll-to-zoom and
 * drag-to-pan. It shows what the minimap has already mapped around you -- the loaded
 * area, up to MinimapData.MAX_RADIUS chunks out -- not a saved whole-world atlas.
 */
final class MinimapScreen extends Screen {
	private static final int MARGIN = 20;
	private static final float MIN_ZOOM = 0.5f, MAX_ZOOM = 8f;
	private static float zoom = 2f;          // GUI px per block; kept between openings
	private double panX = 0, panZ = 0;       // map centre's offset from the player, in blocks
	private boolean dragging = false;
	private boolean openKeyHeld = true;      // the open key is down as we open; ignore it until released

	MinimapScreen() {
		super(Component.literal("Map"));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		g.fill(0, 0, width, height, 0xD80E1110);
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		super.render(g, mouseX, mouseY, partialTick);
		var mc = minecraft;
		if (mc == null || mc.player == null || mc.level == null) {
			return;
		}
		float halfW = (width - MARGIN * 2) / 2f, halfH = (height - MARGIN * 2 - 14) / 2f;
		float cx = width / 2f, cy = MARGIN + halfH;
		float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
		var p = mc.player;
		double px = p.xo + (p.getX() - p.xo) * partial, pz = p.zo + (p.getZ() - p.zo) * partial;
		MinimapRenderer.View v = new MinimapRenderer.View(cx, cy, halfW, halfH, false,
				px + panX, pz + panZ, zoom, 0f);
		try {
			MinimapData.update(mc, MinimapData.MAX_RADIUS * 16.0, MinimapHud.look(mc));
		} catch (Throwable t) {
			// terrain just stops updating this frame
		}

		// (1.21.6+: no flushes -- submission order + bounds put the panel under the map.)
		OriginUi.panel(g, (int) (cx - halfW) - 1, (int) (cy - halfH) - 1, (int) (halfW * 2) + 2, (int) (halfH * 2) + 2,
				4, OriginTheme.BG, OriginTheme.STROKE_STRONG);
		// The map is GuiMesh geometry (layered per this era -- see GuiMesh).
		{
			// Terrain only inside the scanned window (whole chunks around the player's chunk),
			// clipped to the visible area.
			int r = MinimapData.MAX_RADIUS;
			double wx0 = ((p.getBlockX() >> 4) - r) * 16.0, wz0 = ((p.getBlockZ() >> 4) - r) * 16.0;
			double wx1 = ((p.getBlockX() >> 4) + r + 1) * 16.0, wz1 = ((p.getBlockZ() >> 4) + r + 1) * 16.0;
			double vx0 = v.worldX(cx - halfW, cy), vx1 = v.worldX(cx + halfW, cy);
			double vz0 = v.worldZ(cx, cy - halfH), vz1 = v.worldZ(cx, cy + halfH);
			MinimapRenderer.terrainRect(g, v, Math.max(wx0, vx0), Math.max(wz0, vz0), Math.min(wx1, vx1), Math.min(wz1, vz1));
			MinimapRenderer.entities(g, mc, v, MinimapHud.radar(), partial);
			boolean showWp = Mods.bool(MinimapHud.MOD, "showWaypoints") && Mods.on("waypoints");
			if (showWp) {
				MinimapRenderer.waypoints(g, v, true, 3.5f);
				waypointNames(g, v);
			}
			float sx = v.sx(px, pz), sy = v.sy(px, pz);
			if (v.contains(sx, sy, 4)) {
				double yawRad = Math.toRadians(p.getViewYRot(partial));
				MinimapRenderer.playerArrow(g, sx, sy, (float) Math.atan2(-Math.sin(yawRad), -Math.cos(yawRad)),
						(float) Mods.num(MinimapHud.MOD, "playerMarkerSize") + 1,
						OriginColorPicker.liveColor(MinimapHud.MOD, "playerMarkerColor"));
			}
			MinimapRenderer.compass(g, mc, v, OriginTheme.TEXT, true);
		}

		// footer: what's under the cursor + how to use it
		String hint = "Scroll: zoom  ·  Drag: move  ·  Right-click: back to you";
		int fy = height - MARGIN + 2;
		if (v.contains(mouseX, mouseY, 0)) {
			int mx = (int) Math.floor(v.worldX(mouseX, mouseY)), mz = (int) Math.floor(v.worldZ(mouseX, mouseY));
			g.drawString(font, "X " + mx + "   Z " + mz, MARGIN, fy, OriginTheme.TEXT, true);
		}
		g.drawString(font, hint, width - MARGIN - font.width(hint), fy, OriginTheme.MUTED, true);
	}

	/** Each waypoint's name above its diamond (only the ones on the map). */
	private void waypointNames(GuiGraphics g, MinimapRenderer.View v) {
		String dim = Waypoints.currentDim();
		for (Waypoints.Waypoint wp : Waypoints.all()) {
			if (!wp.enabled || !dim.equals(wp.dimension)) {
				continue;
			}
			float x = v.sx(wp.x + 0.5, wp.z + 0.5), y = v.sy(wp.x + 0.5, wp.z + 0.5);
			if (!v.contains(x, y, 6)) {
				continue;
			}
			int tw = font.width(wp.name);
			g.drawString(font, wp.name, Math.round(x - tw / 2f), Math.round(y - 15), 0xFFFFFFFF, true);
		}
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double sx, double sy) {
		if (sy != 0) {
			zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom * (sy > 0 ? 1.25f : 0.8f)));
			return true;
		}
		return super.mouseScrolled(mx, my, sx, sy);
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (button == 0) {
			dragging = true;
			return true;
		}
		if (button == 1) {
			panX = panZ = 0;
			return true;
		}
		return super.mouseClicked(mx, my, button);
	}

	@Override
	public boolean mouseReleased(double mx, double my, int button) {
		if (button == 0) {
			dragging = false;
		}
		return super.mouseReleased(mx, my, button);
	}

	@Override
	public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
		if (dragging && button == 0) {
			double limit = MinimapData.MAX_RADIUS * 16.0;
			panX = Math.max(-limit, Math.min(limit, panX - dx / zoom));
			panZ = Math.max(-limit, Math.min(limit, panZ - dy / zoom));
			return true;
		}
		return super.mouseDragged(mx, my, button, dx, dy);
	}

	@Override
	public void tick() {
		super.tick();
		// The Open Map key toggles: release it once, then pressing it again closes the map.
		boolean down = OriginClientMod.isRawKeyDown(Mods.keyCode(MinimapHud.MOD, "openMapKey"));
		if (!down) {
			openKeyHeld = false;
		} else if (!openKeyHeld) {
			onClose();
		}
	}
}
