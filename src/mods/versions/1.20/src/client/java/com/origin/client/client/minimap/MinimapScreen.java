package com.origin.client.client.minimap;

import com.mojang.blaze3d.systems.RenderSystem;
import com.origin.client.client.OriginClientMod;
import com.origin.client.client.gui.OriginColorPicker;
import com.origin.client.client.gui.OriginDeck;
import com.origin.client.client.mods.Mods;
import com.origin.client.client.waypoints.Waypoints;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.origin.client.client.gui.OriginDeck.*;

/**
 * The full-screen map (the Minimap's "Open Map" key, M by default): the same terrain,
 * radar and waypoints as the HUD map, north-up and big, with scroll-to-zoom and
 * drag-to-pan. It shows what the minimap has already mapped around you -- the loaded
 * area, up to MinimapData.MAX_RADIUS chunks out -- not a saved whole-world atlas.
 *
 * The map stays full-screen (it needs every pixel), so instead of the OriginWindow
 * panel its chrome is drawn with OriginDeck in the same language: the window header
 * (icon, title, subtitle, close) across the top, zoom / re-centre buttons, a rounded
 * frame around the map, smooth labels, and a footer with the cursor's coordinates.
 * Layout is in design px over the whole window (origin top-left, no panel), and the
 * map itself is placed in GUI px from that layout (MinimapRenderer works in GUI px).
 */
final class MinimapScreen extends Screen {
	private static final float M = 24, HEAD_H = 64, FOOT_H = 32;
	private static final float MIN_ZOOM = 0.5f, MAX_ZOOM = 8f;
	private static float zoom = 2f;          // GUI px per block; kept between openings
	private double panX = 0, panZ = 0;       // map centre's offset from the player, in blocks
	private boolean dragging = false;
	private boolean openKeyHeld = true;      // the open key is down as we open; ignore it until released

	private final List<OriginDeck.Hit> hits = new ArrayList<>();
	private final List<OriginDeck.Hit> scratch = new ArrayList<>();
	private float lastS = 1;

