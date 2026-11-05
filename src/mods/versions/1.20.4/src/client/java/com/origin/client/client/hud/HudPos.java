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
	// per-module backing opacity (0 = none), drawn as a rounded panel
	public double bg = 0.0;
	// the piece this is the spot of (null for a bare spot): HudLayout places a few pieces by their own rule
	private String id;

	public HudPos(int anchor, double dx, double dy, double scale) {
		this.anchor = anchor;
		this.dx = dx;
		this.dy = dy;
		this.scale = scale;
	}

	/** Top-left screen position for an element of size (w,h) at this pos,
	 *  clamped so the whole element stays on screen. A saved offset can point
	 *  off-screen after a GUI scale / window size change or a synced layout from
	 *  a bigger monitor; with no clamp the piece was unreachable even in the
	 *  editor. The saved value is left alone (it comes back if the screen grows);
	 *  the game, the editor and hit-testing all read through here (the anchor
	 *  maths lives in HudLayout, which the layout test runs without the game). */
	public double x(int screenW, double w) {
		return HudLayout.left(id, anchor, dx, screenW, w);
	}

	public double y(int screenH, double h) {
		return HudLayout.top(anchor, dy, screenH, h);
	}

	/** Re-derives anchor+offset from an absolute top-left drop position, so
	 *  the element re-anchors to whichever screen region it was dropped in. */
	public void setFromAbsolute(double absX, double absY, int screenW, int screenH, double w, double h) {
		double cx = absX + w / 2.0, cy = absY + h / 2.0;
		int col = cx < screenW / 3.0 ? 0 : cx < screenW * 2 / 3.0 ? 1 : 2;
		int row = cy < screenH / 3.0 ? 0 : cy < screenH * 2 / 3.0 ? 1 : 2;
		anchor = row * 3 + col;
		double baseX = HudLayout.baseX(id, col, screenW, w);
		double baseY = row == 0 ? 0 : row == 1 ? (screenH - h) / 2.0 : screenH - h;
		dx = absX - baseX;
		dy = absY - baseY;
	}

	/** The piece's default spot from HudLayout (THE default layout, the same on every version). */
	public static HudPos ofDefault(String elementId) {
		double[] d = HudLayout.spot(elementId);
		HudPos p = new HudPos((int) d[0], d[1], d[2], d[3]);
		p.id = elementId;
		return p;
	}

	/** Where the piece is now: the player's stored spot, else `def` (callers pass {@link #ofDefault}). A stored
	 *  spot equal to a default Origin shipped before reads as `def` too (HudLayout.resolve); nothing is written. */
	public static HudPos load(String elementId, HudPos def) {
		double[] v = HudLayout.resolve(elementId, ModsConfigAccess.hud(elementId),
				new double[]{def.anchor, def.dx, def.dy, def.scale});
		HudPos p = new HudPos((int) v[0], v[1], v[2], v[3]);
		p.bg = v[4];
		p.id = elementId;
		return p;
	}

	public void save(String elementId) {
		ModsConfigAccess.putHud(elementId, new double[]{anchor, dx, dy, scale, bg});
	}

	/** Live drags only (the editor's resize handle): updates the in-memory layout
	 *  without rewriting the config file on every mouse move. The caller writes it
	 *  once with {@link #flush()} on release / screen close. */
	public void saveUnsaved(String elementId) {
		ModsConfigAccess.putHudUnsaved(elementId, new double[]{anchor, dx, dy, scale, bg});
	}

	public static void flush() {
		ModsConfigAccess.flush();
	}
}
