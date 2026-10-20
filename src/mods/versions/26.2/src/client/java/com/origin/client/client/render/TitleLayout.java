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
 * the corner chip and the buttons never meet the icon row. Other mods can add
 * title buttons (each becomes one more full-width row), so the stack's height
 * comes from the REAL row count ({@link #rows}). When the rows don't fit
 * between the chip and the icon row (GUI scale 4 on 1080p is 480 x 270), the
 * header gives way first: the logo goes (wordmark only), then the wordmark,
 * then the rows tighten toward vanilla's 1 px gap. Past that ({@link #fits}
 * false) the title mixin pairs the added buttons two to a row.
 */
public final class TitleLayout {
	/** Corner chip (player head + name): inset and height (a vanilla button's). */
	public static final int CORNER = 10;
	public static final int CORNER_SIZE = 20;

	private static final int BUTTON_H = 20, ROW_STEP = 24, MIN_ROW_STEP = 21, MAIN_W = 200;
	private static final int DOCK = 20, DOCK_GAP = 4, DOCK_BOTTOM = 14;
	private static final int MARK = 34, MARK_ABOVE = 60, WORD_ABOVE = 16;
	/** Space above the first row with the full header (logo + wordmark) and wordmark only. */
	private static final int HEADER_FULL = MARK_ABOVE + MARK / 2, HEADER_WORD = 34;
	/** Gap kept between the chip and the header, and between the stack and the icon row. */
	private static final int TOP_GAP = 4, DOCK_CLEAR = 12;

	// Rows in the main stack (full rows + the Options | Mods pair), set by the
	// title mixin each frame before anything reads the layout. Render thread only.
	private static int rows = 3;

	public final int mainX, mainW, mainTop, buttonH, rowStep, pairGap;
	public final int dockY, dockSize, dockGap;
	/** markSize 0: no logo this frame. showWordmark false: no wordmark either. */
	public final int markCenterX, markCenterY, markSize, wordBaseline;
	public final boolean showWordmark;
	/** False when even with no header and the tightest rows the stack would meet the icon row. */
	public final boolean fits;

	private TitleLayout(int sw, int mainW, int mainTop, int rowStep, int dockY, int header, boolean fits) {
		this.fits = fits;
		this.mainW = mainW;
		this.mainX = (sw - mainW) / 2;
		this.mainTop = mainTop;
		this.buttonH = BUTTON_H;
		this.rowStep = rowStep;
		this.pairGap = 4;
		this.dockY = dockY;
		this.dockSize = DOCK;
		this.dockGap = DOCK_GAP;
		this.markCenterX = sw / 2;
		this.markCenterY = mainTop - MARK_ABOVE;
		this.markSize = header == HEADER_FULL ? MARK : 0;
		this.wordBaseline = mainTop - WORD_ABOVE;
		this.showWordmark = header > 0;
	}

	/** The title mixin's real row count (vanilla's 3, more when other mods add buttons). */
	public static void rows(int count) {
		rows = Math.max(1, count);
	}

	public static TitleLayout of(int sw, int sh) {
		int mainW = Math.min(MAIN_W, Math.max(100, sw - 24));
		int dockY = Math.max(8, sh - DOCK - DOCK_BOTTOM);
		int top = CORNER + CORNER_SIZE + TOP_GAP;          // first y below the chip
		int bottom = dockY - DOCK_CLEAR;                   // last y above the icon row
		int step = ROW_STEP;
		int stackH = BUTTON_H + (rows - 1) * step;
		int header = HEADER_FULL;
		if (top + header + stackH > bottom) {
			header = HEADER_WORD;
		}
		if (top + header + stackH > bottom) {
			header = 0;
		}
		if (top + header + stackH > bottom && rows > 1) {
			step = Math.max(MIN_ROW_STEP, Math.min(ROW_STEP, (bottom - top - BUTTON_H) / (rows - 1)));
			stackH = BUTTON_H + (rows - 1) * step;
		}
		int minTop = top + header;                         // header clears the corner chip
		int maxTop = Math.max(minTop, bottom - stackH);    // buttons clear the icon row
		int mainTop = clamp((int) Math.round(sh * 0.49), minTop, maxTop);
		return new TitleLayout(sw, mainW, mainTop, step, dockY, header, top + header + stackH <= bottom);
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}
}
