package com.origin.client.client.render;

/**
 * THE one vertical layout of the Origin main menu, shared by TitleScreenMixin
 * (which places the nav buttons) and OriginScreenRenderer (which draws the
 * wordmark). Both used to run their own copy of "the same" formula, and the
 * copies drifted the moment the nav had to shift up on a short window — the
 * wordmark stayed put and landed on top of Singleplayer at most sizes. Now
 * there is exactly one block, laid out top-down, that every part reads.
 *
 * The block, from the top: wordmark (ink height {@link #inkH}) → gap → nav
 * list ({@link #navCount} buttons, 20 px each, ONE even pitch — Will: all
 * evenly spaced). It is fitted between the account chip and the bottom
 * margin: when it doesn't fit, the wordmark shrinks first (down to a floor),
 * then the nav tightens. What's left over is split slightly above centre so
 * the composition sits like the mockup. The language/accessibility icons
 * live bottom-right and never contend with the block.
 *
 * All values are GUI-scaled pixels for the given screen size.
 */
public final class TitleLayout {
	/** Number of nav buttons — set by TitleScreenMixin once it has laid them out. */
	public static volatile int navCount = 6;
	/** Wordmark ink aspect (width / height) — set by OriginScreenRenderer when the texture loads. */
	public static volatile double inkAspect = 5.5;

	public final int left, navW, navTop, navStep, iconRowY;
	public final double inkH, markCenterX, markCenterY;

	private TitleLayout(int left, int navW, int navTop, int navStep, int iconRowY,
						double inkH, double markCenterX, double markCenterY) {
		this.left = left;
		this.navW = navW;
		this.navTop = navTop;
		this.navStep = navStep;
		this.iconRowY = iconRowY;
		this.inkH = inkH;
		this.markCenterX = markCenterX;
		this.markCenterY = markCenterY;
	}

	public static TitleLayout of(int sw, int sh) {
		int n = Math.max(1, navCount);
		double aspect = Math.max(1.0, inkAspect);
		int left = Math.max(24, (int) Math.round(sw * 0.08));
		int navW = Math.max(130, Math.min(180, sw / 4));
		int chipBottom = Math.max(10, (int) Math.round(sw * 0.03)) + 30;
		int topMin = chipBottom + 6;
		int iconRowY = sh - 30;
		int bottomLimit = sh - 12;
		int avail = Math.max(60, bottomLimit - topMin);

		int step = 26, markGap = 22;
		double inkH = Math.min(sh * 0.16, (sw * 0.44) / aspect);
		double inkFloor = Math.min(inkH, Math.max(18.0, sh * 0.09));
		int navH = n * step - (step - 20);
		if (inkH + markGap + navH > avail) {
			inkH = Math.max(inkFloor, avail - markGap - navH);
		}
		if (inkH + markGap + navH > avail) {          // still tight: tighten the nav
			step = 23;
			markGap = 14;
			navH = n * step - (step - 20);
			inkH = Math.max(inkFloor, Math.min(inkH, avail - markGap - navH));
		}
		double blockH = inkH + markGap + navH;
		double blockTop = topMin + Math.max(0.0, (avail - blockH) * 0.40);
		double markCenterY = blockTop + inkH / 2.0;
		double markCenterX = left + (inkH * aspect) / 2.0;
		int navTop = (int) Math.round(blockTop + inkH + markGap);
		return new TitleLayout(left, navW, navTop, step, iconRowY, inkH, markCenterX, markCenterY);
	}
}
