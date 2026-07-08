package com.origin.client.client.hud;

import com.origin.client.client.mods.ModsConfigAccess;

// A HUD element's placement: a 9-point screen anchor + pixel offset from
// that anchor + scale. Anchoring (not absolute pixels) is what makes
// positions hold across resolutions and GUI scales. Persisted as
// [anchor, dx, dy, scale] in originclient-mods.json.
public final class HudPos {
	// anchor = row*3+col: 0..8 (TL, TC, TR, ML, MC, MR, BL, BC, BR)
	public int anchor;
	public double dx, dy;
	public double scale = 1.0;

	public HudPos(int anchor, double dx, double dy, double scale) {
		this.anchor = anchor;
		this.dx = dx;
		this.dy = dy;
		this.scale = scale;
	}

	/** Top-left screen position for an element of size (w,h) at this pos. */
	public double x(int screenW, double w) {
		int col = anchor % 3;
		double base = col == 0 ? 0 : col == 1 ? (screenW - w) / 2.0 : screenW - w;
		return base + dx;
	}

	public double y(int screenH, double h) {
		int row = anchor / 3;
		double base = row == 0 ? 0 : row == 1 ? (screenH - h) / 2.0 : screenH - h;
		return base + dy;
	}

	/** Re-derives anchor+offset from an absolute top-left drop position, so
	 *  the element re-anchors to whichever screen region it was dropped in. */
	public void setFromAbsolute(double absX, double absY, int screenW, int screenH, double w, double h) {
		double cx = absX + w / 2.0, cy = absY + h / 2.0;
		int col = cx < screenW / 3.0 ? 0 : cx < screenW * 2 / 3.0 ? 1 : 2;
		int row = cy < screenH / 3.0 ? 0 : cy < screenH * 2 / 3.0 ? 1 : 2;
		anchor = row * 3 + col;
		double baseX = col == 0 ? 0 : col == 1 ? (screenW - w) / 2.0 : screenW - w;
		double baseY = row == 0 ? 0 : row == 1 ? (screenH - h) / 2.0 : screenH - h;
		dx = absX - baseX;
		dy = absY - baseY;
	}

	public static HudPos load(String elementId, HudPos def) {
		double[] v = ModsConfigAccess.hud(elementId);
		return v == null ? def : new HudPos((int) v[0], v[1], v[2], v[3]);
	}

	public void save(String elementId) {
		ModsConfigAccess.putHud(elementId, new double[]{anchor, dx, dy, scale});
	}
}
