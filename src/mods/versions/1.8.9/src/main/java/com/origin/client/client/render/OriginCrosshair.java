package com.origin.client.client.render;

import com.origin.client.client.gui.Gfx;
import com.origin.client.client.gui.OriginColorPicker;
import com.origin.client.client.gui.Pose;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.player.EntityPlayer;

// The Crosshair mod: replaces vanilla's crosshair sprite with a shape built
// from the mod's options, drawn from HudHooks (RenderGameOverlayEvent.Pre
// (CROSSHAIRS), cancelled while the mod draws).
//
// The shape is rasterised into a small boolean pixel grid in REAL screen
// pixels (the pose is scaled by 1/guiScale while drawing), so a 2-pixel
// crosshair is 2 pixels at every GUI scale. The outline is that grid dilated by
// the outline thickness, so every style -- including the circle and diagonals
// -- gets an exact, even edge. The grid is rebuilt only when an option that
// shapes it changes; per frame it is just a few dozen fills.
//
// 1.8.9: GuiIngameForge.renderCrosshairs fires Pre(CROSSHAIRS) and then draws
// the 16x16 icons.png cross only if showCrosshair() -- false with F3 open
// (1.8.9 has no debug-axes crosshair: nothing at all is drawn then), and in
// spectator unless aiming at an entity. There is no attack indicator (1.9+), so
// unlike 1.16.5 nothing else lives in the replaced draw. 1.8.9 vanilla also shows
// its crosshair in third person; Show In F5 decides that for the custom one,
// the same rule as every other version.
public final class OriginCrosshair {
	private OriginCrosshair() {
	}

	private static String cachedKey = null;
	private static boolean[][] fillMask, outlineMask;   // [y][x], centre at (ext, ext)
	private static int ext;

	/**
	 * Draws the custom crosshair.
	 * @return false when vanilla should handle it instead (F3 open: vanilla draws
	 *         nothing then, and Origin keeps that).
	 */
	public static boolean render(Gfx g) {
		Minecraft mc = Minecraft.getMinecraft();
		if (mc.thePlayer == null || mc.playerController == null) {
			return true;
		}
		boolean firstPerson = mc.gameSettings.thirdPersonView == 0;
		if (mc.gameSettings.showDebugInfo && !mc.gameSettings.hideGUI && !mc.thePlayer.hasReducedDebug()
				&& !mc.gameSettings.reducedDebugInfo) {
			return false;   // vanilla hides the crosshair while the debug screen is up
		}
		if (!firstPerson && !Mods.bool("crosshair", "showInF5")) {
			return true;
		}
		if (mc.playerController.isSpectator() && mc.pointedEntity == null) {
			return true;    // vanilla hides the crosshair in spectator unless aiming at an entity
		}
		ensureMask();
		int color = OriginColorPicker.liveColor("crosshair", "color");
		if (Mods.bool("crosshair", "dynamicColor")) {
			Entity target = mc.pointedEntity;
			if (target instanceof EntityLivingBase && target.isEntityAlive()) {
				boolean ally = mc.thePlayer.isOnSameTeam((EntityLivingBase) target);
				boolean hostile = !ally && (target instanceof IMob || target instanceof EntityPlayer);
				color = OriginColorPicker.liveColor("crosshair", hostile ? "enemyColor" : "friendlyColor");
			}
		}
		double gs = Gfx.guiScale();
		int cx = Gfx.windowWidth() / 2;
		int cy = Gfx.windowHeight() / 2;
		Pose p = g.pose();
		p.pushPose();
		try {
			p.scale((float) (1.0 / gs), (float) (1.0 / gs), 1f);
			if (Mods.bool("crosshair", "outline")) {
				drawMask(g, outlineMask, cx, cy, OriginColorPicker.liveColor("crosshair", "outlineColor"));
			}
			drawMask(g, fillMask, cx, cy, color);
		} finally {
			p.popPose();
		}
		return true;
	}