	MinimapScreen() {
		super(Component.literal("Map"));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	// 1.20 / 1.20.1: renderBackground(GuiGraphics) has no mouse/tick arguments and
	// Screen.render never calls it (from 1.20.2 it does), so render() draws it.
	@Override
	public void renderBackground(GuiGraphics g) {
		g.fill(0, 0, width, height, 0xD80E1110);
	}

	/** Physical px per design px: the menus' 1080p size, never below 75%. */
	private static float deckScale(int winW, int winH) {
		return OriginDeck.scale(winW, winH, 640, 400);
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		renderBackground(g);
		super.render(g, mouseX, mouseY, partialTick);
		var mc = minecraft;
		if (mc == null || mc.player == null || mc.level == null) {
			return;
		}
		var win = mc.getWindow();
		float s = deckScale(win.getWidth(), win.getHeight());
		lastS = s;
		double gs = win.getGuiScale();
		float dw = win.getWidth() / s, dh = win.getHeight() / s;     // the window in design px
		float mapX = M, mapY = HEAD_H, mapW = dw - 2 * M, mapH = dh - HEAD_H - M - FOOT_H - 16;

		// pass 1: the map's well (under the terrain)
		OriginDeck d0 = new OriginDeck(g, s, 0, 0, mouseX, mouseY, scratch);
		d0.rect(mapX, mapY, mapW, mapH, 6, 0xFF101412);
		d0.end();

		// the map itself, in GUI px, inset 1 design px inside the frame
		float k = (float) (s / gs);   // design px -> GUI px
		float gx0 = (mapX + 1) * k, gy0 = (mapY + 1) * k, gx1 = (mapX + mapW - 1) * k, gy1 = (mapY + mapH - 1) * k;
		float halfW = (gx1 - gx0) / 2f, halfH = (gy1 - gy0) / 2f;
		float cx = gx0 + halfW, cy = gy0 + halfH;
		float partial = mc.getFrameTime();
		var p = mc.player;
		double px = p.xo + (p.getX() - p.xo) * partial, pz = p.zo + (p.getZ() - p.zo) * partial;
		MinimapRenderer.View v = new MinimapRenderer.View(cx, cy, halfW, halfH, false,
				px + panX, pz + panZ, zoom, 0f);
		try {
			MinimapData.update(mc, MinimapData.MAX_RADIUS * 16.0, MinimapHud.look(mc));
		} catch (Throwable t) {
			// terrain just stops updating this frame
		}
		boolean showWp = Mods.bool(MinimapHud.MOD, "showWaypoints") && Mods.on("waypoints");

		g.flush();
		Matrix4f m = g.pose().last().pose();
		// Immediate-mode map geometry draws with the depth test off, like GuiGraphics' own flush.
		RenderSystem.disableDepthTest();
		try {
			// Terrain only inside the scanned window (whole chunks around the player's chunk),
			// clipped to the visible area.
			int r = MinimapData.MAX_RADIUS;
			double wx0 = ((p.getBlockX() >> 4) - r) * 16.0, wz0 = ((p.getBlockZ() >> 4) - r) * 16.0;
			double wx1 = ((p.getBlockX() >> 4) + r + 1) * 16.0, wz1 = ((p.getBlockZ() >> 4) + r + 1) * 16.0;
			double vx0 = v.worldX(cx - halfW, cy), vx1 = v.worldX(cx + halfW, cy);
			double vz0 = v.worldZ(cx, cy - halfH), vz1 = v.worldZ(cx, cy + halfH);
			MinimapRenderer.terrainRect(m, v, Math.max(wx0, vx0), Math.max(wz0, vz0), Math.min(wx1, vx1), Math.min(wz1, vz1));
			MinimapRenderer.entities(g, mc, v, MinimapHud.radar(), partial);
			if (showWp) {
				MinimapRenderer.waypoints(g, v, true, 3.5f);
			}
			MinimapRenderer.party(g, v, 3f);
			float sx = v.sx(px, pz), sy = v.sy(px, pz);
			if (v.contains(sx, sy, 4)) {
				double yawRad = Math.toRadians(p.getViewYRot(partial));
				MinimapRenderer.playerArrow(m, sx, sy, (float) Math.atan2(-Math.sin(yawRad), -Math.cos(yawRad)),
						(float) Mods.num(MinimapHud.MOD, "playerMarkerSize") + 1,
						OriginColorPicker.liveColor(MinimapHud.MOD, "playerMarkerColor"));
			}
		} finally {
			RenderSystem.enableDepthTest();
		}

		// pass 2: the chrome, over the map
		OriginDeck d = new OriginDeck(g, s, 0, 0, mouseX, mouseY, hits);
		try {
			d.rect(mapX, mapY, mapW, mapH, 6, 0, LINE2);   // the frame (border only)
			compass(d, v, k);
			if (showWp) {
				waypointNames(d, v, k);
			}
			drawHeader(d, dw);
			drawFooter(d, v, k, dw, dh, mouseX, mouseY);
		} catch (RuntimeException e) {
			// fail soft: the chrome is decoration; the map stays usable
		} finally {
			d.end();
		}
	}

	// ---- chrome ----

	private void drawHeader(OriginDeck d, float dw) {
		d.icon(MinimapHud.MOD, M, HEAD_H / 2 - 12, 24, TEXT);
		float tx = M + 24 + 12;
		d.text("Map", tx, 30, 17, true, 0.3f, TEXT);
		d.text("The area your minimap has explored around you", tx, 48, 12, false, 0, DIM);

		// right to left: close | re-centre | zoom out, zoom readout, zoom in
		float x = dw - M - 32;
		boolean ch = d.over(x, 16, 32, 32);
		d.rect(x, 16, 32, 32, 6, ch ? 0x1AFFFFFF : 0x0AFFFFFF);
		d.icon("@close", x + 9, 25, 14, ch ? TEXT : DIM);
		d.hit(x, 16, 32, 32, () -> onClose());

		boolean panned = Math.abs(panX) > 0.5 || Math.abs(panZ) > 0.5;
		String rc = "RE-CENTRE";
		float rw = 14 + 22 + d.textW(rc, 11, true, CAPS_TRACK) + 14;
		x -= 8 + rw;
		boolean rh = panned && d.over(x, 16, rw, 32);
		d.rect(x, 16, rw, 32, 6, !panned ? 0x0AFFFFFF : rh ? 0x1AFFFFFF : 0x0FFFFFFF);
		int rfg = !panned ? MUTE : rh ? TEXT : DIM;
		d.icon("@user", x + 14, 25, 14, rfg);
		d.textMid(rc, x + 36, 32, 11, true, CAPS_TRACK, rfg);
		if (panned) {
			d.hit(x, 16, rw, 32, () -> panX = panZ = 0);
		}

		// zoom: [ - ]  2.0x  [ + ] in one control
		float zw = 32 + 56 + 32;
		x -= 8 + zw;
		d.rect(x, 16, zw, 32, 6, 0x0AFFFFFF, LINE);
		boolean mh = d.over(x, 16, 32, 32), ph = d.over(x + zw - 32, 16, 32, 32);
		if (mh) {
			d.rect(x, 16, 32, 32, 6, 0x0FFFFFFF);
		}
		if (ph) {
			d.rect(x + zw - 32, 16, 32, 32, 6, 0x0FFFFFFF);
		}
		d.fill(x + 10, 31.25f, 12, 1.5f, zoom > MIN_ZOOM ? (mh ? TEXT : DIM) : MUTE);   // minus
		d.icon("@plus", x + zw - 32 + 9, 25, 14, zoom < MAX_ZOOM ? (ph ? TEXT : DIM) : MUTE);
		d.textCenter(String.format(Locale.ROOT, "%.1fx", zoom), x + zw / 2, 32, 13, true, 0, TEXT);
		d.hit(x, 16, 32, 32, () -> zoomBy(-1));
		d.hit(x + zw - 32, 16, 32, 32, () -> zoomBy(1));
	}

	private void drawFooter(OriginDeck d, MinimapRenderer.View v, float k, float dw, float dh, int mouseX, int mouseY) {
		float y = dh - M - FOOT_H;
		if (v.contains(mouseX, mouseY, 0)) {
			int mx = (int) Math.floor(v.worldX(mouseX, mouseY)), mz = (int) Math.floor(v.worldZ(mouseX, mouseY));
			String xs = Integer.toString(mx), zs = Integer.toString(mz);
			float w = 14 + d.textW("X", 11, true, CAPS_TRACK) + 8 + d.textW(xs, 13, true, 0) + 18
					+ d.textW("Z", 11, true, CAPS_TRACK) + 8 + d.textW(zs, 13, true, 0) + 14;
			d.rect(M, y, w, FOOT_H, 6, 0xC7141816, LINE);
			float x = M + 14;
			x += d.textMid("X", x, y + 16, 11, true, CAPS_TRACK, MUTE) + 8;
			x += d.textMid(xs, x, y + 16, 13, true, 0, TEXT) + 18;
			x += d.textMid("Z", x, y + 16, 11, true, CAPS_TRACK, MUTE) + 8;
			d.textMid(zs, x, y + 16, 13, true, 0, TEXT);
		}
		String hint = "Scroll to zoom  •  Drag to move  •  Right-click to re-centre";
		d.textRight(hint, dw - M, y + 16, 12, false, 0, MUTE);
	}

	/** N / E / S / W just inside the map's edge (north-up), in the smooth font. */
	private void compass(OriginDeck d, MinimapRenderer.View v, float k) {
		String[] letters = {"N", "E", "S", "W"};
		float[][] dirs = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
		for (int i = 0; i < 4; i++) {
			float[] e = v.clampToEdge(v.cx + dirs[i][0] * 10000f, v.cy + dirs[i][1] * 10000f, 16 * k);
			float ex = e[0] / k, ey = e[1] / k;
			d.circle(ex, ey, 11, 0xB30E1110);
			d.textCenter(letters[i], ex, ey, 12, true, 0, i == 0 ? ACC : TEXT);
		}
	}

	/** Each waypoint's name above its diamond (only the ones on the map), on a dark pill. */
	private void waypointNames(OriginDeck d, MinimapRenderer.View v, float k) {
		String dim = Waypoints.currentDim();
		for (Waypoints.Waypoint wp : Waypoints.all()) {
			if (!wp.enabled || !dim.equals(wp.dimension)) {
				continue;
			}
			float x = v.sx(wp.x + 0.5, wp.z + 0.5), y = v.sy(wp.x + 0.5, wp.z + 0.5);
			if (!v.contains(x, y, 6)) {
				continue;
			}
			float dx = x / k, dy = (y - 3.5f) / k - 14;   // pill just above the 3.5 GUI px diamond
			float tw = d.textW(wp.name, 12, true, 0);
			d.rect(dx - tw / 2 - 7, dy - 10, tw + 14, 20, 5, 0xB30E1110);
			d.textCenter(wp.name, dx, dy, 12, true, 0, TEXT);
		}
	}

	private void zoomBy(int dir) {
		zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom * (dir > 0 ? 1.25f : 0.8f)));
	}

	// ---- input ----

	@Override
	public boolean mouseScrolled(double mx, double my, double sy) {
		if (sy != 0) {
			zoomBy(sy > 0 ? 1 : -1);
			return true;
		}
		return super.mouseScrolled(mx, my, sy);
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (button == 0) {
			double gs = minecraft != null ? minecraft.getWindow().getGuiScale() : 1;
			if (OriginDeck.click(hits, OriginDeck.toDesign(mx, gs, 0, lastS), OriginDeck.toDesign(my, gs, 0, lastS))) {
				return true;
			}
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
