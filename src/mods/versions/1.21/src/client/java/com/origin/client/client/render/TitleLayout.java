package com.origin.client.client.render;

/**
 * One compact, centred title composition shared by the title mixin and the
 * Origin renderer. The structure follows a premium client launcher rather
 * than vanilla's tall list: mark, two primary rows, one paired row, then a
 * small bottom utility dock. All values are GUI-scaled pixels.
 */
public final class TitleLayout {
	public final int mainX, mainW, mainTop, buttonH, rowStep, pairGap;
	public final int dockY, dockSize, dockGap;
	public final int markCenterX, markCenterY, markSize;

	private TitleLayout(int mainX, int mainW, int mainTop, int buttonH, int rowStep, int pairGap,
						int dockY, int dockSize, int dockGap,
						int markCenterX, int markCenterY, int markSize) {
		this.mainX = mainX;
		this.mainW = mainW;
		this.mainTop = mainTop;
		this.buttonH = buttonH;
		this.rowStep = rowStep;
		this.pairGap = pairGap;
		this.dockY = dockY;
		this.dockSize = dockSize;
		this.dockGap = dockGap;
		this.markCenterX = markCenterX;
		this.markCenterY = markCenterY;
		this.markSize = markSize;
	}

	public static TitleLayout of(int sw, int sh) {
		int mainW = clamp((int) Math.round(sw * 0.22), 108, 174);
		int mainX = (sw - mainW) / 2;
		int buttonH = 17;
		int rowStep = 22;
		int pairGap = 4;

		int dockSize = 20;
		int dockGap = 5;
		int dockY = Math.max(8, sh - dockSize - 9);

		int markSize = clamp(sh / 9, 40, 54);
		int markGap = 14;
		int minTop = 48 + markSize + markGap;
		int stackH = buttonH * 3 + (rowStep - buttonH) * 2;
		int maxTop = Math.max(minTop, dockY - stackH - 18);
		int mainTop = clamp((int) Math.round(sh * 0.42), minTop, maxTop);
		int markCenterY = mainTop - markGap - markSize / 2;

		return new TitleLayout(mainX, mainW, mainTop, buttonH, rowStep, pairGap,
				dockY, dockSize, dockGap, sw / 2, markCenterY, markSize);
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}
}