	/** One fill per horizontal run of set pixels. */
	private static void drawMask(Gfx g, boolean[][] mask, int cx, int cy, int color) {
		if (((color >>> 24) & 0xFF) == 0) {
			return;
		}
		int n = mask.length;
		for (int y = 0; y < n; y++) {
			boolean[] row = mask[y];
			int x = 0;
			while (x < n) {
				if (!row[x]) {
					x++;
					continue;
				}
				int start = x;
				while (x < n && row[x]) {
					x++;
				}
				g.fill(cx + start - ext, cy + y - ext, cx + x - ext, cy + y - ext + 1, color);
			}
		}
	}

	// ---- shape rasterisation ----

	private static void ensureMask() {
		String style = Mods.mode("crosshair", "style");
		int s = (int) Mods.num("crosshair", "size");
		int t = Math.max(1, (int) Mods.num("crosshair", "thickness"));
		int gap = (int) Mods.num("crosshair", "gap");
		boolean dot = Mods.bool("crosshair", "dot");
		int dotSize = Math.max(1, (int) Mods.num("crosshair", "dotSize"));
		int o = Mods.bool("crosshair", "outline") ? Math.max(1, (int) Mods.num("crosshair", "outlineThickness")) : 0;
		String key = style + "|" + s + "|" + t + "|" + gap + "|" + dot + "|" + dotSize + "|" + o;
		if (key.equals(cachedKey)) {
			return;
		}
		cachedKey = key;
		ext = gap + s + t + dotSize + o + 3;
		int n = ext * 2 + 1;
		boolean[][] fill = new boolean[n][n];
		int t0 = -(t - 1) / 2, t1 = t0 + t - 1;          // the arm's cross-section around pixel 0
		int d0 = -(dotSize - 1) / 2, d1 = d0 + dotSize - 1;
		for (int y = 0; y < n; y++) {
			for (int x = 0; x < n; x++) {
				int dx = x - ext, dy = y - ext;
				boolean on;
				switch (style) {
					case "Dot":
						on = dx * dx + dy * dy <= (s / 2.0 + 0.25) * (s / 2.0 + 0.25);
						break;
					case "Circle": {
						double d = Math.sqrt(dx * dx + dy * dy);
						double r = gap + s;
						on = d >= r - t / 2.0 && d < r + t / 2.0;
						break;
					}
					case "Square": {
						int m = Math.max(Math.abs(dx), Math.abs(dy));
						int r = gap + s;
						on = m <= r && m > r - t;
						break;
					}
					case "Arrow": {
						int k = dy - gap;                      // apex sits `gap` below centre
						int off = Math.abs(dx) - k;              // thickens outward from the line
						on = k >= 0 && k <= s && off >= 0 && off <= t - 1;
						break;
					}
					case "X": {
						int a = Math.abs(dx), b = Math.abs(dy);
						int m = Math.max(a, b);
						int off = a - b;                         // thickens toward the horizontal
						on = off >= 0 && off <= t - 1 && m > gap && m <= gap + s;
						break;
					}
					default: {                               // "Cross" and "T"
						boolean vert = dx >= t0 && dx <= t1
								&& ((dy >= t0 - gap - s && dy <= t0 - gap - 1 && !style.equals("T"))
								|| (dy >= t1 + gap + 1 && dy <= t1 + gap + s));
						boolean horiz = dy >= t0 && dy <= t1
								&& ((dx >= t0 - gap - s && dx <= t0 - gap - 1)
								|| (dx >= t1 + gap + 1 && dx <= t1 + gap + s));
						on = vert || horiz;
						break;
					}
				}
				if (dot && dx >= d0 && dx <= d1 && dy >= d0 && dy <= d1) {
					on = true;
				}
				fill[y][x] = on;
			}
		}
		boolean[][] outline = new boolean[n][n];
		if (o > 0) {
			for (int y = 0; y < n; y++) {
				for (int x = 0; x < n; x++) {
					if (fill[y][x]) {
						continue;
					}
					search:
					for (int oy = -o; oy <= o; oy++) {
						for (int ox = -o; ox <= o; ox++) {
							int yy = y + oy, xx = x + ox;
							if (yy >= 0 && yy < n && xx >= 0 && xx < n && fill[yy][xx]) {
								outline[y][x] = true;
								break search;
							}
						}
					}
				}
			}
		}
		fillMask = fill;
		outlineMask = outline;
	}
}
