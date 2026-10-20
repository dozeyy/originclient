package com.origin.client.client.render;

/**
 * The title composition on Minecraft's own buttons (Will, 2026-10-19), shared
 * by the title mixin (widget positions) and OriginScreenRenderer (logo,
 * wordmark, account chip). All values are GUI px:
 *
 *   [chip]
 *                        (logo 34)
 *                         ORIGIN
 *               [        Singleplayer        ]   200 x 20 (vanilla's size)
 *               [        Multiplayer         ]   rows 24 apart (vanilla's step)
 *               [   Options   ][    Mods     ]   98 + 4 + 98
 *
 *     (realms)(friends)(cosmetics)(lang)(access)(quit)   20 x 20, gap 4
 *
 * The block sits a touch below centre and is clamped so the logo never meets
 * the corner chip and the buttons never meet the icon row on short windows
 * (427 x 240, the smallest GUI a 1280 x 720 window gets, still fits).
 */
public final class TitleLayout {
	/** Corner chip (player head + name): inset and height (a vanilla button's). */
	public static final int CORNER = 10;
	public static final int CORNER_SIZE = 20;

	private static final int BUTTON_H = 20, ROW_STEP = 24, MAIN_W = 200;
	private static final int DOCK = 20, DOCK_GAP = 4, DOCK_BOTTOM = 14;
	private static final int MARK = 34, MARK_ABOVE = 60, WORD_ABOVE = 16;

	public final int mainX, mainW, mainTop, buttonH, rowStep, pairGap;
	public final int dockY, dockSize, dockGap;
	public final int markCenterX, markCenterY, markSize, wordBaseline;

	private TitleLayout(int sw, int mainW, int mainTop, int dockY) {
		this.mainW = mainW;
		this.mainX = (sw - mainW) / 2;
		this.mainTop = mainTop;
		this.buttonH = BUTTON_H;
		this.rowStep = ROW_STEP;
		this.pairGap = 4;
		this.dockY = dockY;
		this.dockSize = DOCK;
		this.dockGap = DOCK_GAP;
		this.markCenterX = sw / 2;
		this.markCenterY = mainTop - MARK_ABOVE;
		this.markSize = MARK;
		this.wordBaseline = mainTop - WORD_ABOVE;
	}

	public static TitleLayout of(int sw, int sh) {
		int mainW = Math.min(MAIN_W, Math.max(100, sw - 24));
		int dockY = Math.max(8, sh - DOCK - DOCK_BOTTOM);
		int stackH = BUTTON_H + 2 * ROW_STEP;
		int minTop = CORNER + CORNER_SIZE + 4 + MARK_ABOVE + MARK / 2;   // logo top clears the corner chip
		int maxTop = Math.max(minTop, dockY - stackH - 12);              // buttons clear the icon row
		int mainTop = clamp((int) Math.round(sh * 0.49), minTop, maxTop);
		return new TitleLayout(sw, mainW, mainTop, dockY);
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}
}
