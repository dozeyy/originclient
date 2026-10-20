package com.origin.client.client.render;

/**
 * The title composition from Will's approved clean mockup (2026-10-19), shared
 * by the title mixin (widget positions) and OriginScreenRenderer (logo,
 * wordmark). All values are GUI px:
 *
 *   [chip]                                        [quit]
 *                        (logo 34)
 *                         ORIGIN
 *                 [     Singleplayer     ]   160 x 16
 *                 [     Multiplayer      ]   rows 5 apart
 *                 [ Options  ][  Mods    ]   78 + 4 + 78
 *
 *                 (realms)(friends)(cosmetics)(lang)(access)   16 x 16, gap 4
 *
 * The block sits a touch below centre and is clamped so the logo never meets
 * the corner chip and the buttons never meet the icon row on short windows.
 */
public final class TitleLayout {
	/** Corner controls (account chip, quit): inset and size. */
	public static final int CORNER = 10;
	public static final int CORNER_SIZE = 16;

	public final int mainX, mainW, mainTop, buttonH, rowStep, pairGap;
	public final int dockY, dockSize, dockGap;
	public final int markCenterX, markCenterY, markSize, wordBaseline;
	public final int quitX, quitY;

	private TitleLayout(int sw, int mainW, int mainTop, int dockY, int markCenterY) {
		this.mainW = mainW;
		this.mainX = (sw - mainW) / 2;
		this.mainTop = mainTop;
		this.buttonH = 16;
		this.rowStep = 21;
		this.pairGap = 4;
		this.dockY = dockY;
		this.dockSize = 16;
		this.dockGap = 4;
		this.markCenterX = sw / 2;
		this.markCenterY = markCenterY;
		this.markSize = 34;
		this.wordBaseline = mainTop - 20;
		this.quitX = sw - CORNER - CORNER_SIZE;
		this.quitY = CORNER;
	}

	public static TitleLayout of(int sw, int sh) {
		int mainW = Math.min(160, Math.max(100, sw - 24));
		int dockY = Math.max(8, sh - 16 - 16);
		int stackH = 16 + 2 * 21;
		int minTop = CORNER + CORNER_SIZE + 4 + 34 + 37;    // logo top clears the corner chip
		int maxTop = Math.max(minTop, dockY - stackH - 12);  // buttons clear the icon row
		int mainTop = clamp((int) Math.round(sh * 0.49), minTop, maxTop);
		return new TitleLayout(sw, mainW, mainTop, dockY, mainTop - 54);
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}
}
